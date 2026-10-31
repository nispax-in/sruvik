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
    internal val cipher: Cipher,
    internal val isNewVault: Boolean,
    internal val pendingDataKey: ByteArray?,
    internal val wrappedDataKey: ByteArray?,
    val allowedAuthenticators: Int
)

data class SrukryptEntryPreview(
    val id: String,
    val title: String,
    val mimeType: String,
    val createdAtMillis: Long,
    val byteCount: Int,
    val thumbnail: Bitmap
)

/**
 * Envelope encryption for Srukrypt:
 * - A random 256-bit data key encrypts files with AES-256-GCM and a fresh nonce.
 * - An Android Keystore AES key, requiring per-use strong-biometric authentication,
 *   wraps the data key. The Keystore key is non-exportable.
 * - The unwrapped data key exists only in memory for the active vault session.
 *
 * The wrapped key and ciphertext live under noBackupFilesDir. Filenames are random IDs;
 * names, MIME types and file contents are all inside authenticated ciphertext.
 */
object SrukryptVault {
    private const val MASTER_ALIAS_ID = "master"
    private const val WRAPPED_KEY_FILE = "master.wrap"
    private const val WRAP_MAGIC = 0x53524B57 // SRKW
    private const val PASSPHRASE_FILE = "passphrase.wrap"
    private const val PASSPHRASE_MAGIC = 0x53524B50 // SRKP
    private const val PASSPHRASE_ITERATIONS = 650_000
    private const val ENTRY_MAGIC = 0x53524B31 // SRK1
    private const val FORMAT_VERSION = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    const val MAX_IMAGE_BYTES = 24 * 1024 * 1024

    private val wrapAad = "Srukrypt:data-key:v1".toByteArray(Charsets.UTF_8)

    fun hasVault(context: Context): Boolean {
        val dir = SrukryptStorage.vaultDirectory(context)
        return File(dir, WRAPPED_KEY_FILE).isFile ||
            File(dir, PASSPHRASE_FILE).isFile ||
            entryFiles(dir).isNotEmpty()
    }

    fun entryCount(context: Context): Int =
        entryFiles(SrukryptStorage.vaultDirectory(context)).size

    fun hasPassphraseFallback(context: Context): Boolean =
        File(SrukryptStorage.vaultDirectory(context), PASSPHRASE_FILE).isFile

