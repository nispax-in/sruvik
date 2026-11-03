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
    fun detectsSeparateOuterAndInnerGarmentTargets() {
        assertEquals(
            setOf("outerwear", "inner-top"),
            SruGenColorEditParser.garmentTargets("change my clothes to a black blazer and white shirt inside")
        )
        assertEquals(
            setOf("outerwear", "lower-body"),
            SruGenColorEditParser.garmentTargets("make my jacket black and jeans white")
        )
        assertEquals(
            setOf("inner-top"),
            SruGenColorEditParser.garmentTargets("change my shirt to red")
        )
    }

    @Test
    fun layeredOutfitRequestsAreNotSentToGenericDiffusion() {
        assertEquals(
            true,
            SruGenColorEditParser.requiresMultiGarmentEdit("black blazer and white shirt inside")
        )
        assertEquals(
            false,
            SruGenColorEditParser.requiresMultiGarmentEdit("change my shirt to black")
        )
    }

    @Test
    fun multipleRequestedColorsAreNotTreatedAsASimpleRecolour() {
        assertNull(
            SruGenColorEditParser.targetColor("change my shirt to red and blue")
        )
    }
}
