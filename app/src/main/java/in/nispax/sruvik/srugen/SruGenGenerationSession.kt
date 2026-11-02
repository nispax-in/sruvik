package `in`.nispax.sruvik.srugen

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class SruGenGenerationStatus { IDLE, RUNNING, COMPLETED, CANCELLED, FAILED }

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
    @Volatile private var cancelRequested = false

    @Synchronized
    fun start(
        context: Context,
        chatWithPrompt: SruGenChat,
        generationPrompt: String,
        modelId: String,
        mode: SruGenMode = SruGenMode.TEXT_TO_IMAGE,
        sourceAttachmentId: String? = null
    ): Boolean {
        if (mutableState.value.status == SruGenGenerationStatus.RUNNING) return false
        cancelRequested = false
        val appContext = context.applicationContext
        mutableState.value = SruGenGenerationState(
            status = SruGenGenerationStatus.RUNNING,
            chatId = chatWithPrompt.id,
            progress = 0,
            progressLabel = "Saving your prompt"
        )

        scope.launch {
            var resultBytes: ByteArray? = null
            var sourceImageBytes: ByteArray? = null
            try {
                withContext(Dispatchers.IO) {
                    SruGenChatStore.upsertChat(appContext, chatWithPrompt)
                }
                mutableState.update {
                    it.copy(progressLabel = "Loading the on-device model", updatedAt = System.currentTimeMillis())
                }
                if (mode != SruGenMode.TEXT_TO_IMAGE) {
                    val attachmentId = sourceAttachmentId
                        ?: throw IllegalArgumentException("Attach an image before starting this workflow.")
                    sourceImageBytes = withContext(Dispatchers.IO) {
                        SruGenChatStore.readAttachment(appContext, attachmentId)
                    }
                }
                val progressCallback: (Int, String) -> Unit = { value, label ->
                    if (cancelRequested) SruGenEngineRegistry.engine?.requestStop()
                    mutableState.update {
                        it.copy(
                            progress = value.coerceIn(0, 100),
                            progressLabel = label,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                }
                val result = if (mode == SruGenMode.REMOVE_BACKGROUND) {
                    withContext(Dispatchers.IO) {
                        if (cancelRequested) throw CancellationException("Generation stopped by you.")
                        progressCallback(12, "Detecting subject and background locally")
                        val bytes = SruGenAutoMasker.removeBackground(
                            appContext,
                            requireNotNull(sourceImageBytes)
                        )
                        progressCallback(94, "Finishing transparent PNG")
                        SruGenResult(
                            imageBytes = bytes,
                            mimeType = "image/png",
                            modelName = "MediaPipe SelfieMulticlass · Background removal"
                        )
                    }
                } else {
                    withContext(Dispatchers.Default) {
                        val engine = SruGenEngineRegistry.getOrCreate {
                            SruGenEngineFactory.create(appContext, modelId)
                        }
                        if (cancelRequested) {
                            engine.requestStop()
                            throw CancellationException("Generation stopped by you.")
                        }
                        engine.generate(
                            SruGenRequest(
                                mode = mode,
                                prompt = generationPrompt,
                                style = "Cinematic",
                                aspectRatio = "1:1",
                                sourceImage = sourceImageBytes
                            ),
                            progressCallback
                        )
                    }
                }
                if (cancelRequested) throw CancellationException("Generation stopped by you.")
                resultBytes = result.imageBytes
                val attachmentId = withContext(Dispatchers.IO) {
                    val id = SruGenChatStore.saveAttachment(appContext, result.imageBytes, result.mimeType)
                    try {
                        if (cancelRequested) {
                            throw CancellationException("Generation stopped before the image was committed.")
                        }
                        SruGenChatStore.appendMessage(
                            appContext,
                            chatWithPrompt.id,
                            SruGenMessage(
                                role = "assistant",
                                text = "Generated locally on this device. Saved in this encrypted SruGen conversation only—not in Srukrypt or the device gallery until you choose a save action.",
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
                    message = "Your image is saved in the encrypted SruGen conversation only. Use the image card to save a copy to your device or import it into Srukrypt.",
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
                if (cancelRequested || failure is CancellationException) {
                    android.util.Log.i("SruGen", "Local image generation was stopped by the user.")
                    recordCancelled(appContext, chatWithPrompt.id)
                } else {
                    android.util.Log.e("SruGen", "Local image generation failed.", failure)
                    recordFailure(
                        appContext,
                        chatWithPrompt.id,
                        failure.message ?: "Local image generation failed. Your prompt remains saved in encrypted chat history."
                    )
                }
            } finally {
                resultBytes?.fill(0)
                sourceImageBytes?.fill(0)
            }
        }
        return true
    }

    /**
     * Requests cooperative cancellation. Native inference stops at the next
     * engine checkpoint; we do not interrupt JNI from an arbitrary thread.
     */
    @Synchronized
    fun cancel(): Boolean {
        if (mutableState.value.status != SruGenGenerationStatus.RUNNING) return false
        cancelRequested = true
        mutableState.update {
            it.copy(
                progressLabel = "Stopping at the next safe checkpoint…",
                updatedAt = System.currentTimeMillis()
            )
        }
        SruGenEngineRegistry.engine?.requestStop()
        return true
    }

    private suspend fun recordCancelled(context: Context, chatId: String) {
        val message = "Generation stopped by you. Your prompt remains saved in the encrypted conversation; no completed image was added."
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
            status = SruGenGenerationStatus.CANCELLED,
            chatId = chatId,
            progressLabel = "Stopped",
            message = if (stored) message else "$message (The stop message could not be saved.)",
            updatedAt = System.currentTimeMillis()
        )
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
