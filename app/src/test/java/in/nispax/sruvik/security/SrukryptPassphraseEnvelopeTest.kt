package `in`.nispax.sruvik.security

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SrukryptPassphraseEnvelopeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripsA256BitDataKey() {
        val file = File(temporaryFolder.root, "passphrase.wrap")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val password = "correct horse battery staple".toCharArray()
        SrukryptPassphraseEnvelope.write(file, key, password)

        assertTrue(file.isFile)
        assertFalse(String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1)
            .contains(String(key, StandardCharsets.ISO_8859_1)))
        val unlocked = SrukryptPassphraseEnvelope.read(file, password)
        assertArrayEquals(key, unlocked)
        key.fill(0)
        unlocked.fill(0)
        password.fill('\u0000')
    }

    @Test
    fun replacingExistingPassphraseEnvelopeKeepsItReadable() {
        val file = File(temporaryFolder.root, "passphrase.wrap")
        val originalKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val replacementKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        SrukryptPassphraseEnvelope.write(file, originalKey, "first long passphrase".toCharArray())
        SrukryptPassphraseEnvelope.write(file, replacementKey, "second long passphrase".toCharArray())

        val unlocked = SrukryptPassphraseEnvelope.read(file, "second long passphrase".toCharArray())
        assertArrayEquals(replacementKey, unlocked)
        originalKey.fill(0)
        replacementKey.fill(0)
        unlocked.fill(0)
    }

    @Test
    fun wrongPassphraseFailsWithoutChangingEnvelope() {
        val file = File(temporaryFolder.root, "passphrase.wrap")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        SrukryptPassphraseEnvelope.write(file, key, "right passphrase".toCharArray())
        val before = Files.readAllBytes(file.toPath())

        try {
            SrukryptPassphraseEnvelope.read(file, "wrong passphrase".toCharArray())
            fail("Wrong passphrase must not unlock the vault.")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("incorrect"))
        }
        assertArrayEquals(before, Files.readAllBytes(file.toPath()))
        key.fill(0)
    }
}
