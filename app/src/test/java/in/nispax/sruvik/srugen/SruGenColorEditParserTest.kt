package `in`.nispax.sruvik.srugen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SruGenColorEditParserTest {
    @Test
    fun plainClothingColorRequestUsesPrecisionRecolourPath() {
        assertEquals(
            "red",
            SruGenColorEditParser.targetColor("change my clothes color to red")
        )
        assertEquals(
            "black",
            SruGenColorEditParser.targetColor("Make this shirt black")
        )
        assertEquals(
            "blue",
            SruGenColorEditParser.targetColor("recolour my jacket to blue")
        )
    }

    @Test
    fun structuralOrPatternChangesRemainOnGenerativeEditPath() {
        assertNull(
            SruGenColorEditParser.targetColor("change my shirt to a red leather jacket")
        )
        assertNull(
            SruGenColorEditParser.targetColor("make my shirt red with white stripes")
        )
        assertNull(
            SruGenColorEditParser.targetColor("change the background to red")
        )
    }

    @Test
    fun multipleRequestedColorsAreNotTreatedAsASimpleRecolour() {
        assertNull(
            SruGenColorEditParser.targetColor("change my shirt to red and blue")
        )
    }
}
