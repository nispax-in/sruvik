package `in`.nispax.sruvik.srugen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
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

    /**
     * Crop the diffusion input to the detected garment, with a little surrounding
     * context. Full-frame img2img at high denoise made the model invent a new
     * torso/person inside the later clothing composite. A garment crop makes the
     * requested fabric/colour change the dominant subject instead.
     */
    fun cropForClothingEdit(context: Context, sourceImage: Bitmap): Bitmap {
        require(!sourceImage.isRecycled) { "The original photo was released before clothing detection." }
        val mask = segment(context, sourceImage) { label -> label == CLOTHES_LABEL }
        try {
            val bounds = clothingBounds(mask, sourceImage.width, sourceImage.height)
            val selected = mask.values.count { (it.toInt() and 0xff) > 0 }
            val ratio = selected.toFloat() / mask.values.size.toFloat()
            require(ratio >= 0.008f) {
                "SruGen could not find clothing in this photo. Try a clear, well-lit image showing the person's outfit."
            }
            require(ratio <= 0.90f) {
                "The clothing mask was too broad to edit safely. Try a closer photo with one clearly visible person."
            }
            return Bitmap.createBitmap(
                sourceImage,
                bounds.left,
                bounds.top,
                bounds.width(),
                bounds.height()
            )
        } finally {
            mask.values.fill(0)
        }
    }

    /**
     * A pure colour change should preserve the garment's real folds, stitching,
     * shadows and silhouette. Diffusion img2img can hallucinate a new torso, so
     * recolour only the clothes segmentation mask and leave every other pixel
     * sourced directly from the original photo.
     */
    fun recolorClothing(
        context: Context,
        sourceImage: Bitmap,
        targetColor: String
    ): Bitmap {
        require(!sourceImage.isRecycled) { "The original photo was released before recolouring." }
        val width = sourceImage.width
        val height = sourceImage.height
        require(width > 0 && height > 0)
        val original = IntArray(width * height)
        sourceImage.getPixels(original, 0, width, 0, 0, width, height)
        var mask: SegmentationMask? = null
        var alphaMask: ByteArray? = null
        try {
            val clothes = segment(context, sourceImage) { label -> label == CLOTHES_LABEL }
            mask = clothes
            val selected = clothes.values.count { (it.toInt() and 0xff) > 0 }
            val ratio = selected.toFloat() / clothes.values.size.toFloat()
            require(ratio >= 0.008f) {
                "SruGen could not find clothing in this photo. Try a clear, well-lit image showing the person's outfit."
            }
            require(ratio <= 0.90f) {
                "The clothing mask was too broad to recolour safely. Try a closer photo with one clearly visible person."
            }
            val feather = soften(clothes.values, clothes.width, clothes.height, expansionRadius = 0)
            alphaMask = feather
            val outputPixels = original.copyOf()
            val hsv = FloatArray(3)
            val targetHue = when (targetColor.lowercase()) {
                "red" -> 0f
                "orange", "brown" -> 28f
                "yellow" -> 55f
                "green" -> 120f
                "teal" -> 175f
                "cyan" -> 190f
                "blue" -> 220f
                "purple", "violet" -> 275f
                "pink" -> 330f
                else -> null
            }

            for (y in 0 until height) {
                val maskY = (y.toLong() * clothes.height / height).toInt()
                    .coerceIn(0, clothes.height - 1)
                for (x in 0 until width) {
                    val maskX = (x.toLong() * clothes.width / width).toInt()
                        .coerceIn(0, clothes.width - 1)
                    val alpha = feather[maskY * clothes.width + maskX].toInt() and 0xff
                    if (alpha == 0) continue
                    val index = y * width + x
                    val old = original[index]
                    Color.RGBToHSV(Color.red(old), Color.green(old), Color.blue(old), hsv)
                    when (targetColor.lowercase()) {
                        "black" -> {
                            hsv[0] = 0f
                            hsv[1] = minOf(hsv[1], 0.18f)
                            hsv[2] = (hsv[2] * 0.38f).coerceAtLeast(0.035f)
                        }
                        "white" -> {
                            hsv[0] = 0f
                            hsv[1] = minOf(hsv[1], 0.08f)
                            hsv[2] = (0.68f + hsv[2] * 0.32f).coerceAtMost(1f)
                        }
                        "gray", "grey" -> {
                            hsv[0] = 0f
                            hsv[1] = minOf(hsv[1], 0.07f)
                            hsv[2] = (0.30f + hsv[2] * 0.48f).coerceIn(0.12f, 0.82f)
                        }
                        else -> {
                            hsv[0] = requireNotNull(targetHue) {
                                "Choose a supported clothing colour."
                            }
                            hsv[1] = maxOf(hsv[1], if (targetColor == "brown") 0.60f else 0.68f)
                            if (targetColor == "brown") hsv[2] = (hsv[2] * 0.68f).coerceAtLeast(0.08f)
                        }
                    }
                    val changed = Color.HSVToColor(Color.alpha(old), hsv)
                    val inverse = 255 - alpha
                    val red = ((Color.red(old) * inverse) + (Color.red(changed) * alpha)) / 255
                    val green = ((Color.green(old) * inverse) + (Color.green(changed) * alpha)) / 255
                    val blue = ((Color.blue(old) * inverse) + (Color.blue(changed) * alpha)) / 255
                    outputPixels[index] = Color.argb(Color.alpha(old), red, green, blue)
                }
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(outputPixels, 0, width, 0, 0, width, height)
                outputPixels.fill(0)
            }
        } finally {
            original.fill(0)
            alphaMask?.fill(0)
            mask?.values?.fill(0)
        }
    }

    fun editClothing(
        context: Context,
        sourceImage: Bitmap,
        generatedImage: Bitmap
    ): Bitmap {
        require(!sourceImage.isRecycled) { "The original photo was released before editing." }
        require(!generatedImage.isRecycled) { "The local model returned an image buffer that was already released. Retry the edit." }
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

            val bounds = clothingBounds(segmentMask, sourceWidth, sourceHeight)
            val smoothMask = soften(segmentMask.values, segmentMask.width, segmentMask.height)
            softMask = smoothMask
            val scaledOutput = Bitmap.createScaledBitmap(
                generatedImage,
                bounds.width(),
                bounds.height(),
                true
            )
            generatedScaled = scaledOutput
            require(!scaledOutput.isRecycled) {
                "The resized model output was released before it could be composited."
            }
            val renderedPixels = IntArray(bounds.width() * bounds.height())
            generatedPixels = renderedPixels
            scaledOutput.getPixels(
                renderedPixels, 0, bounds.width(), 0, 0, bounds.width(), bounds.height()
            )

            // The diffusion model only sees a garment-focused crop. Paste its
            // result back through the original full-resolution clothing mask;
            // all pixels outside the detected garment remain byte-for-byte
            // sourced from the original photograph.
            for (y in bounds.top until bounds.bottom) {
                val maskY = (y.toLong() * segmentMask.height / sourceHeight)
                    .toInt().coerceIn(0, segmentMask.height - 1)
                for (x in bounds.left until bounds.right) {
                    val maskX = (x.toLong() * segmentMask.width / sourceWidth)
                        .toInt().coerceIn(0, segmentMask.width - 1)
                    val alpha = smoothMask[maskY * segmentMask.width + maskX].toInt() and 0xff
                    if (alpha == 0) continue
                    val oldIndex = y * sourceWidth + x
                    val freshIndex = (y - bounds.top) * bounds.width() + (x - bounds.left)
                    val old = originalPixels[oldIndex]
                    val fresh = renderedPixels[freshIndex]
                    val inverse = 255 - alpha
                    val red = (((old ushr 16 and 0xff) * inverse) + ((fresh ushr 16 and 0xff) * alpha)) / 255
                    val green = (((old ushr 8 and 0xff) * inverse) + ((fresh ushr 8 and 0xff) * alpha)) / 255
                    val blue = (((old and 0xff) * inverse) + ((fresh and 0xff) * alpha)) / 255
                    originalPixels[oldIndex] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
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

    private fun clothingBounds(mask: SegmentationMask, imageWidth: Int, imageHeight: Int): Rect {
        var minX = mask.width
        var minY = mask.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if ((mask.values[y * mask.width + x].toInt() and 0xff) == 0) continue
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        require(maxX >= minX && maxY >= minY) {
            "SruGen could not identify a clothing region in this photo."
        }

        val left = (minX.toLong() * imageWidth / mask.width).toInt()
        val top = (minY.toLong() * imageHeight / mask.height).toInt()
        val right = (((maxX + 1L) * imageWidth + mask.width - 1) / mask.width).toInt()
        val bottom = (((maxY + 1L) * imageHeight + mask.height - 1) / mask.height).toInt()
        val garmentWidth = (right - left).coerceAtLeast(1)
        val garmentHeight = (bottom - top).coerceAtLeast(1)
        val padX = (garmentWidth * 0.18f).toInt().coerceAtLeast(12)
        val padY = (garmentHeight * 0.18f).toInt().coerceAtLeast(12)
        return Rect(
            (left - padX).coerceAtLeast(0),
            (top - padY).coerceAtLeast(0),
            (right + padX).coerceAtMost(imageWidth),
            (bottom + padY).coerceAtMost(imageHeight)
        )
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
        require(!bitmap.isRecycled) { "The source photo was released before segmentation." }
        val segmentationBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("Unable to prepare a private segmentation copy.")
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        val segmenter = try {
            ImageSegmenter.createFromOptions(context, options)
        } catch (failure: Exception) {
            if (!segmentationBitmap.isRecycled) segmentationBitmap.recycle()
            throw failure
        }
        try {
            val mpImage = BitmapImageBuilder(segmentationBitmap).build()
            try {
                val result = segmenter.segment(mpImage)
                val categoryMask = result.categoryMask().orElseThrow {
                    IllegalStateException("The automatic image segmenter did not return a category mask.")
                }
                val width = categoryMask.width
                val height = categoryMask.height
                require(width > 0 && height > 0) {
                    "The automatic image segmenter returned an empty category mask."
                }
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
            }
        } finally {
            try {
                segmenter.close()
            } finally {
                if (!segmentationBitmap.isRecycled) segmentationBitmap.recycle()
            }
        }
    }

    /**
     * Expand the selected pixels slightly, then feather the boundary with a
     * separable box blur. This avoids a hard pasted-on edge and keeps unmasked
     * areas from being changed by the img2img result.
     */
    private fun soften(
        binary: ByteArray,
        width: Int,
        height: Int,
        expansionRadius: Int = 2
    ): ByteArray {
        val expanded = ByteArray(binary.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var selected = false
                loop@ for (dy in -expansionRadius..expansionRadius) for (dx in -expansionRadius..expansionRadius) {
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
