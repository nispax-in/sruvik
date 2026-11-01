package `in`.nispax.sruvik.security

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SrukryptVaultInstrumentedTest {
    @Test
    fun missingWrappedKeyWithExistingCiphertextDoesNotCreateReplacementKey() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val directory = SrukryptStorage.vaultDirectory(context)
        val alias = SrukryptStorage.keyAlias("master")
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapped = java.io.File(directory, "master.wrap")
        val passphrase = java.io.File(directory, "passphrase.wrap")
        val marker = java.io.File(directory, "00000000-0000-0000-0000-000000000001.srk")

        try {
            keyStore.deleteEntry(alias)
            wrapped.delete()
            passphrase.delete()
            marker.writeBytes(byteArrayOf(1, 2, 3, 4))

            try {
                SrukryptVault.prepareUnlock(context)
                throw AssertionError("A vault with entries and no key envelope must not create a new key.")
            } catch (expected: SecurityException) {
                assertTrue(expected.message.orEmpty().contains("left untouched"))
            }
            assertTrue("No replacement Keystore alias may be created", !keyStore.containsAlias(alias))
            assertTrue("Existing encrypted entry must be preserved", marker.isFile)
        } finally {
            marker.delete()
            wrapped.delete()
            passphrase.delete()
            keyStore.deleteEntry(alias)
        }
    }

    @Test
    fun passphraseVaultCanStoreReadAndRewrapTheSameImageKey() {
        // Use the instrumentation package's private storage, never the user's app data.
        val context = InstrumentationRegistry.getInstrumentation().context
        val directory = SrukryptStorage.vaultDirectory(context)
        directory.listFiles()?.forEach { it.deleteRecursively() }

        val firstPassphrase = "correct first passphrase".toCharArray()
        val replacementPassphrase = "correct replacement passphrase".toCharArray()
        val wrongPassphrase = "an incorrect passphrase".toCharArray()
        var firstKey: ByteArray? = null
        var replacementKey: ByteArray? = null
        var recoveredImage: ByteArray? = null
        var previewBitmap: Bitmap? = null
        var imageBytes: ByteArray? = null
        try {
            val key = SrukryptVault.createVaultWithPassphrase(context, firstPassphrase)
            firstKey = key
            assertTrue(SrukryptVault.hasVault(context))
            assertTrue(SrukryptVault.hasPassphraseFallback(context))

            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            val encodedImageBytes = output.toByteArray()
            imageBytes = encodedImageBytes

            val id = SrukryptVault.storeImage(
                context, key, "instrumentation image", "image/png", encodedImageBytes
            )
            val unlockedWithOriginal = SrukryptVault.unlockWithPassphrase(context, firstPassphrase)
            assertArrayEquals(key, unlockedWithOriginal)
            unlockedWithOriginal.fill(0)

            val previews = SrukryptVault.loadPreviews(context, key)
            assertEquals(1, previews.size)
            assertEquals("instrumentation image", previews.single().title)
            previewBitmap = previews.single().thumbnail

            val (title, bytes) = SrukryptVault.readImage(context, key, id)
            assertEquals("instrumentation image", title)
            recoveredImage = bytes
            assertArrayEquals(encodedImageBytes, bytes)

            // Replacing an existing envelope must preserve the exact same data key.
            SrukryptVault.enablePassphraseFallback(context, key, replacementPassphrase)
            val keyAfterReplacement = SrukryptVault.unlockWithPassphrase(context, replacementPassphrase)
            replacementKey = keyAfterReplacement
            assertArrayEquals(key, keyAfterReplacement)

            try {
                SrukryptVault.unlockWithPassphrase(context, wrongPassphrase)
                throw AssertionError("An incorrect passphrase must not unlock Srukrypt.")
            } catch (expected: SecurityException) {
                assertTrue(expected.message.orEmpty().contains("incorrect", ignoreCase = true))
            }
        } finally {
            firstKey?.fill(0)
            replacementKey?.fill(0)
            recoveredImage?.fill(0)
            imageBytes?.fill(0)
            firstPassphrase.fill('\u0000')
            replacementPassphrase.fill('\u0000')
            wrongPassphrase.fill('\u0000')
            previewBitmap?.takeUnless { it.isRecycled }?.recycle()
            directory.deleteRecursively()
        }
    }
}
