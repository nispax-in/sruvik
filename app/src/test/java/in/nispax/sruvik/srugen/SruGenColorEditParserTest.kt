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
    @Test
    fun routesHairColourToHairMaskAndNotClothingMask() {
        assertEquals("blue", SruGenColorEditParser.targetColor("make my hair blue"))
        assertEquals("hair", SruGenColorEditParser.targetRegion("make my hair blue"))
        assertNull(SruGenColorEditParser.targetRegion("make my hair and shirt blue"))
    }

    @Test
    fun hairStyleChangesAreNotMisroutedToColourTransform() {
        assertNull(SruGenColorEditParser.targetColor("give me curly hair"))
        assertNull(SruGenColorEditParser.targetColor("make my hairstyle shorter"))
    }

    @Test
    fun detectsHairPlusClothingAsSeparateEditRegions() {
        assertEquals(true, SruGenColorEditParser.requiresMultiRegionEdit("make my hair blue and my shirt black"))
        assertEquals(true, SruGenColorEditParser.requiresMultiRegionEdit("white blazer over a black turtleneck"))
        assertEquals(false, SruGenColorEditParser.requiresMultiRegionEdit("make my hair blue"))
        assertEquals(false, SruGenColorEditParser.requiresMultiRegionEdit("make my shirt black"))
    }

    @Test
    fun identifiesClothingForRegionPreservingStructuralEdits() {
        assertEquals(true, SruGenColorEditParser.hasClothingTarget("change my blazer to a darker style"))
        assertEquals(true, SruGenColorEditParser.hasClothingTarget("replace my turtleneck with a shirt"))
        assertEquals(false, SruGenColorEditParser.hasClothingTarget("change the background to a beach"))
        assertEquals(false, SruGenColorEditParser.hasClothingTarget("give me curly hair"))
    }

    @Test
    fun blazerAndTurtleneckAreRecognizedAsTwoDistinctGarments() {
        assertEquals(
            setOf("outerwear", "inner-top"),
            SruGenColorEditParser.garmentTargets("black blazer over a white turtleneck")
        )
        assertEquals(
            true,
            SruGenColorEditParser.requiresMultiRegionEdit("black blazer over a white turtleneck")
        )
    }

}
