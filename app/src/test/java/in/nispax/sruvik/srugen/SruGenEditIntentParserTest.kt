package `in`.nispax.sruvik.srugen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SruGenEditIntentParserTest {
    @Test
    fun hairInstructionsSelectHairWorkflow() {
        assertTrue(SruGenEditIntentParser.isHairEdit("change my hairstyle to a short bob"))
        assertTrue(SruGenEditIntentParser.isHairEdit("give me bangs"))
        assertFalse(SruGenEditIntentParser.isHairEdit("change the background to a beach"))
    }

    @Test
    fun fingerInstructionsSelectNamedFingerAndSide() {
        assertTrue(SruGenEditIntentParser.isFingerEdit("make my index finger longer"))
        assertEquals(SruGenEditIntentParser.Finger.INDEX,
            SruGenEditIntentParser.finger("make my index finger longer"))
        assertEquals(SruGenEditIntentParser.Finger.RING,
            SruGenEditIntentParser.finger("shorten the ring finger on my right hand"))
        assertEquals(SruGenEditIntentParser.HandSide.RIGHT,
            SruGenEditIntentParser.handSide("shorten my ring finger on my right hand"))
    }

    @Test
    fun ambiguousWholeHandOrMultipleFingerRequestsDoNotGuess() {
        assertTrue(SruGenEditIntentParser.isFingerEdit("make my fingers longer"))
        assertEquals(null, SruGenEditIntentParser.finger("make my fingers longer"))
        assertEquals(null, SruGenEditIntentParser.finger("change my index finger and ring finger"))
        assertFalse(SruGenEditIntentParser.isHairEdit("make my hand smaller"))
    }
}
