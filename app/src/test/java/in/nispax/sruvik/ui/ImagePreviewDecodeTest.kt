package `in`.nispax.sruvik.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePreviewDecodeTest {
    @Test
    fun previewKeepsSmallImagesAtNativeSize() {
        assertEquals(1, previewSampleSize(1280, 720))
    }

    @Test
    fun previewBoundsLargeImagePixelAndDimensionBudget() {
        val width = 8000
        val height = 6000
        val sample = previewSampleSize(width, height)

        assertTrue(sample >= 2)
        assertTrue((width.toLong() / sample) * (height.toLong() / sample) <= 4_000_000L)
        assertTrue(maxOf(width / sample, height / sample) <= 4096)
    }

    @Test
    fun previewSizingHandlesVeryLargeProviderDimensionsWithoutOverflow() {
        val sample = previewSampleSize(100_000, 100_000)

        assertTrue(sample > 1)
        assertTrue((100_000L / sample) * (100_000L / sample) <= 4_000_000L)
        assertTrue(100_000 / sample <= 4096)
    }
}
