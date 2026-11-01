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
            firstKey = SrukryptVault.createVaultWithPassphrase(context, firstPassphrase)
            assertTrue(SrukryptVault.hasVault(context))
            assertTrue(SrukryptVault.hasPassphraseFallback(context))

            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            imageBytes = output.toByteArray()

            val id = SrukryptVault.storeImage(
                context, firstKey, "instrumentation image", "image/png", imageBytes
            )
            val unlockedWithOriginal = SrukryptVault.unlockWithPassphrase(context, firstPassphrase)
            assertArrayEquals(firstKey, unlockedWithOriginal)
            unlockedWithOriginal.fill(0)

            val previews = SrukryptVault.loadPreviews(context, firstKey)
            assertEquals(1, previews.size)
            assertEquals("instrumentation image", previews.single().title)
            previewBitmap = previews.single().thumbnail

            val (title, bytes) = SrukryptVault.readImage(context, firstKey, id)
            assertEquals("instrumentation image", title)
            recoveredImage = bytes
            assertArrayEquals(imageBytes, recoveredImage)

            // Replacing an existing envelope must preserve the exact same data key.
            SrukryptVault.enablePassphraseFallback(context, firstKey, replacementPassphrase)
            replacementKey = SrukryptVault.unlockWithPassphrase(context, replacementPassphrase)
            assertArrayEquals(firstKey, replacementKey)

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
