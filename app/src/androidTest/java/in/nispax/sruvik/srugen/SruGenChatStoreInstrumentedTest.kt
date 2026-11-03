package `in`.nispax.sruvik.srugen

import java.io.ByteArrayOutputStream

import android.os.Build

import android.graphics.Bitmap

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SruGenChatStoreInstrumentedTest {
    @Test
    fun saveToDevicePublishesImageInPicturesSruvik() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        val bytes = output.toByteArray()
        var savedUri: android.net.Uri? = null
        try {
            savedUri = SruGenImageGallery.saveToPictures(context, bytes, "image/png")
            val resolver = context.contentResolver
            resolver.query(
                savedUri!!,
                arrayOf(
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH
                ),
                null,
                null,
                null
            )?.use { cursor ->
                assertTrue("Gallery row should be queryable", cursor.moveToFirst())
                val name = cursor.getString(
                    cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                )
                assertTrue(name.startsWith("Sruvik-"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativePath = cursor.getString(
                        cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
                    )
                    assertTrue(relativePath.startsWith("Pictures/Sruvik"))
                }
            } ?: throw AssertionError("Android did not return the saved Gallery item.")

            val storedBytes = resolver.openInputStream(savedUri!!)?.use { it.readBytes() }
                ?: throw AssertionError("The saved Gallery image could not be opened.")
            try {
                assertArrayEquals(bytes, storedBytes)
            } finally {
                storedBytes.fill(0)
            }
        } finally {
            savedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            bytes.fill(0)
        }
    }

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
