package `in`.nispax.sruvik.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstrumentMathTest {
    @Test fun mapsRangeEndpointsAndMidpoint() {
        assertEquals(0f, InstrumentMath.fraction(0.0, 0.0, 100.0), 0.0001f)
        assertEquals(0.5f, InstrumentMath.fraction(50.0, 0.0, 100.0), 0.0001f)
        assertEquals(1f, InstrumentMath.fraction(100.0, 0.0, 100.0), 0.0001f)
    }

    @Test fun clampsOnlyTheVisualFraction() {
        assertEquals(0f, InstrumentMath.fraction(-25.0, 0.0, 100.0), 0.0001f)
        assertEquals(1f, InstrumentMath.fraction(125.0, 0.0, 100.0), 0.0001f)
    }

    @Test fun rejectsInvalidRangesWithoutNan() {
        assertEquals(0f, InstrumentMath.fraction(50.0, 100.0, 100.0), 0.0001f)
        assertEquals(0f, InstrumentMath.fraction(Double.NaN, 0.0, 100.0), 0.0001f)
        assertEquals(180f, InstrumentMath.angle(50.0, 10.0, 10.0), 0.0001f)
    }

    @Test fun processCpuIsNormalisedAcrossAvailableCores() {
        assertEquals(50f, InstrumentMath.processCpuSharePercent(500L, 1000L, 1), 0.0001f)
        assertEquals(25f, InstrumentMath.processCpuSharePercent(500L, 1000L, 2), 0.0001f)
        assertEquals(100f, InstrumentMath.processCpuSharePercent(4000L, 1000L, 2), 0.0001f)
        assertEquals(0f, InstrumentMath.processCpuSharePercent(500L, 0L, 2), 0.0001f)
    }

    @Test fun appUidNetworkRatesRejectInvalidIntervalsAndCounterResets() {
        assertEquals(1024L, InstrumentMath.bytesPerSecond(2048L, 2000L) ?: -1L)
        assertNull(InstrumentMath.bytesPerSecond(-1L, 2000L))
        assertNull(InstrumentMath.bytesPerSecond(10L, 0L))
    }

    @Test fun estimatedDeviceMemoryIsClampedToKernelTotal() {
        assertEquals(600L, InstrumentMath.estimatedUsedBytes(1000L, 400L))
        assertEquals(0L, InstrumentMath.estimatedUsedBytes(1000L, 1200L))
        assertEquals(0L, InstrumentMath.estimatedUsedBytes(-10L, 5L))
    }

    @Test fun needleUsesSameScaleAsReadout() {
        assertEquals(180f, InstrumentMath.angle(0.0, 0.0, 100.0), 0.0001f)
        assertEquals(270f, InstrumentMath.angle(50.0, 0.0, 100.0), 0.0001f)
        assertEquals(360f, InstrumentMath.angle(100.0, 0.0, 100.0), 0.0001f)
    }
}
