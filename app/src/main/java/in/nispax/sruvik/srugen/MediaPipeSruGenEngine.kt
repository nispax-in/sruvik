package `in`.nispax.sruvik.srugen

import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGeneratorResult
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real local image-generation provider using the official MediaPipe Image Generator API
 * and a converted Stable Diffusion 1.5 model. The weights are installed separately.
 */
class MediaPipeSruGenEngine(
    private val context: Context,
    private val modelDirectory: String
) : SruGenEngine, AutoCloseable {
    override val displayName: String = "Stable Diffusion 1.5 · MediaPipe"
    override val isOffline: Boolean = true

    @Volatile
    private var imageGenerator: ImageGenerator? = null
    @Volatile
    private var closed = false
    override val isClosed: Boolean get() = closed
    private val inferenceLock = ReentrantLock()

    private fun getOrCreateGenerator(): ImageGenerator {
        check(!closed) { "The local SruGen engine was released. Reopen SruGen to initialize it again." }
        imageGenerator?.let { return it }
        synchronized(this) {
            check(!closed) { "The local SruGen engine was released. Reopen SruGen to initialize it again." }
            imageGenerator?.let { return it }
            val options = ImageGenerator.ImageGeneratorOptions.builder()
                .setImageGeneratorModelDirectory(modelDirectory)
                .setErrorListener { failure ->
                    android.util.Log.e("SruGenEngine", "MediaPipe image-generation task failed.", failure)
                }
                .build()
            val created = ImageGenerator.createFromOptions(context, options)
            imageGenerator = created
            return created
        }
    }

    override suspend fun generate(
        request: SruGenRequest,
        onProgress: (Int, String) -> Unit
    ): SruGenResult = withContext(Dispatchers.Default) {
        inferenceLock.withLock {
        check(!closed) { "The local SruGen engine was released. Reopen SruGen to initialize it again." }
        require(request.mode == SruGenMode.TEXT_TO_IMAGE) {
            when (request.mode) {
                SruGenMode.PROMPT_EDIT -> "This installed Stable Diffusion 1.5 runtime currently supports text-to-image only. Reference-image editing is not enabled in this engine."
                SruGenMode.REMOVE_BACKGROUND -> "Install an on-device segmentation model to use background removal. It is not part of this diffusion model."
                SruGenMode.REMOVE_OBJECT -> "Object removal needs a separate inpainting/control model. It is not part of this runtime."
                else -> "Unsupported SruGen workflow."
            }
        }
        require(request.prompt.isNotBlank()) { "Describe the image you want to create." }
        // Fail closed before the first native allocation. The guard checks total
        // RAM, current free headroom, free storage, thermal pressure and Battery Saver.
        SruGenDeviceGuard.requireSafeToStart(context)
        val generator = getOrCreateGenerator()
        val seed = SecureRandom().nextInt()
        onProgress(3, "Preparing local diffusion model")
        // Run each native diffusion step off the main thread and report real progress.
        val iterations = 20
        generator.setInputs(request.prompt, iterations, seed)
        var finalResult: ImageGeneratorResult? = null
        for (step in 0 until iterations) {
            // Recheck before every native diffusion step; a device can heat up or
            // enter low-memory state while the model is running.
            SruGenDeviceGuard.assertSafeToContinue(context)
            finalResult = generator.execute(step == iterations - 1)
            onProgress(
                8 + ((step + 1) * 82 / iterations),
                "Generating locally · ${step + 1}/$iterations"
            )
        }
        val generated = finalResult?.generatedImage()
            ?: throw IllegalStateException("The local image engine returned no image.")
        val bitmap: Bitmap = BitmapExtractor.extract(generated)
            ?: throw IllegalStateException("Unable to decode the generated image.")
        onProgress(92, "Encoding the result")
        val bytes = ByteArrayOutputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IllegalStateException("Unable to encode the generated image.")
            }
            stream.toByteArray()
        }
        onProgress(100, "Done · generated locally")
        SruGenResult(
            imageBytes = bytes,
            mimeType = "image/png",
            modelName = displayName,
            seed = seed.toLong()
        )
        }
    }

    override fun close() {
        inferenceLock.withLock {
            if (closed) return
            closed = true
            try {
                imageGenerator?.close()
            } catch (failure: Exception) {
                android.util.Log.w("SruGenEngine", "Error while releasing the local image generator.", failure)
            } finally {
                imageGenerator = null
            }
        }
    }
}
