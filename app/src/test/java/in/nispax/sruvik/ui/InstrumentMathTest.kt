package `in`.nispax.sruvik.ui

import org.junit.Assert.assertEquals
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

    @Test fun needleUsesSameScaleAsReadout() {
        assertEquals(180f, InstrumentMath.angle(0.0, 0.0, 100.0), 0.0001f)
        assertEquals(270f, InstrumentMath.angle(50.0, 0.0, 100.0), 0.0001f)
        assertEquals(360f, InstrumentMath.angle(100.0, 0.0, 100.0), 0.0001f)
    }
}
