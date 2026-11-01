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
    fun rollbackFallbackReplacesExistingEnvelope() {
        val directory = temporaryFolder.root
        val temp = File(directory, ".new.tmp").apply { writeText("new envelope") }
        val target = File(directory, "passphrase.wrap").apply { writeText("old envelope") }

        SrukryptPassphraseEnvelope.replaceWithRollback(temp, target)

        assertTrue(target.isFile)
        assertEquals("new envelope", target.readText())
        assertFalse(temp.exists())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".bak") })
    }

    @Test
    fun rollbackFallbackRestoresOriginalWhenCommitRenameFails() {
        val directory = temporaryFolder.root
        val temp = File(directory, ".missing.tmp")
        val target = File(directory, "passphrase.wrap").apply { writeText("working envelope") }

        try {
            SrukryptPassphraseEnvelope.replaceWithRollback(temp, target)
            fail("Replacing with a missing temporary file must fail.")
        } catch (expected: java.io.IOException) {
            // Expected. The original envelope must be restored from the backup.
        }

        assertTrue(target.isFile)
        assertEquals("working envelope", target.readText())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".bak") })
    }

    @Test
    fun invalidReplacementLeavesPreviousEnvelopeUsable() {
        val file = File(temporaryFolder.root, "passphrase.wrap")
        val originalKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val originalPassphrase = "original safe passphrase".toCharArray()
        SrukryptPassphraseEnvelope.write(file, originalKey, originalPassphrase)
        val before = Files.readAllBytes(file.toPath())

        try {
            SrukryptPassphraseEnvelope.write(file, originalKey, "short".toCharArray())
            fail("A short passphrase must be rejected.")
        } catch (expected: IllegalArgumentException) {
            // Rejected before any temporary file can replace the working envelope.
        }

        assertArrayEquals(before, Files.readAllBytes(file.toPath()))
        val recovered = SrukryptPassphraseEnvelope.read(file, originalPassphrase)
        assertArrayEquals(originalKey, recovered)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        originalKey.fill(0)
        recovered.fill(0)
        originalPassphrase.fill('\u0000')
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
