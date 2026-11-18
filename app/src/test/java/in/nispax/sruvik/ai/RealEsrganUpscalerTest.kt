package `in`.nispax.sruvik.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RealEsrganUpscalerTest {
    @Test
    fun twoByTwoBoxFilterAveragesRgbChannels() {
        val source = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()
        )
        val destination = IntArray(1)

        RealEsrganUpscaler.downsample2xBoxInto(source, 2, destination)

        assertArrayEquals(intArrayOf(0xFF808080.toInt()), destination)
    }

    @Test
    fun twoByTwoBoxFilterUsesCorrectRowsAndCanReuseDestination() {
        val source = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF0000.toInt(),
            0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
            0xFFFF0000.toInt(), 0xFFFF0000.toInt(),
            0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFF0000FF.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
            0xFF0000FF.toInt(), 0xFF0000FF.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()
        )
        val destination = IntArray(4)

        RealEsrganUpscaler.downsample2xBoxInto(source, 4, destination)

        assertEquals(0xFFFF0000.toInt(), destination[0])
        assertEquals(0xFF00FF00.toInt(), destination[1])
        assertEquals(0xFF0000FF.toInt(), destination[2])
        assertEquals(0xFFFFFFFF.toInt(), destination[3])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOddSourceWidth() {
        RealEsrganUpscaler.downsample2xBoxInto(
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
            1,
            IntArray(1)
        )
    }
}
