package `in`.nispax.sruvik.security

import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SrukryptCipherTest {
    @Test
    fun aesGcmWrapCipherInitializesBeforeSupplyingAdditionalAuthenticatedData() {
        val keyBytes = ByteArray(32) { index -> (index * 7 + 3).toByte() }
        val key = SecretKeySpec(keyBytes, "AES")
        val plaintext = "Srukrypt vault data-key test".toByteArray(Charsets.UTF_8)

        var ciphertext: ByteArray? = null
        var nonce: ByteArray? = null
        try {
            val encryptor = SrukryptVault.initializedWrapCipher(
                Cipher.ENCRYPT_MODE,
                key
            )
            nonce = encryptor.iv.copyOf()
            ciphertext = encryptor.doFinal(plaintext)

            val decryptor = SrukryptVault.initializedWrapCipher(
                Cipher.DECRYPT_MODE,
                key,
                nonce
            )
            val restored = decryptor.doFinal(ciphertext)
            try {
                assertArrayEquals(plaintext, restored)
            } finally {
                Arrays.fill(restored, 0)
            }
        } finally {
            Arrays.fill(keyBytes, 0)
            Arrays.fill(plaintext, 0)
            ciphertext?.let { Arrays.fill(it, 0) }
            nonce?.let { Arrays.fill(it, 0) }
        }
    }
}
