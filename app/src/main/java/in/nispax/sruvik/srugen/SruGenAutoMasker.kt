package `in`.nispax.sruvik.srugen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Prompt-free automatic masks for SruGen. The official MediaPipe SelfieMulticlass
 * model labels 0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 others.
 * It is used only to locate the clothing/background; the diffusion model still
 * performs the visual change.
 */
object SruGenAutoMasker {
    private const val MODEL_ASSET = "models/selfie_multiclass_256x256.tflite"
    private const val CLOTHES_LABEL = 4
    private const val MAX_DECODE_SIDE = 1536

    private data class SegmentationMask(
        val width: Int,
        val height: Int,
        val values: ByteArray
    )

    fun removeBackground(context: Context, imageBytes: ByteArray): ByteArray {
        require(imageBytes.isNotEmpty()) { "Choose an image before removing its background." }
        val source = decodeBounded(imageBytes)
        try {
            val mask = segment(context, source) { label -> label != 0 }
            val softened = soften(mask.values, mask.width, mask.height)
            val pixels = IntArray(mask.width * mask.height)
            source.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
            for (index in pixels.indices) {
                pixels[index] = (pixels[index] and 0x00FFFFFF) or
                    ((softened[index].toInt() and 0xff) shl 24)
            }
            val output = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
            output.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
            pixels.fill(0)
            softened.fill(0)
            return try {
                encodePng(output)
            } finally {
                output.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    fun editClothing(
        context: Context,
        sourceImage: Bitmap,
        generatedImage: Bitmap
    ): Bitmap {
        require(!sourceImage.isRecycled) { "The original photo was released before editing. Reattach it and retry." }
        require(!generatedImage.isRecycled) { "The local model returned an image buffer that was already released. Retry the edit." }
        require(sourceImage.width > 0 && sourceImage.height > 0)

        // Snapshot source pixels BEFORE MediaPipe receives its own isolated copy.
        // This avoids accessing a bitmap after a task/image wrapper has released it.
        val sourceWidth = sourceImage.width
        val sourceHeight = sourceImage.height
        val originalPixels = IntArray(sourceWidth * sourceHeight)
        sourceImage.getPixels(originalPixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
        var generatedPixels: IntArray? = null
        var generatedScaled: Bitmap? = null
        var mask: SegmentationMask? = null
        var softMask: ByteArray? = null
        try {
            val segmentMask = segment(context, sourceImage) { label -> label == CLOTHES_LABEL }
            mask = segmentMask
            val selected = segmentMask.values.count { (it.toInt() and 0xff) > 0 }
            val ratio = selected.toFloat() / segmentMask.values.size.toFloat()
            require(ratio >= 0.008f) {
                "SruGen could not find clothing in this photo. Try a clear, well-lit image showing the person's outfit."
            }
            require(ratio <= 0.90f) {
                "The clothing mask was too broad to edit safely. Try a closer photo with one clearly visible person."
            }

            val smoothMask = soften(segmentMask.values, segmentMask.width, segmentMask.height)
            softMask = smoothMask
            val scaledOutput = if (
                generatedImage.width == sourceWidth && generatedImage.height == sourceHeight
            ) {
                generatedImage
            } else {
                Bitmap.createScaledBitmap(generatedImage, sourceWidth, sourceHeight, true)
            }
            generatedScaled = scaledOutput
            require(!scaledOutput.isRecycled) {
                "The resized model output was released before it could be composited."
            }
            val renderedPixels = IntArray(originalPixels.size)
            generatedPixels = renderedPixels
            scaledOutput.getPixels(
                renderedPixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight
            )

            // Segmenter masks are normally source-sized. Map coordinates explicitly
            // so a runtime returning a lower-resolution category mask stays safe.
            for (y in 0 until sourceHeight) {
                val maskY = (y.toLong() * segmentMask.height / sourceHeight).toInt().coerceIn(0, segmentMask.height - 1)
                for (x in 0 until sourceWidth) {
                    val maskX = (x.toLong() * segmentMask.width / sourceWidth).toInt().coerceIn(0, segmentMask.width - 1)
                    val index = y * sourceWidth + x
                    val alpha = smoothMask[maskY * segmentMask.width + maskX].toInt() and 0xff
                    if (alpha == 0) continue
                    val inverse = 255 - alpha
                    val old = originalPixels[index]
                    val fresh = generatedPixels[index]
                    val red = (((old ushr 16 and 0xff) * inverse) + ((fresh ushr 16 and 0xff) * alpha)) / 255
                    val green = (((old ushr 8 and 0xff) * inverse) + ((fresh ushr 8 and 0xff) * alpha)) / 255
                    val blue = (((old and 0xff) * inverse) + ((fresh and 0xff) * alpha)) / 255
                    originalPixels[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }

            return Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888).also {
                it.setPixels(originalPixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
            }
        } finally {
            if (generatedScaled != null && generatedScaled !== generatedImage && !generatedScaled.isRecycled) {
                generatedScaled.recycle()
            }
            generatedPixels?.fill(0)
            originalPixels.fill(0)
            softMask?.fill(0)
            mask?.values?.fill(0)
        }
    }

    fun decodeBounded(imageBytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The attached file is not a readable image." }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_SIDE ||
            bounds.outHeight / sample > MAX_DECODE_SIDE
        ) sample *= 2
        return BitmapFactory.decodeByteArray(
            imageBytes, 0, imageBytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: throw IllegalArgumentException("SruGen could not decode the attached image.")
    }

    /** Resize to a SD1.5-compatible shape while keeping the source aspect ratio close. */
    fun resizeForDiffusion(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height).toFloat()
        fun scaled(value: Int): Int {
            val raw = value / longest * 512f
            return (((raw / 64f) + 0.5f).toInt() * 64).coerceIn(256, 512)
        }
        val width = scaled(source.width)
        val height = scaled(source.height)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    fun toPackedRgb(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = ByteArray(pixels.size * 3)
        var offset = 0
        pixels.forEach { pixel ->
            rgb[offset++] = (pixel ushr 16).toByte()
            rgb[offset++] = (pixel ushr 8).toByte()
            rgb[offset++] = pixel.toByte()
        }
        pixels.fill(0)
        return rgb
    }

    fun decodePackedRgb(packed: ByteArray): Bitmap {
        require(packed.size >= 8) { "Nezumi Kiln returned an invalid image buffer." }
        val width = ByteBuffer.wrap(packed, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        val height = ByteBuffer.wrap(packed, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        require(width in 256..768 && height in 256..768 && width % 64 == 0 && height % 64 == 0) {
            "Nezumi Kiln returned an unsupported image size ($width × $height)."
        }
        val count = Math.multiplyExact(width, height)
        require(packed.size == 8 + count * 3) { "Nezumi Kiln returned a truncated or oversized image buffer." }
        val pixels = IntArray(count)
        var offset = 8
        for (index in pixels.indices) {
            val red = packed[offset++].toInt() and 0xff
            val green = packed[offset++].toInt() and 0xff
            val blue = packed[offset++].toInt() and 0xff
            pixels[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
            pixels.fill(0)
        }
    }

    fun encodePng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "SruGen could not encode the edited image."
            }
            output.toByteArray()
        }

    private fun segment(
        context: Context,
        bitmap: Bitmap,
        select: (Int) -> Boolean
    ): SegmentationMask {
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        require(!bitmap.isRecycled) { "The source photo was released before segmentation." }
        val segmenter = ImageSegmenter.createFromOptions(context, options)
        val segmentationBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("Unable to prepare a private segmentation copy.")
        val mpImage = BitmapImageBuilder(segmentationBitmap).build()
        try {
            val result = segmenter.segment(mpImage)
            val categoryMask = result.categoryMask().orElseThrow {
                IllegalStateException("The automatic image segmenter did not return a category mask.")
            }
            val width = categoryMask.width
            val height = categoryMask.height
            val buffer = ByteBufferExtractor.extract(categoryMask).duplicate()
            val values = ByteArray(width * height)
            require(buffer.remaining() >= values.size) {
                "The automatic image segmenter returned an incomplete mask."
            }
            for (index in values.indices) {
                val label = buffer.get(index).toInt() and 0xff
                values[index] = if (select(label)) 0xff.toByte() else 0
            }
            return SegmentationMask(width, height, values)
        } finally {
            mpImage.close()
            segmenter.close()
            if (!segmentationBitmap.isRecycled) segmentationBitmap.recycle()
        }
    }

    /**
     * Expand the selected pixels slightly, then feather the boundary with a
     * separable box blur. This avoids a hard pasted-on edge and keeps unmasked
     * areas from being changed by the img2img result.
     */
    private fun soften(binary: ByteArray, width: Int, height: Int): ByteArray {
        val expanded = ByteArray(binary.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var selected = false
                loop@ for (dy in -2..2) for (dx in -2..2) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until width && yy in 0 until height &&
                        (binary[yy * width + xx].toInt() and 0xff) > 0
                    ) {
                        selected = true
                        break@loop
                    }
                }
                if (selected) expanded[y * width + x] = 0xff.toByte()
            }
        }
        val radius = 5
        val horizontal = ByteArray(binary.size)
        val output = ByteArray(binary.size)
        for (y in 0 until height) {
            var sum = 0
            for (x in -radius..radius) {
                val xx = x.coerceIn(0, width - 1)
                sum += expanded[y * width + xx].toInt() and 0xff
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = (sum / (radius * 2 + 1)).toByte()
                val removeX = (x - radius).coerceIn(0, width - 1)
                val addX = (x + radius + 1).coerceIn(0, width - 1)
                sum += (expanded[y * width + addX].toInt() and 0xff) -
                    (expanded[y * width + removeX].toInt() and 0xff)
            }
        }
        for (x in 0 until width) {
            var sum = 0
            for (y in -radius..radius) {
                val yy = y.coerceIn(0, height - 1)
                sum += horizontal[yy * width + x].toInt() and 0xff
            }
            for (y in 0 until height) {
                output[y * width + x] = (sum / (radius * 2 + 1)).toByte()
                val removeY = (y - radius).coerceIn(0, height - 1)
                val addY = (y + radius + 1).coerceIn(0, height - 1)
                sum += (horizontal[addY * width + x].toInt() and 0xff) -
                    (horizontal[removeY * width + x].toInt() and 0xff)
            }
        }
        expanded.fill(0)
        horizontal.fill(0)
        return output
    }
}
