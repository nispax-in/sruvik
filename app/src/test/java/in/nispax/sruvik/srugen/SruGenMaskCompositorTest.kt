package `in`.nispax.sruvik.srugen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SruGenMaskCompositorTest {
    @Test
    fun zeroAlphaPixelsRemainBitExact() {
        val original = intArrayOf(
            0xff102030.toInt(), 0xff405060.toInt(),
            0xff708090.toInt(), 0xffa0b0c0.toInt()
        )
        val generated = IntArray(4) { 0xffffffff.toInt() }
        val mask = byteArrayOf(0, 0xff.toByte(), 0, 0)
        val result = SruGenMaskCompositor.composite(
            original, 2, 2, generated, 2, 2, 0, 0, mask, 2, 2
        )
        assertEquals(original[0], result[0])
        assertNotEquals(original[1], result[1])
        assertEquals(original[2], result[2])
        assertEquals(original[3], result[3])
    }

    @Test
    fun fullySelectedMaskUsesGeneratedPixels() {
        val original = IntArray(4) { 0xff000000.toInt() }
        val generated = IntArray(4) { 0xffabcdef.toInt() }
        val result = SruGenMaskCompositor.composite(
            original, 2, 2, generated, 2, 2, 0, 0,
            ByteArray(4) { 0xff.toByte() }, 2, 2
        )
        assertEquals(generated.toList(), result.toList())
    }

    @Test
    fun cropCompositeLeavesAllPixelsOutsideDestinationUnchanged() {
        val original = IntArray(16) { 0xff112233.toInt() }
        val generated = IntArray(4) { 0xffabcdef.toInt() }
        val mask = ByteArray(16) { 0xff.toByte() }
        val result = SruGenMaskCompositor.composite(
            original, 4, 4, generated, 2, 2, 1, 1, mask, 4, 4
        )
        for (y in 0 until 4) for (x in 0 until 4) {
            val index = y * 4 + x
            if (x in 1..2 && y in 1..2) assertEquals(generated[(y - 1) * 2 + (x - 1)], result[index])
            else assertEquals(original[index], result[index])
        }
    }
}
