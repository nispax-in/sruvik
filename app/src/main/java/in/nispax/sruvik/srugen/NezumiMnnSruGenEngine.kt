package `in`.nispax.sruvik.srugen

import android.content.Context
import android.graphics.Bitmap
import com.nezumi_ai.sd.MnnSdNative
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val modelDirectory: File
) : SruGenEngine, AutoCloseable {
    override val displayName: String = "DreamShaper 8 · Nezumi Kiln (MNN)"
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
                "DreamShaper could not be loaded by Nezumi Kiln: ${MnnSdNative.getLastError()}"
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
        require(request.mode == SruGenMode.TEXT_TO_IMAGE) {
            "DreamShaper 8 currently supports text-to-image only in Sruvik."
        }
        require(request.prompt.isNotBlank()) { "Describe the image you want to create." }
        nativeLock.withLock {
            check(!closed) { "The MNN engine has been released." }
            stopRequested = false
            stopReason = null
            SruGenDeviceGuard.requireSafeToStart(context)
            onProgress(2, "Checking DreamShaper model pack")
            ensureLoaded()
            check(!stopRequested) {
                "DreamShaper loading was cancelled to protect device resources. Your encrypted prompt remains saved."
            }
            SruGenDeviceGuard.assertSafeToContinue(context)
            val currentHandle = handle
            check(currentHandle != 0L && loaded) { "Nezumi Kiln did not finish loading the model." }

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
                    runCatching { onProgress(percent, "Generating locally · $step/$totalSteps") }
                }
            }

            onProgress(5, "Loading DreamShaper 8 · CPU-safe mode")
            val packed = MnnSdNative.generate(
                handle = currentHandle,
                prompt = request.prompt,
                negativePrompt = "low quality, blurry, deformed, distorted",
                width = 512,
                height = 512,
                steps = 20,
                cfg = 7.0f,
                seed = SecureRandom().nextLong().and(Long.MAX_VALUE),
                scheduler = 4, // DPM++ 2M Karras in the pinned Nezumi JNI enum.
                progressListener = progress
            )
            stopReason?.let { throw IllegalStateException(it) }
            check(!stopRequested) {
                "Generation was cancelled to protect device resources. Your encrypted prompt remains saved."
            }
            check(packed != null) {
                "DreamShaper generation failed: ${MnnSdNative.getLastError()}"
            }
            SruGenDeviceGuard.assertSafeToContinue(context)
            onProgress(92, "Decoding generated image")
            val bitmap = decodePackedRgb(packed)
            try {
                val png = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Unable to encode the generated image."
                    }
                    output.toByteArray()
                }
                onProgress(100, "Done · generated locally")
                SruGenResult(
                    imageBytes = png,
                    mimeType = "image/png",
                    modelName = displayName
                )
            } finally {
                bitmap.recycle()
                packed.fill(0)
            }
        }
    }

    private fun decodePackedRgb(packed: ByteArray): Bitmap {
        require(packed.size >= 8) { "Nezumi Kiln returned an invalid image buffer." }
        val header = ByteBuffer.wrap(packed, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.int
        val height = header.int
        require(width == 512 && height == 512) {
            "Nezumi Kiln returned an unexpected image size ($width × $height); expected 512 × 512."
        }
        val pixelCount = Math.multiplyExact(width, height)
        val expectedBytes = Math.addExact(8, Math.multiplyExact(pixelCount, 3))
        require(packed.size == expectedBytes) { "Nezumi Kiln returned a truncated or oversized image buffer." }

        val pixels = IntArray(pixelCount)
        var source = 8
        for (index in pixels.indices) {
            val red = packed[source++].toInt() and 0xff
            val green = packed[source++].toInt() and 0xff
            val blue = packed[source++].toInt() and 0xff
            pixels[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
            pixels.fill(0)
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
