package `in`.nispax.sruvik.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SruGenClothingEditIntentTest {
    @Test
    fun attachedPhotoClothingInstructionIsRecognized() {
        assertTrue(isSruGenImageEditInstruction("change my clothes color to red"))
        assertTrue(isSruGenImageEditInstruction("make my shirt black"))
        assertTrue(isSruGenImageEditInstruction("recolour the jacket blue"))
    }

    @Test
    fun hairstyleAndNamedFingerInstructionsAreRecognized() {
        assertTrue(isSruGenImageEditInstruction("change my hairstyle to a short bob"))
        assertTrue(isSruGenImageEditInstruction("make my index finger longer"))
        assertTrue(isSruGenImageEditInstruction("shorten my ring finger on my right hand"))
    }

    @Test
    fun ordinaryCreationPromptIsNotTreatedAsClothingEdit() {
        assertFalse(isSruGenImageEditInstruction("create a red sports car"))
        assertFalse(isSruGenImageEditInstruction("make a cinematic sunset"))
    }

    @Test
    fun nonClothingEditDoesNotTriggerClothingWorkflow() {
        assertFalse(isSruGenImageEditInstruction("change the background to a beach"))
        assertFalse(isSruGenImageEditInstruction("remove the person"))
    }
}
