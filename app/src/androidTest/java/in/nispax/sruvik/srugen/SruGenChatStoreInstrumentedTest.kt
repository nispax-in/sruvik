package `in`.nispax.sruvik.srugen

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SruGenChatStoreInstrumentedTest {
    @Test
    fun generatedImageAttachmentSurvivesEncryptedChatReload() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val directory = File(context.noBackupFilesDir, "srugen")
        directory.deleteRecursively()

        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val chatId = "generation-persistence-test"
        try {
            val userPrompt = SruGenMessage(role = "user", text = "A mountain lake at dusk")
            SruGenChatStore.upsertChat(
                context,
                SruGenChat(
                    id = chatId,
                    title = "Persistence test",
                    messages = listOf(userPrompt)
                )
            )

            val attachmentId = SruGenChatStore.saveAttachment(context, imageBytes, "image/png")
            SruGenChatStore.appendMessage(
                context,
                chatId,
                SruGenMessage(
                    role = "assistant",
                    text = "Generated locally.",
                    attachmentId = attachmentId,
                    attachmentMime = "image/png",
                    modelName = "Test model"
                )
            )

            // Simulate leaving the SruGen destination and constructing it again:
            // only the encrypted store is used to restore the conversation.
            val restored = SruGenChatStore.loadChats(context).single()
            assertEquals(chatId, restored.id)
            assertTrue(restored.messages.any { it.role == "user" && it.text == userPrompt.text })
            val result = restored.messages.single { it.role == "assistant" }
            assertEquals(attachmentId, result.attachmentId)
            assertNotNull(result.modelName)
            val reopenedBytes = SruGenChatStore.readAttachment(context, attachmentId)
            assertArrayEquals(imageBytes, reopenedBytes)
            reopenedBytes.fill(0)
        } finally {
            directory.deleteRecursively()
        }
    }
}
