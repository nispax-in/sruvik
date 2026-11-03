package `in`.nispax.sruvik.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.*
import java.security.KeyStore
import android.security.keystore.KeyInfo
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SrukryptUnlockChallenge internal constructor(
    internal val isNewVault: Boolean,
    internal val pendingDataKey: ByteArray?,
    internal val wrappedIv: ByteArray?,
    internal val wrappedDataKey: ByteArray?,
    val allowedAuthenticators: Int,
    internal val keyAliasId: String = "master",
    internal val wrappedFileName: String = "master.wrap"
)

data class SrukryptEntryPreview(
    val id: String,
    val title: String,
    val mimeType: String,
    val createdAtMillis: Long,
    val byteCount: Int,
    val thumbnail: Bitmap
)

/** Successful previews plus the count of encrypted entries that could not be authenticated or decoded. */
data class SrukryptPreviewLoadResult(
    val entries: List<SrukryptEntryPreview>,
    val unreadableEntries: Int
)

/**
 * Envelope encryption for Srukrypt:
 * - A random 256-bit data key encrypts files with AES-256-GCM and a fresh nonce.
 * - An Android Keystore AES key, requiring per-use Android system authentication
 *   (strong enrolled biometric or device PIN/pattern/password), wraps the data key.
 *   The Keystore key is non-exportable.
 * - The optional passphrase is a reset-only verifier. It never unwraps or unlocks
 *   the data key; a successful check only permits a separately confirmed wipe.
 * - The unwrapped data key exists only in memory for the active vault session.
 *
 * The wrapped key and ciphertext live under noBackupFilesDir. Filenames are random IDs;
 * names, MIME types and file contents are all inside authenticated ciphertext.
 */
object SrukryptVault {
    private const val MASTER_ALIAS_ID = "master"
    private const val WRAPPED_KEY_FILE = "master.wrap"
    // A replacement biometric key is written beside the original key envelope.
    // The original remains usable until a replacement prompt succeeds.
    private const val REPAIRED_MASTER_ALIAS_ID = "master.repaired"
    private const val REPAIRED_WRAPPED_KEY_FILE = "master.repaired.wrap"
    private const val WRAP_MAGIC = 0x53524B57 // SRKW
    private const val PASSPHRASE_FILE = "passphrase.wrap"
    private const val ENTRY_MAGIC = 0x53524B31 // SRK1
    private const val FORMAT_VERSION = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    // Android's device-credential fallback can authorize a time-based Keystore key.
    // Keep this window short; it is consumed immediately after the system prompt.
    private const val AUTHORIZATION_WINDOW_SECONDS = 15
    const val MAX_IMAGE_BYTES = 24 * 1024 * 1024

    private val wrapAad = "Srukrypt:data-key:v1".toByteArray(Charsets.UTF_8)

    fun hasVault(context: Context): Boolean {
        val dir = SrukryptStorage.vaultDirectory(context)
        return File(dir, WRAPPED_KEY_FILE).isFile ||
            File(dir, REPAIRED_WRAPPED_KEY_FILE).isFile ||
            File(dir, PASSPHRASE_FILE).isFile ||
            entryFiles(dir).isNotEmpty()
    }

    fun entryCount(context: Context): Int =
        entryFiles(SrukryptStorage.vaultDirectory(context)).size

    fun hasResetPassphrase(context: Context): Boolean =
        File(SrukryptStorage.vaultDirectory(context), PASSPHRASE_FILE).isFile

    /** True only when a wrapped data key and its matching Android Keystore key exist. */
    fun hasDeviceUnlock(context: Context): Boolean {
        val directory = SrukryptStorage.vaultDirectory(context)
        val repaired = File(directory, REPAIRED_WRAPPED_KEY_FILE)
        if (repaired.isFile && hasMasterKey(REPAIRED_MASTER_ALIAS_ID)) return true
        val primary = File(directory, WRAPPED_KEY_FILE)
        return primary.isFile && hasMasterKey(MASTER_ALIAS_ID)
    }

