package `in`.nispax.sruvik.srugen

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class SruGenGenerationStatus { IDLE, RUNNING, COMPLETED, FAILED }

data class SruGenGenerationState(
    val status: SruGenGenerationStatus = SruGenGenerationStatus.IDLE,
    val chatId: String? = null,
    val progress: Int = 0,
    val progressLabel: String = "",
    val message: String = "",
    val updatedAt: Long = 0L
)

/**
 * App-process-owned generation runner.
 *
 * A Compose destination is transient: navigating to another tab disposes its
 * rememberCoroutineScope. Image generation is not UI work, so the job and its
 * observable state live here instead. The encrypted user prompt is committed
 * before native model allocation and the result is committed before COMPLETED.
 *
 * This protects against tab navigation and recomposition. It cannot promise
 * survival if Android kills the entire app process or the user force-stops Sruvik.
 */
object SruGenGenerationSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(SruGenGenerationState())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun start(
        context: Context,
        chatWithPrompt: SruGenChat,
        generationPrompt: String,
        modelId: String
    ): Boolean {
        if (mutableState.value.status == SruGenGenerationStatus.RUNNING) return false
        val appContext = context.applicationContext
        mutableState.value = SruGenGenerationState(
            status = SruGenGenerationStatus.RUNNING,
            chatId = chatWithPrompt.id,
            progress = 0,
            progressLabel = "Saving your prompt"
        )

        scope.launch {
            var resultBytes: ByteArray? = null
            try {
                withContext(Dispatchers.IO) {
                    SruGenChatStore.upsertChat(appContext, chatWithPrompt)
                }
                mutableState.update {
                    it.copy(progressLabel = "Loading the on-device model", updatedAt = System.currentTimeMillis())
                }
                val result = withContext(Dispatchers.Default) {
                    val engine = SruGenEngineRegistry.getOrCreate {
                        SruGenEngineFactory.create(appContext, modelId)
                    }
                    engine.generate(
                        SruGenRequest(
                            mode = SruGenMode.TEXT_TO_IMAGE,
                            prompt = generationPrompt,
                            style = "Cinematic",
                            aspectRatio = "1:1",
                            sourceImage = null
                        )
                    ) { value, label ->
                        mutableState.update {
                            it.copy(
                                progress = value.coerceIn(0, 100),
                                progressLabel = label,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    }
                }
                resultBytes = result.imageBytes
                val attachmentId = withContext(Dispatchers.IO) {
                    val id = SruGenChatStore.saveAttachment(appContext, result.imageBytes, result.mimeType)
                    try {
                        SruGenChatStore.appendMessage(
                            appContext,
                            chatWithPrompt.id,
                            SruGenMessage(
                                role = "assistant",
                                text = "Generated locally on this device. No prompt or image was uploaded.",
                                attachmentId = id,
                                attachmentMime = result.mimeType,
                                modelName = result.modelName
                            )
                        )
                    } catch (failure: Exception) {
                        // Avoid leaving an unreferenced encrypted image if chat commit fails.
                        SruGenChatStore.deleteAttachment(appContext, id)
                        throw failure
                    }
                    id
                }
                mutableState.value = SruGenGenerationState(
                    status = SruGenGenerationStatus.COMPLETED,
                    chatId = chatWithPrompt.id,
                    progress = 100,
                    progressLabel = "Complete",
                    message = "Your image is saved in this encrypted conversation.",
                    updatedAt = System.currentTimeMillis()
                )
                android.util.Log.i("SruGen", "Generation completed and encrypted attachment $attachmentId was committed.")
            } catch (failure: LinkageError) {
                android.util.Log.e("SruGen", "The local image engine could not load a required native runtime.", failure)
                releaseFailedEngine()
                recordFailure(
                    appContext,
                    chatWithPrompt.id,
                    "SruGen's image engine could not load a compatible Android runtime library. The failure was recorded locally; no prompt or image was uploaded."
                )
            } catch (failure: OutOfMemoryError) {
                android.util.Log.e("SruGen", "The phone ran out of memory during local image generation.", failure)
                releaseFailedEngine()
                System.gc()
                recordFailure(
                    appContext,
                    chatWithPrompt.id,
                    "Android ran out of memory during image generation. Sruvik stopped the failed engine. Close other apps before retrying; this is a safety limit, not a guarantee against system-level process termination."
                )
            } catch (failure: Exception) {
                android.util.Log.e("SruGen", "Local image generation failed.", failure)
                recordFailure(
                    appContext,
                    chatWithPrompt.id,
                    failure.message ?: "Local image generation failed. Your prompt remains saved in encrypted chat history."
                )
            } finally {
                resultBytes?.fill(0)
            }
        }
        return true
    }

    private suspend fun recordFailure(context: Context, chatId: String, message: String) {
        val stored = runCatching {
            withContext(Dispatchers.IO) {
                SruGenChatStore.appendMessage(
                    context,
                    chatId,
                    SruGenMessage(role = "assistant", text = message)
                )
            }
        }.isSuccess
        mutableState.value = SruGenGenerationState(
            status = SruGenGenerationStatus.FAILED,
            chatId = chatId,
            progressLabel = "Could not complete",
            message = if (stored) message else "$message (The failure message could not be saved.)",
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun releaseFailedEngine() {
        val failed = SruGenEngineRegistry.engine ?: return
        failed.requestStop()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { SruGenEngineRegistry.closeAndDetach(failed) }
        }
    }
}
