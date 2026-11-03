package `in`.nispax.sruvik.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SruGenClothingEditIntentTest {
    @Test
    fun attachedPhotoClothingInstructionIsRecognized() {
        assertTrue(isSruGenClothingEditInstruction("change my clothes color to red"))
        assertTrue(isSruGenClothingEditInstruction("make my shirt black"))
        assertTrue(isSruGenClothingEditInstruction("recolour the jacket blue"))
    }

    @Test
    fun attachedPhotoHairColourInstructionIsRecognized() {
        assertTrue(isSruGenClothingEditInstruction("make my hair blue"))
        assertFalse(isSruGenClothingEditInstruction("change my hair colour to auburn"))
    }

    @Test
    fun unsupportedStructuralHairEditIsNotRoutedToClothingEngine() {
        assertFalse(isSruGenClothingEditInstruction("give me curly hair"))
        assertFalse(isSruGenClothingEditInstruction("make my hairstyle shorter"))
    }

    @Test
    fun ordinaryCreationPromptIsNotTreatedAsClothingEdit() {
        assertFalse(isSruGenClothingEditInstruction("create a red sports car"))
        assertFalse(isSruGenClothingEditInstruction("make a cinematic sunset"))
    }

    @Test
    fun nonClothingEditDoesNotTriggerClothingWorkflow() {
        assertFalse(isSruGenClothingEditInstruction("change the background to a beach"))
        assertFalse(isSruGenClothingEditInstruction("remove the person"))
    }
}
