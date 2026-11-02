package `in`.nispax.sruvik.srugen

import android.content.Context
import android.graphics.Bitmap
import com.nezumi_ai.sd.MnnSdNative
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sruvik adapter for Nezumi Kiln's LGPL MNN SD1.5 JNI runtime.
 *
 * This adapter deliberately uses CPU, 512x512, and <=20 steps. The GPU path is
 * not enabled until it has been tested across device-specific OpenCL drivers.
 * The same conservative SruGenDeviceGuard is required before native allocation
 * and is rechecked on every native progress callback.
 */
class NezumiMnnSruGenEngine(
    private val context: Context,
    private val modelDirectory: File,
    private val modelDisplayName: String = "Nezumi MNN · SD1.5"
) : SruGenEngine, AutoCloseable {
    override val displayName: String = "$modelDisplayName · Nezumi Kiln (MNN)"
    override val isOffline: Boolean = true

    private val nativeLock = ReentrantLock()
    @Volatile private var handle: Long = 0L
    @Volatile private var loaded = false
    @Volatile private var closed = false
    @Volatile private var stopRequested = false
    @Volatile private var stopReason: String? = null

    override val isClosed: Boolean get() = closed

    override fun requestStop() {
        stopRequested = true
        stopReason = "Android requested that image generation stop to protect device resources."
        val current = handle
        if (current != 0L) {
            // The native engine accepts cancellation even before load completes.
            runCatching { MnnSdNative.cancel(current) }
        }
    }

    private fun validateModelPack() {
        val required = listOf(
            "model.json",
            "tokenizer.json",
            "clip_v2.mnn",
            "clip_v2.mnn.weight",
            "unet.mnn",
            "unet.mnn.weight",
            "vae_decoder_fp16.mnn",
            "vae_decoder_fp16.mnn.weight",
            "token_emb.bin",
            "pos_emb.bin"
        )
        val missing = required.filter { name ->
            val file = File(modelDirectory, name)
            !file.isFile || file.length() <= 0L
        }
        require(missing.isEmpty()) {
            "The selected MNN model pack is incomplete. Missing or empty files: ${missing.joinToString()}"
        }
    }

    private fun ensureLoaded() {
        check(!closed) { "The MNN engine has been released. Reopen SruGen to retry." }
        if (loaded && handle != 0L && MnnSdNative.isLoaded(handle)) return

        SruGenDeviceGuard.requireSafeToStart(context)
        require(BuildConfigForSruGen.apiSupportsMnn(context)) {
            "Nezumi Kiln requires Android 12 or newer."
        }
        require(MnnSdNative.isAvailable()) {
            "The Nezumi Kiln native runtime is unavailable on this device. SruGen can continue using the supported MediaPipe model."
        }
        validateModelPack()

        val created = MnnSdNative.create()
        check(created != 0L) { "Nezumi Kiln could not allocate its native engine." }
        handle = created
        try {
            val success = MnnSdNative.load(
                created,
                modelDirectory.absolutePath,
                MnnSdNative.BACKEND_CPU,
                448
            )
            check(success) {
                "$displayName could not be loaded by Nezumi Kiln: ${MnnSdNative.getLastError()}"
            }
            check(!stopRequested) {
                "DreamShaper loading was stopped because Android requested memory release."
            }
            SruGenDeviceGuard.assertSafeToContinue(context)
            loaded = true
        } catch (failure: Throwable) {
            runCatching { MnnSdNative.unload(created) }
            runCatching { MnnSdNative.destroy(created) }
            handle = 0L
            loaded = false
            throw failure
        }
    }

    override suspend fun generate(
        request: SruGenRequest,
        onProgress: (Int, String) -> Unit
    ): SruGenResult = withContext(Dispatchers.IO) {
        require(request.mode == SruGenMode.TEXT_TO_IMAGE || request.mode == SruGenMode.PROMPT_EDIT) {
            "$displayName does not support this image workflow."
        }
        require(request.prompt.isNotBlank()) { "Describe the image you want to create or how you want to edit it." }
        val editing = request.mode == SruGenMode.PROMPT_EDIT
        require(!editing || request.sourceImage?.isNotEmpty() == true) {
            "Attach an image before starting a prompt edit."
        }

        nativeLock.withLock {
            check(!closed) { "The MNN engine has been released." }
            stopRequested = false
            stopReason = null
            var sourceBitmap: Bitmap? = null
            var resizedSource: Bitmap? = null
            var sourceRgb: ByteArray? = null
            var packed: ByteArray? = null
            var generatedBitmap: Bitmap? = null
            var finalBitmap: Bitmap? = null
            try {
                SruGenDeviceGuard.requireSafeToStart(context)
                onProgress(2, "Checking $displayName model pack")
                ensureLoaded()
                check(!stopRequested) {
                    "DreamShaper loading was cancelled to protect device resources. Your encrypted prompt remains saved."
                }
                SruGenDeviceGuard.assertSafeToContinue(context)
                val currentHandle = handle
                check(currentHandle != 0L && loaded) { "Nezumi Kiln did not finish loading the model." }

                var generationWidth = 512
                var generationHeight = 512
                if (editing) {
                    onProgress(4, "Preparing your private reference image")
                    sourceBitmap = SruGenAutoMasker.decodeBounded(requireNotNull(request.sourceImage))
                    resizedSource = SruGenAutoMasker.resizeForDiffusion(requireNotNull(sourceBitmap))
                    generationWidth = resizedSource!!.width
                    generationHeight = resizedSource!!.height
                    sourceRgb = SruGenAutoMasker.toPackedRgb(resizedSource!!)

                    val capabilities = JSONObject(MnnSdNative.getCapabilities(currentHandle) ?: "{}")
                    require(capabilities.optInt("supports_img2img", 0) == 1) {
                        "This installed model does not include a compatible VAE encoder. Install a verified SD1.5 model pack with vae_encoder_fp16.mnn to enable image editing."
                    }
                }

                val progress = object : MnnSdNative.NativeProgressListener {
                    override fun onNativeProgress(step: Int, totalSteps: Int, elapsedSec: Float) {
                        if (stopRequested) {
                            runCatching { MnnSdNative.cancel(currentHandle) }
                            return
                        }
                        val assessment = runCatching {
                            SruGenDeviceGuard.assertSafeToContinue(context)
                        }
                        if (assessment.isFailure) {
                            stopReason = assessment.exceptionOrNull()?.message
                                ?: "Device resource safety threshold reached."
                            stopRequested = true
                            runCatching { MnnSdNative.cancel(currentHandle) }
                            return
                        }
                        val percent = if (totalSteps > 0) {
                            8 + (step.coerceIn(0, totalSteps) * 82 / totalSteps)
                        } else 8
                        runCatching {
                            onProgress(
                                percent,
                                if (editing) "Editing image locally · $step/$totalSteps"
                                else "Generating locally · $step/$totalSteps"
                            )
                        }
                    }
                }

                onProgress(5, if (editing) "Preparing image-to-image inference · CPU-safe mode" else "Loading $displayName · CPU-safe mode")
                val diffusionPrompt = if (editing) {
                    "Faithful photorealistic edit of the supplied original photo. Keep the same person, identity, face, hair, expression, pose, camera framing, background and lighting. Change only the visible clothing as requested: ${request.prompt}. Preserve every non-clothing detail."
                } else {
                    request.prompt
                }
                packed = MnnSdNative.generate(
                    handle = currentHandle,
                    prompt = diffusionPrompt,
                    negativePrompt = "low quality, blurry, deformed, distorted, different person, changed identity, changed face, changed hair, changed expression, changed pose, changed camera, changed background, watermark",
                    width = generationWidth,
                    height = generationHeight,
                    steps = 20,
                    cfg = 7.0f,
                    seed = SecureRandom().nextLong().and(Long.MAX_VALUE),
                    scheduler = 4, // DPM++ 2M Karras in the pinned Nezumi JNI enum.
                    initImageRgb = sourceRgb,
                    initImageWidth = if (editing) generationWidth else 0,
                    initImageHeight = if (editing) generationHeight else 0,
                    denoiseStrength = if (editing) 0.76f else 0.0f,
                    progressListener = progress
                )
                stopReason?.let { throw IllegalStateException(it) }
                check(!stopRequested) {
                    "Generation was cancelled to protect device resources. Your encrypted prompt remains saved."
                }
                check(packed != null) {
                    "DreamShaper image operation failed: ${MnnSdNative.getLastError()}"
                }
                SruGenDeviceGuard.assertSafeToContinue(context)
                onProgress(92, if (editing) "Applying the automatic clothing mask" else "Decoding generated image")
                generatedBitmap = SruGenAutoMasker.decodePackedRgb(requireNotNull(packed))
                finalBitmap = if (editing) {
                    SruGenAutoMasker.editClothing(
                        context,
                        requireNotNull(sourceBitmap),
                        requireNotNull(generatedBitmap)
                    )
                } else {
                    generatedBitmap
                }

                val png = SruGenAutoMasker.encodePng(requireNotNull(finalBitmap))
                onProgress(100, if (editing) "Done · clothing edited locally" else "Done · generated locally")
                SruGenResult(
                    imageBytes = png,
                    mimeType = "image/png",
                    modelName = if (editing) "$displayName · automatic clothing edit" else displayName
                )
            } finally {
                sourceBitmap?.recycle()
                resizedSource?.recycle()
                generatedBitmap?.takeIf { it !== finalBitmap }?.recycle()
                finalBitmap?.recycle()
                sourceRgb?.fill(0)
                packed?.fill(0)
            }
        }
    }

    override fun close() {
        nativeLock.withLock {
            if (closed) return
            closed = true
            val current = handle
            handle = 0L
            loaded = false
            if (current != 0L) {
                runCatching { MnnSdNative.cancel(current) }
                runCatching { MnnSdNative.unload(current) }
                runCatching { MnnSdNative.destroy(current) }
            }
        }
    }
}

/** Kept separate so device-version checks can be unit tested without JNI. */
private object BuildConfigForSruGen {
    fun apiSupportsMnn(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
}