    /**
     * For new vaults Android 11+ can authorize the Keystore cipher with either a
     * strong biometric or the device screen credential (PIN, pattern or password).
     * Existing keys retain the authenticator policy they were originally created with.
     */
    fun preferredAuthenticatorFlags(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return BiometricManagerFlags.BIOMETRIC_STRONG
        }
        if (!hasMasterKey()) {
            return BiometricManagerFlags.BIOMETRIC_STRONG or BiometricManagerFlags.DEVICE_CREDENTIAL
        }
        return authenticatorFlagsForKey(getMasterKey())
    }

    private object BiometricManagerFlags {
        const val BIOMETRIC_STRONG = 0x000F
        const val DEVICE_CREDENTIAL = 0x8000
    }

    /**
     * Create a vault protected by a user passphrase. PBKDF2-HMAC-SHA256 derives
     * a 256-bit wrapping key; the actual image data still uses AES-256-GCM.
     * Use at least 10 characters. This is intended as a fallback for devices
     * without a compatible strong biometric/system crypto prompt.
     */
    @Throws(Exception::class)
    fun createVaultWithPassphrase(context: Context, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 10) { "Use a passphrase with at least 10 characters." }
        require(!hasVault(context)) { "A Srukrypt vault already exists." }
        // A prior failed biometric setup may have left an empty orphan Keystore alias.
        // Remove it so this passphrase vault cannot accidentally create a second data key.
        if (hasMasterKey()) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(SrukryptStorage.keyAlias(MASTER_ALIAS_ID))
        }
        val dataKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            writePassphraseEnvelope(
                File(SrukryptStorage.vaultDirectory(context), PASSPHRASE_FILE),
                dataKey,
                passphrase
            )
            return dataKey.copyOf()
        } catch (error: Exception) {
            Arrays.fill(dataKey, 0)
            throw error
        }
    }

    @Throws(Exception::class)
    fun unlockWithPassphrase(context: Context, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 1) { "Enter your Srukrypt passphrase." }
        val file = File(SrukryptStorage.vaultDirectory(context), PASSPHRASE_FILE)
        if (!file.isFile) throw FileNotFoundException("No passphrase fallback has been configured.")
        return readPassphraseEnvelope(file, passphrase)
    }

    @Throws(Exception::class)
    fun enablePassphraseFallback(context: Context, dataKey: ByteArray, passphrase: CharArray) {
        require(dataKey.size == 32) { "Invalid vault session key." }
        require(passphrase.size >= 10) { "Use a passphrase with at least 10 characters." }
        writePassphraseEnvelope(
            File(SrukryptStorage.vaultDirectory(context), PASSPHRASE_FILE),
            dataKey,
            passphrase
        )
    }

    /**
     * Create a Cipher for BiometricPrompt.CryptoObject. The returned
     * authenticator flags exactly match the Android Keystore key policy.
     */
    @Throws(Exception::class)
    fun prepareUnlock(context: Context): SrukryptUnlockChallenge {
        val dir = SrukryptStorage.vaultDirectory(context)
        val wrappedFile = File(dir, WRAPPED_KEY_FILE)
        if (!wrappedFile.exists() && hasPassphraseFallback(context)) {
            throw IllegalStateException("This vault uses a passphrase. Choose “Use passphrase” to unlock it.")
        }
        val existingMasterKey = hasMasterKey()
        val masterKey = if (existingMasterKey) getMasterKey() else createMasterKey()
        val allowedAuthenticators = if (existingMasterKey) {
            authenticatorFlagsForKey(masterKey)
        } else {
            preferredAuthenticatorFlags(context)
        }

        if (!wrappedFile.exists()) {
            if (entryFiles(dir).isNotEmpty()) {
                throw SecurityException("The Srukrypt key envelope is missing. Existing vault data cannot be safely recovered.")
            }
            val dataKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, masterKey)
                cipher.updateAAD(wrapAad)
                SrukryptUnlockChallenge(cipher, true, dataKey, null, allowedAuthenticators)
            } catch (error: Exception) {
                Arrays.fill(dataKey, 0)
                throw error
            }
        }

        val envelope = readEnvelope(wrappedFile, WRAP_MAGIC)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(TAG_BITS, envelope.first))
        cipher.updateAAD(wrapAad)
        return SrukryptUnlockChallenge(cipher, false, null, envelope.second, allowedAuthenticators)
    }

    private fun authenticatorFlagsForKey(key: SecretKey): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return BiometricManagerFlags.BIOMETRIC_STRONG
        }
        // KeyInfo uses KeyProperties bit values, not BiometricPrompt authenticator flags.
        val keyAuth = SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java).userAuthenticationType
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

    @Throws(Exception::class)
    fun finishUnlock(
        context: Context,
        challenge: SrukryptUnlockChallenge,
        authenticatedCipher: Cipher
    ): ByteArray {
        if (challenge.isNewVault) {
            val dataKey = challenge.pendingDataKey
                ?: throw SecurityException("Missing initial vault key.")
            val encryptedKey = authenticatedCipher.doFinal(dataKey)
            val iv = authenticatedCipher.iv ?: throw SecurityException("Missing encryption nonce.")
            writeEnvelopeAtomically(
                File(SrukryptStorage.vaultDirectory(context), WRAPPED_KEY_FILE),
                WRAP_MAGIC,
                iv,
                encryptedKey
            )
            return dataKey.copyOf()
        }

        val wrapped = challenge.wrappedDataKey
            ?: throw SecurityException("Missing wrapped vault key.")
        val dataKey = authenticatedCipher.doFinal(wrapped)
        if (dataKey.size != 32) {
            Arrays.fill(dataKey, 0)
            throw SecurityException("The Srukrypt key envelope is invalid.")
        }
        return dataKey
    }

    fun discardChallenge(challenge: SrukryptUnlockChallenge) {
        challenge.pendingDataKey?.let { Arrays.fill(it, 0) }
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
    fun loadPreviews(context: Context, dataKey: ByteArray): List<SrukryptEntryPreview> {
        val dir = SrukryptStorage.vaultDirectory(context)
        return entryFiles(dir).mapNotNull { file ->
            val id = file.name.removeSuffix(".srk")
            try {
                val plain = decryptEntry(file, id, dataKey)
                try {
                    decodePreview(id, plain)
                } finally {
                    Arrays.fill(plain, 0)
                }
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAtMillis }
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


    private fun writePassphraseEnvelope(file: File, dataKey: ByteArray, passphrase: CharArray) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val derived = derivePassphraseKey(passphrase, salt, PASSPHRASE_ITERATIONS)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"))
            cipher.updateAAD("Srukrypt:passphrase-wrap:v1".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(dataKey)
            val iv = cipher.iv ?: throw SecurityException("Missing passphrase nonce.")
            val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
            try {
                val stream = FileOutputStream(temp)
                val output = DataOutputStream(BufferedOutputStream(stream))
                output.writeInt(PASSPHRASE_MAGIC)
                output.writeInt(PASSPHRASE_ITERATIONS)
                output.writeInt(salt.size)
                output.write(salt)
                output.writeInt(iv.size)
                output.write(iv)
                output.writeInt(encrypted.size)
                output.write(encrypted)
                output.flush()
                stream.fd.sync()
                output.close()
                if (!temp.renameTo(file)) throw IOException("Unable to save the passphrase envelope.")
            } finally {
                if (temp.exists()) temp.delete()
                Arrays.fill(encrypted, 0)
            }
        } finally {
            Arrays.fill(derived, 0)
            Arrays.fill(salt, 0)
        }
    }

    private fun readPassphraseEnvelope(file: File, passphrase: CharArray): ByteArray {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != PASSPHRASE_MAGIC) throw SecurityException("Unsupported passphrase envelope.")
            val iterations = input.readInt()
            if (iterations !in 200_000..1_500_000) throw SecurityException("Invalid passphrase work factor.")
            val saltLength = input.readInt()
            if (saltLength != 16) throw SecurityException("Invalid passphrase salt.")
            val salt = ByteArray(saltLength)
            input.readFully(salt)
            val ivLength = input.readInt()
            if (ivLength != IV_BYTES) throw SecurityException("Invalid passphrase nonce.")
            val iv = ByteArray(ivLength)
            input.readFully(iv)
            val encryptedLength = input.readInt()
            if (encryptedLength != 48 || encryptedLength > input.available()) {
                throw SecurityException("Invalid passphrase key envelope.")
            }
            val encrypted = ByteArray(encryptedLength)
            input.readFully(encrypted)
            val derived = derivePassphraseKey(passphrase, salt, iterations)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD("Srukrypt:passphrase-wrap:v1".toByteArray(Charsets.UTF_8))
                val dataKey = try {
                    cipher.doFinal(encrypted)
                } catch (error: AEADBadTagException) {
                    throw SecurityException("That passphrase is incorrect.", error)
                }
                if (dataKey.size != 32) {
                    Arrays.fill(dataKey, 0)
                    throw SecurityException("The passphrase envelope is invalid.")
                }
                return dataKey
            } finally {
                Arrays.fill(salt, 0)
                Arrays.fill(derived, 0)
                Arrays.fill(encrypted, 0)
            }
        }
    }

    private fun derivePassphraseKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
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

    private fun hasMasterKey(): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.containsAlias(SrukryptStorage.keyAlias(MASTER_ALIAS_ID))
    }

    private fun getMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(SrukryptStorage.keyAlias(MASTER_ALIAS_ID), null) as? SecretKey
            ?: throw SecurityException("The Srukrypt Keystore key is unavailable.")
    }

    private fun createMasterKey(): SecretKey {
        val alias = SrukryptStorage.keyAlias(MASTER_ALIAS_ID)
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
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
            // A newly enrolled fingerprint must not orphan the device-credential fallback.
            builder.setInvalidatedByBiometricEnrollment(false)
        } else {
            builder.setInvalidatedByBiometricEnrollment(true)
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

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