    /**
     * Permanently reset the vault. Ciphertext is removed before Keystore aliases;
     * if deleting any vault file fails, the method stops and reports the failure.
     * This is a destructive reset, not a recovery operation.
     */
    @Throws(Exception::class)
    fun resetVault(context: Context) {
        val directory = File(context.noBackupFilesDir, "srukrypt")
        if (directory.exists()) {
            directory.walkBottomUp().forEach { entry ->
                if (entry.exists() && !entry.delete()) {
                    throw IOException("Unable to remove a Srukrypt vault file. The reset was not completed.")
                }
            }
        }

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf(MASTER_ALIAS_ID, REPAIRED_MASTER_ALIAS_ID).forEach { aliasId ->
            val alias = SrukryptStorage.keyAlias(aliasId)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    /**
     * For new vaults Android 11+ can authorize the Keystore cipher with either a
     * strong biometric or the device screen credential (PIN, pattern or password).
     * Existing keys retain the authenticator policy they were originally created with.
     */
    fun preferredAuthenticatorFlags(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return BiometricManagerFlags.BIOMETRIC_STRONG
        }
        val aliasId = activeMasterAlias(context)
        if (!hasMasterKey(aliasId)) {
            return BiometricManagerFlags.BIOMETRIC_STRONG or BiometricManagerFlags.DEVICE_CREDENTIAL
        }
        return authenticatorFlagsForKey(getMasterKey(aliasId))
    }

    private object BiometricManagerFlags {
        const val BIOMETRIC_STRONG = 0x000F
        const val DEVICE_CREDENTIAL = 0x8000
    }

    /**
     * Create a Cipher for BiometricPrompt.CryptoObject. The returned
     * authenticator flags exactly match the Android Keystore key policy.
     */
    @Throws(Exception::class)
    fun prepareUnlock(context: Context): SrukryptUnlockChallenge {
        val dir = SrukryptStorage.vaultDirectory(context)
        val candidates = listOf(
            MASTER_ALIAS_ID to File(dir, WRAPPED_KEY_FILE),
            REPAIRED_MASTER_ALIAS_ID to File(dir, REPAIRED_WRAPPED_KEY_FILE)
        ).filter { (aliasId, file) ->
            file.isFile && hasMasterKey(aliasId)
        }.sortedByDescending { (aliasId, _) ->
            val flags = runCatching { authenticatorFlagsForKey(getMasterKey(aliasId)) }
                .getOrDefault(0)
            (flags and BiometricManagerFlags.DEVICE_CREDENTIAL) != 0
        }

        var lastCandidateFailure: Exception? = null
        for ((keyAliasId, wrappedFile) in candidates) {
            var envelope: Pair<ByteArray, ByteArray>? = null
            try {
                val masterKey = getMasterKey(keyAliasId)
                val validitySeconds = keyAuthenticationValiditySeconds(masterKey)
                require(validitySeconds > 0) {
                    "This vault was created with an older biometric-only key. A device PIN/pattern/password cannot authorize that legacy key. Your encrypted files were preserved. Use the reset phrase only if you choose to erase this vault."
                }
                val allowedAuthenticators = authenticatorFlagsForKey(masterKey)
                envelope = readEnvelope(wrappedFile, WRAP_MAGIC)

                // Do not initialize Cipher here. Device-credential fallback is
                // time-based authentication: Android must first complete its system
                // prompt, then Cipher.init consumes the Keystore authorization.
                return SrukryptUnlockChallenge(
                    false,
                    null,
                    envelope.first,
                    envelope.second,
                    allowedAuthenticators,
                    keyAliasId,
                    wrappedFile.name
                )
            } catch (failure: Exception) {
                envelope?.first?.let { Arrays.fill(it, 0) }
                envelope?.second?.let { Arrays.fill(it, 0) }
                lastCandidateFailure = failure
                android.util.Log.w(
                    "Srukrypt",
                    "A preserved Srukrypt key envelope could not be prepared for system device authentication.",
                    failure
                )
            }
        }
        if (lastCandidateFailure != null) throw lastCandidateFailure

        val hasAnyWrappedKey = File(dir, WRAPPED_KEY_FILE).isFile ||
            File(dir, REPAIRED_WRAPPED_KEY_FILE).isFile
        if (hasAnyWrappedKey) {
            throw SecurityException(
                "The original Srukrypt Keystore key is missing. No replacement key was created and your encrypted files were left untouched. A reset passphrase cannot recover these encrypted files."
            )
        }

        // Passphrase-only legacy vaults can never be opened by a new Keystore key.
        if (hasResetPassphrase(context)) {
            throw SecurityException(
                "This is a legacy passphrase-only Srukrypt vault. Passphrases can no longer unlock vault contents; they are reset-only. Use “Forgot password? Reset vault” to erase this legacy vault, then create a new one with Android device authentication."
            )
        }

        if (entryFiles(dir).isNotEmpty()) {
            throw SecurityException(
                "The Srukrypt key envelope is missing. Existing encrypted files were left untouched; a new key was not created."
            )
        }

        // The previous build could leave an auth-per-operation alias behind after
        // the combined biometric/device-credential prompt failed. It has no wrapped
        // data key or ciphertext yet, so replace only that empty orphan key.
        var masterKey = if (hasMasterKey(MASTER_ALIAS_ID)) getMasterKey(MASTER_ALIAS_ID) else null
        if (masterKey != null && keyAuthenticationValiditySeconds(masterKey) <= 0) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(SrukryptStorage.keyAlias(MASTER_ALIAS_ID))
            masterKey = null
        }
        if (masterKey == null) {
            masterKey = createMasterKey(MASTER_ALIAS_ID)
        }

        val allowedAuthenticators = authenticatorFlagsForKey(masterKey)
        val dataKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return SrukryptUnlockChallenge(
            true,
            dataKey,
            null,
            null,
            allowedAuthenticators,
            MASTER_ALIAS_ID,
            WRAPPED_KEY_FILE
        )
    }

