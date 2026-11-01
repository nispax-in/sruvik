package `in`.nispax.sruvik.security

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password envelope for Srukrypt's random 256-bit data key.
 *
 * This class is deliberately independent of Android Keystore so its file-format,
 * replacement and wrong-password behavior can be exercised in JVM unit tests.
 */
internal object SrukryptPassphraseEnvelope {
    private const val MAGIC = 0x53524B50 // SRKP
    private const val ITERATIONS = 650_000
    private const val MIN_ITERATIONS = 200_000
    private const val MAX_ITERATIONS = 1_500_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val DATA_KEY_BYTES = 32
    private const val ENCRYPTED_KEY_BYTES = DATA_KEY_BYTES + TAG_BITS / 8
    private val aad = "Srukrypt:passphrase-wrap:v1".toByteArray(Charsets.UTF_8)

    fun write(file: File, dataKey: ByteArray, passphrase: CharArray) {
        require(dataKey.size == DATA_KEY_BYTES) { "Invalid vault data key." }
        require(passphrase.size >= 10) { "Use a passphrase with at least 10 characters." }
        val parent = file.absoluteFile.parentFile
            ?: throw IOException("Unable to prepare the Srukrypt passphrase directory.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to prepare the Srukrypt passphrase directory.")
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(passphrase, salt, ITERATIONS)
        var encrypted = ByteArray(0)
        var iv = ByteArray(0)
        val temp = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"))
            cipher.updateAAD(aad)
            encrypted = cipher.doFinal(dataKey)
            iv = cipher.iv ?: throw SecurityException("Missing passphrase encryption nonce.")
            check(iv.size == IV_BYTES && encrypted.size == ENCRYPTED_KEY_BYTES) {
                "The passphrase key envelope has an unexpected format."
            }

            val raw = FileOutputStream(temp)
            try {
                val output = DataOutputStream(BufferedOutputStream(raw))
                output.writeInt(MAGIC)
                output.writeInt(ITERATIONS)
                output.writeInt(salt.size)
                output.write(salt)
                output.writeInt(iv.size)
                output.write(iv)
                output.writeInt(encrypted.size)
                output.write(encrypted)
                output.flush()
                raw.fd.sync()
                output.close()
            } finally {
                runCatching { raw.close() }
            }

            // REPLACE_EXISTING matters when a user changes their fallback passphrase.
            // Keep source and destination in the same private directory for atomic rename.
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
            Arrays.fill(salt, 0)
            Arrays.fill(derived, 0)
            Arrays.fill(iv, 0)
            Arrays.fill(encrypted, 0)
        }
    }

    fun read(file: File, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Enter your Srukrypt passphrase." }
        if (!file.isFile) throw java.io.FileNotFoundException("No passphrase fallback has been configured.")

        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC) throw SecurityException("Unsupported passphrase envelope.")
            val iterations = input.readInt()
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
                throw SecurityException("Invalid passphrase work factor.")
            }
            val saltLength = input.readInt()
            if (saltLength != SALT_BYTES) throw SecurityException("Invalid passphrase salt.")
            val salt = ByteArray(saltLength)
            input.readFully(salt)
            val ivLength = input.readInt()
            if (ivLength != IV_BYTES) {
                Arrays.fill(salt, 0)
                throw SecurityException("Invalid passphrase nonce.")
            }
            val iv = ByteArray(ivLength)
            input.readFully(iv)
            val encryptedLength = input.readInt()
            if (encryptedLength != ENCRYPTED_KEY_BYTES || encryptedLength > input.available()) {
                Arrays.fill(salt, 0)
                Arrays.fill(iv, 0)
                throw SecurityException("Invalid passphrase key envelope.")
            }
            val encrypted = ByteArray(encryptedLength)
            input.readFully(encrypted)
            val derived = derive(passphrase, salt, iterations)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(aad)
                val dataKey = try {
                    cipher.doFinal(encrypted)
                } catch (error: AEADBadTagException) {
                    throw SecurityException("That passphrase is incorrect.", error)
                }
                if (dataKey.size != DATA_KEY_BYTES) {
                    Arrays.fill(dataKey, 0)
                    throw SecurityException("The passphrase envelope is invalid.")
                }
                return dataKey
            } finally {
                Arrays.fill(salt, 0)
                Arrays.fill(iv, 0)
                Arrays.fill(encrypted, 0)
                Arrays.fill(derived, 0)
            }
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, DATA_KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
