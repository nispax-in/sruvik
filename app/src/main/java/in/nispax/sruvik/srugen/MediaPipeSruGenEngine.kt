package `in`.nispax.sruvik.srugen

import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
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

    private fun getOrCreateGenerator(): ImageGenerator {
        imageGenerator?.let { return it }
        synchronized(this) {
            imageGenerator?.let { return it }
            val options = ImageGenerator.ImageGeneratorOptions.builder()
                .setImageGeneratorModelDirectory(modelDirectory)
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
        require(request.mode == SruGenMode.TEXT_TO_IMAGE) {
            when (request.mode) {
                SruGenMode.PROMPT_EDIT -> "This installed Stable Diffusion 1.5 runtime currently supports text-to-image only. Reference-image editing is not enabled in this engine."
                SruGenMode.REMOVE_BACKGROUND -> "Install an on-device segmentation model to use background removal. It is not part of this diffusion model."
                SruGenMode.REMOVE_OBJECT -> "Object removal needs a separate inpainting/control model. It is not part of this runtime."
                else -> "Unsupported SruGen workflow."
            }
        }
        require(request.prompt.isNotBlank()) { "Describe the image you want to create." }
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        require(memory.availMem >= 2_800_000_000L) {
            "SruGen needs about 2.8 GB of free system memory before loading Stable Diffusion. Close other apps and try again. An 8 GB RAM phone is recommended."
        }
        val generator = getOrCreateGenerator()
        val seed = SecureRandom().nextInt()
        onProgress(3, "Preparing local diffusion model")
        // MediaPipe ImageGenerator.generate is a blocking native call; stay off the UI thread.
        onProgress(8, "Generating on this device")
        val result = generator.generate(request.prompt, 20, seed)
            ?: throw IllegalStateException("The local image engine returned no result.")
        val generated = result.generatedImage()
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

    override fun close() {
        synchronized(this) {
            try {
                imageGenerator?.close()
            } catch (_: Exception) {
            } finally {
                imageGenerator = null
            }
        }
    }
}