    /**
     * Save a reset-only passphrase while the vault is already unlocked.
     * It encrypts a random verifier token, never the vault data key.
     */
    @Throws(Exception::class)
    fun setResetPassphrase(context: Context, sessionKey: ByteArray, passphrase: CharArray) {
        require(sessionKey.size == 32) { "Unlock the vault before setting a reset passphrase." }
        require(passphrase.size >= 10) { "Use a passphrase with at least 10 characters." }
        val verifier = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            SrukryptPassphraseEnvelope.write(
                File(SrukryptStorage.vaultDirectory(context), PASSPHRASE_FILE),
                verifier,
                passphrase
            )
        } finally {
            Arrays.fill(verifier, 0)
        }
    }

    /**
     * Verify a reset passphrase. The decrypted verifier is immediately wiped;
     * this method never returns or installs a vault data key.
     */
    @Throws(Exception::class)
    fun verifyResetPassphrase(context: Context, passphrase: CharArray) {
        require(passphrase.isNotEmpty()) { "Enter your Srukrypt reset passphrase." }
        val file = File(SrukryptStorage.vaultDirectory(context), PASSPHRASE_FILE)
        if (!file.isFile) throw FileNotFoundException("No reset passphrase has been configured.")
        val verifier = SrukryptPassphraseEnvelope.read(file, passphrase)
        try {
            require(verifier.size == 32) { "The reset passphrase record is invalid." }
        } finally {
            Arrays.fill(verifier, 0)
        }
    }

    private fun activeMasterAlias(context: Context): String {
        val repairedEnvelope = File(SrukryptStorage.vaultDirectory(context), REPAIRED_WRAPPED_KEY_FILE)
        return if (repairedEnvelope.isFile && hasMasterKey(REPAIRED_MASTER_ALIAS_ID)) {
            REPAIRED_MASTER_ALIAS_ID
        } else {
            MASTER_ALIAS_ID
        }
    }

    private fun keyAuthenticationValiditySeconds(key: SecretKey): Int {
        val keyInfo = SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return keyInfo.userAuthenticationValidityDurationSeconds
    }

    private fun authenticatorFlagsForKey(key: SecretKey): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return BiometricManagerFlags.BIOMETRIC_STRONG
        }
        // KeyInfo uses KeyProperties bit values, not BiometricPrompt authenticator flags.
        val keyInfo = SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        // Normalize the platform value to Int before applying the AUTH_* bit flags.
        val keyAuth = keyInfo.userAuthenticationType.toInt()
        var flags = 0
        if ((keyAuth and KeyProperties.AUTH_BIOMETRIC_STRONG) != 0) {
            flags = flags or BiometricManagerFlags.BIOMETRIC_STRONG
        }
        if ((keyAuth and KeyProperties.AUTH_DEVICE_CREDENTIAL) != 0) {
            flags = flags or BiometricManagerFlags.DEVICE_CREDENTIAL
        }
        if (flags == 0) throw SecurityException("The Srukrypt Keystore key has no supported unlock method.")
        return flags
    }

    /**
     * Create an initialized AES-GCM cipher and bind the envelope metadata.
     *
     * Cipher.init() must happen before updateAAD(). Calling updateAAD() first
     * throws IllegalStateException("Cipher not initialized") on Android/JCA.
     */
    internal fun initializedWrapCipher(
        mode: Int,
        key: SecretKey,
        iv: ByteArray? = null
    ): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        when (mode) {
            Cipher.ENCRYPT_MODE -> {
                require(iv == null) { "Encryption must generate a fresh GCM nonce." }
                cipher.init(Cipher.ENCRYPT_MODE, key)
            }
            Cipher.DECRYPT_MODE -> {
                val nonce = requireNotNull(iv) { "A GCM nonce is required for decryption." }
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            }
            else -> throw IllegalArgumentException("Unsupported Srukrypt cipher mode.")
        }
        // Supply AAD after init, but before any plaintext/ciphertext operation.
        cipher.updateAAD(wrapAad)
        return cipher
    }

    @Throws(Exception::class)
    fun finishUnlock(context: Context, challenge: SrukryptUnlockChallenge): ByteArray {
        val masterKey = getMasterKey(challenge.keyAliasId)

        if (challenge.isNewVault) {
            val dataKey = challenge.pendingDataKey
                ?: throw SecurityException("Missing initial vault key.")
            // Android authentication has completed before the Keystore operation.
            val cipher = initializedWrapCipher(Cipher.ENCRYPT_MODE, masterKey)
            val encryptedKey = cipher.doFinal(dataKey)
            val iv = cipher.iv ?: throw SecurityException("Missing encryption nonce.")
            try {
                writeEnvelopeAtomically(
                    File(SrukryptStorage.vaultDirectory(context), challenge.wrappedFileName),
                    WRAP_MAGIC,
                    iv,
                    encryptedKey
                )
            } finally {
                Arrays.fill(iv, 0)
                Arrays.fill(encryptedKey, 0)
            }
            return dataKey.copyOf()
        }

        val wrapped = challenge.wrappedDataKey
            ?: throw SecurityException("Missing wrapped vault key.")
        val iv = challenge.wrappedIv
            ?: throw SecurityException("Missing wrapped vault nonce.")
        // The same init -> AAD -> doFinal ordering is used for decryption.
        val cipher = initializedWrapCipher(Cipher.DECRYPT_MODE, masterKey, iv)
        val dataKey = cipher.doFinal(wrapped)
        if (dataKey.size != 32) {
            Arrays.fill(dataKey, 0)
            throw SecurityException("The Srukrypt key envelope is invalid.")
        }
        return dataKey
    }

    fun discardChallenge(challenge: SrukryptUnlockChallenge) {
        challenge.pendingDataKey?.let { Arrays.fill(it, 0) }
        challenge.wrappedIv?.let { Arrays.fill(it, 0) }
        challenge.wrappedDataKey?.let { Arrays.fill(it, 0) }
    }

    @Throws(Exception::class)
    fun storeImage(
        context: Context,
        dataKey: ByteArray,
        title: String,
        mimeType: String,
        imageBytes: ByteArray
    ): String {
        require(dataKey.size == 32) { "Invalid vault session key." }
        require(imageBytes.isNotEmpty() && imageBytes.size <= MAX_IMAGE_BYTES) {
            "Choose an image smaller than 24 MB."
        }
        require(mimeType.startsWith("image/")) { "Only image files can be added to Srukrypt." }

        val id = UUID.randomUUID().toString()
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF(title.trim().take(72).ifBlank { "Private image" })
                out.writeUTF(mimeType.take(64))
                out.writeLong(System.currentTimeMillis())
                out.writeInt(imageBytes.size)
                out.write(imageBytes)
            }
            bytes.toByteArray()
        }

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"))
            cipher.updateAAD(entryAad(id))
            val encrypted = cipher.doFinal(plain)
            val iv = cipher.iv ?: throw SecurityException("Missing image encryption nonce.")
            val target = File(SrukryptStorage.vaultDirectory(context), "$id.srk")
            writeEnvelopeAtomically(target, ENTRY_MAGIC, iv, encrypted)
            return id
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    @Throws(Exception::class)
    fun loadPreviews(context: Context, dataKey: ByteArray): List<SrukryptEntryPreview> =
        loadPreviewsWithReport(context, dataKey).entries

    /**
     * Load every readable entry while explicitly reporting damaged or unsupported
     * records. A preview failure never deletes its encrypted source file.
     */
    @Throws(Exception::class)
    fun loadPreviewsWithReport(context: Context, dataKey: ByteArray): SrukryptPreviewLoadResult {
        val dir = SrukryptStorage.vaultDirectory(context)
        var unreadable = 0
        val previews = buildList {
            entryFiles(dir).forEach { file ->
                val id = file.name.removeSuffix(".srk")
                try {
                    val plain = decryptEntry(file, id, dataKey)
                    try {
                        add(decodePreview(id, plain))
                    } finally {
                        Arrays.fill(plain, 0)
                    }
                } catch (failure: Exception) {
                    // Keep the ciphertext on disk; never hide this integrity issue.
                    unreadable++
                    android.util.Log.e("Srukrypt", "Encrypted vault entry $id could not be opened; file preserved.", failure)
                }
            }
        }.sortedByDescending { it.createdAtMillis }
        return SrukryptPreviewLoadResult(previews, unreadable)
    }

    @Throws(Exception::class)
    fun readImage(context: Context, dataKey: ByteArray, id: String): Pair<String, ByteArray> {
        require(isValidId(id))
        val file = File(SrukryptStorage.vaultDirectory(context), "$id.srk")
        if (!file.isFile) throw FileNotFoundException("This vault item no longer exists.")
        val plain = decryptEntry(file, id, dataKey)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { input ->
                val title = input.readUTF()
                input.readUTF()
                input.readLong()
                val length = input.readInt()
                if (length <= 0 || length > MAX_IMAGE_BYTES || length > input.available()) {
                    throw SecurityException("The encrypted image record is invalid.")
                }
                val image = ByteArray(length)
                input.readFully(image)
                return title to image
            }
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    fun deleteEntry(context: Context, id: String): Boolean {
        require(isValidId(id))
        val file = File(SrukryptStorage.vaultDirectory(context), "$id.srk")
        return !file.exists() || file.delete()
    }


    private fun decodePreview(id: String, plain: ByteArray): SrukryptEntryPreview {
        DataInputStream(ByteArrayInputStream(plain)).use { input ->
            val title = input.readUTF()
            val mime = input.readUTF()
            val created = input.readLong()
            val length = input.readInt()
            if (length <= 0 || length > MAX_IMAGE_BYTES || length > input.available()) {
                throw SecurityException("Invalid image length.")
            }
            val image = ByteArray(length)
            input.readFully(image)
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(
                    image, 0, image.size,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                ) ?: throw SecurityException("This image could not be previewed.")
                return SrukryptEntryPreview(id, title, mime, created, length, bitmap)
            } finally {
                Arrays.fill(image, 0)
            }
        }
    }

    private fun decryptEntry(file: File, id: String, dataKey: ByteArray): ByteArray {
        val envelope = readEnvelope(file, ENTRY_MAGIC)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(TAG_BITS, envelope.first))
        cipher.updateAAD(entryAad(id))
        return cipher.doFinal(envelope.second)
    }

    private fun entryAad(id: String) =
        "Srukrypt:entry:v$FORMAT_VERSION:$id".toByteArray(Charsets.UTF_8)

    private fun hasMasterKey(aliasId: String = MASTER_ALIAS_ID): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.containsAlias(SrukryptStorage.keyAlias(aliasId))
    }

    private fun getMasterKey(aliasId: String = MASTER_ALIAS_ID): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(SrukryptStorage.keyAlias(aliasId), null) as? SecretKey
            ?: throw SecurityException("The Srukrypt Keystore key is unavailable.")
    }

    private fun createMasterKey(aliasId: String = MASTER_ALIAS_ID): SecretKey {
        val alias = SrukryptStorage.keyAlias(aliasId)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android explicitly requires a time-based key when a prompt can fall
            // back to the device credential. Per-operation keys (timeout 0) require
            // a CryptoObject and cannot use PIN/pattern/password fallback.
            builder.setUserAuthenticationParameters(
                AUTHORIZATION_WINDOW_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            // Pre-Android 11: use the system confirm-device-credential screen.
            // The deprecated API maps a positive duration to biometric OR credential.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTHORIZATION_WINDOW_SECONDS)
        }
        // Adding/removing fingerprints must not orphan the device-credential key.
        builder.setInvalidatedByBiometricEnrollment(false)

        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun entryFiles(directory: File): List<File> =
        directory.listFiles { file ->
            file.isFile && file.name.endsWith(".srk") && isValidId(file.name.removeSuffix(".srk"))
        }?.toList().orEmpty()

    private fun isValidId(id: String): Boolean =
        id.matches(Regex("[0-9a-fA-F-]{36}"))

    private fun readEnvelope(file: File, expectedMagic: Int): Pair<ByteArray, ByteArray> {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != expectedMagic) throw SecurityException("Unsupported Srukrypt file.")
            val ivLength = input.readInt()
            if (ivLength != IV_BYTES) throw SecurityException("Invalid encryption nonce.")
            val iv = ByteArray(ivLength)
            input.readFully(iv)
            val encryptedLength = input.readInt()
            if (encryptedLength < 16 || encryptedLength > MAX_IMAGE_BYTES + 4096 ||
                encryptedLength > input.available()
            ) throw SecurityException("Invalid encrypted payload.")
            val encrypted = ByteArray(encryptedLength)
            input.readFully(encrypted)
            return iv to encrypted
        }
    }

    private fun writeEnvelopeAtomically(file: File, magic: Int, iv: ByteArray, encrypted: ByteArray) {
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            val stream = FileOutputStream(temp)
            val output = DataOutputStream(BufferedOutputStream(stream))
            output.writeInt(magic)
            output.writeInt(iv.size)
            output.write(iv)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
            stream.fd.sync()
            output.close()
            if (!temp.renameTo(file)) throw IOException("Unable to commit encrypted Srukrypt data.")
        } finally {
            if (temp.exists()) temp.delete()
            Arrays.fill(encrypted, 0)
        }
    }
}
