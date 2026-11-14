package `in`.nispax.sruvik.srugen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Produces a finger-specific edit region from MediaPipe's 21 hand landmarks.
 * Only one explicitly named finger is accepted. When multiple hands are in the
 * photo, the user must identify left or right instead of risking the wrong hand.
 *
 * The current SD1.5 native bridge is img2img-only. This mask constrains the final
 * composite and protects every zero-alpha pixel; it is not latent-mask inpainting.
 */
object SruGenHandMasker {
    private const val MODEL_ASSET = "models/hand_landmarker.task"

    class EditPlan internal constructor(
        val crop: Bitmap,
        val bounds: Rect,
        internal val maskAlpha: ByteArray,
        val imageWidth: Int,
        val imageHeight: Int
    ) : Closeable {
        override fun close() {
            if (!crop.isRecycled) crop.recycle()
            maskAlpha.fill(0)
        }
    }

    fun createEditPlan(context: Context, source: Bitmap, prompt: String): EditPlan {
        require(!source.isRecycled) { "The original image was released before hand detection." }
        val finger = SruGenEditIntentParser.finger(prompt)
            ?: throw IllegalArgumentException(
                "Name one finger to edit (thumb, index, middle, ring or little finger)."
            )

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(2)
            .build()
        val landmarker = HandLandmarker.createFromOptions(context, options)
        val image = BitmapImageBuilder(source).build()
        var crop: Bitmap? = null
        var maskBitmap: Bitmap? = null
        try {
            val result = landmarker.detect(image)
            val hands = result.landmarks()
            require(hands.isNotEmpty()) {
                "SruGen could not find a clear hand. Try a well-lit photo with the whole hand visible."
            }
            val handedness = result.handedness()
            val requestedSide = SruGenEditIntentParser.handSide(prompt)
            val handIndex = when {
                requestedSide != null -> {
                    handedness.indices.filter { index ->
                        val category = handedness.getOrNull(index)?.firstOrNull()
                        category?.categoryName().equals(requestedSide.name, ignoreCase = true) &&
                            (category?.score() ?: 0f) >= 0.50f
                    }.singleOrNull() ?: throw IllegalArgumentException(
                        "SruGen could not confidently identify the requested ${requestedSide.name.lowercase()} hand. Check whether the photo is mirrored or try a clearer photo."
                    )
                }
                hands.size == 1 -> 0
                else -> throw IllegalArgumentException(
                    "More than one hand is visible. Specify 'left hand' or 'right hand' so SruGen edits the correct finger."
                )
            }
            val hand = hands[handIndex]
            require(hand.size >= 21) { "The hand landmark model returned an incomplete hand." }
            val pointsX = FloatArray(21) { index -> hand[index].x() * source.width }
            val pointsY = FloatArray(21) { index -> hand[index].y() * source.height }
            val indices = finger.landmarkIndices
            val baseIndex = indices.first()
            val tipIndex = indices.last()
            val length = distance(pointsX[baseIndex], pointsY[baseIndex], pointsX[tipIndex], pointsY[tipIndex])
            require(length >= max(source.width, source.height) * 0.025f) {
                "The selected finger is too small or hidden. Try a closer photo with the hand fully visible."
            }

            val adjacentSpacing = when (finger) {
                SruGenEditIntentParser.Finger.THUMB ->
                    distance(pointsX[1], pointsY[1], pointsX[5], pointsY[5])
                SruGenEditIntentParser.Finger.INDEX ->
                    distance(pointsX[5], pointsY[5], pointsX[9], pointsY[9])
                SruGenEditIntentParser.Finger.MIDDLE ->
                    (distance(pointsX[5], pointsY[5], pointsX[9], pointsY[9]) +
                        distance(pointsX[9], pointsY[9], pointsX[13], pointsY[13])) / 2f
                SruGenEditIntentParser.Finger.RING ->
                    distance(pointsX[9], pointsY[9], pointsX[13], pointsY[13])
                SruGenEditIntentParser.Finger.PINKY ->
                    distance(pointsX[13], pointsY[13], pointsX[17], pointsY[17])
            }
            val strokeWidth = (adjacentSpacing * 0.82f).coerceIn(
                max(5f, max(source.width, source.height) * 0.006f),
                max(12f, max(source.width, source.height) * 0.075f)
            )

            val path = Path()
            path.moveTo(pointsX[indices[0]], pointsY[indices[0]])
            for (position in 1 until indices.size) {
                path.lineTo(pointsX[indices[position]], pointsY[indices[position]])
            }
            val previous = indices[indices.size - 2]
            val tipX = pointsX[tipIndex]
            val tipY = pointsY[tipIndex]
            val directionX = tipX - pointsX[previous]
            val directionY = tipY - pointsY[previous]
            val segmentLength = hypot(directionX, directionY).coerceAtLeast(1f)
            // Reserve an editable halo beyond the current tip so length changes
            // do not get clipped to the original silhouette.
            val extension = length * 0.42f
            path.lineTo(tipX + directionX / segmentLength * extension,
                tipY + directionY / segmentLength * extension)

            val minX = indices.minOf { pointsX[it] }.coerceAtMost(tipX)
            val maxX = indices.maxOf { pointsX[it] }.coerceAtLeast(tipX + directionX / segmentLength * extension)
            val minY = indices.minOf { pointsY[it] }.coerceAtMost(tipY)
            val maxY = indices.maxOf { pointsY[it] }.coerceAtLeast(tipY + directionY / segmentLength * extension)
            val padding = strokeWidth * 2.5f
            val bounds = Rect(
                (minX - padding).toInt().coerceIn(0, source.width - 1),
                (minY - padding).toInt().coerceIn(0, source.height - 1),
                ceil((maxX + padding).toDouble()).toInt().coerceIn(1, source.width),
                ceil((maxY + padding).toDouble()).toInt().coerceIn(1, source.height)
            )
            require(bounds.width() >= 32 && bounds.height() >= 32) {
                "The finger region is too small to edit reliably. Use a closer hand photo."
            }

            maskBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(maskBitmap!!)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.strokeWidth = strokeWidth * 1.45f
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(pointsX[baseIndex], pointsY[baseIndex], strokeWidth * 0.73f, paint)
            canvas.drawCircle(
                tipX + directionX / segmentLength * extension,
                tipY + directionY / segmentLength * extension,
                strokeWidth * 0.68f,
                paint
            )

            val maskPixels = IntArray(source.width * source.height)
            maskBitmap!!.getPixels(maskPixels, 0, source.width, 0, 0, source.width, source.height)
            val alpha = ByteArray(maskPixels.size) { index -> Color.alpha(maskPixels[index]).toByte() }
            maskPixels.fill(0)
            crop = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width(), bounds.height())
            return EditPlan(crop!!, bounds, alpha, source.width, source.height)
        } catch (failure: Throwable) {
            crop?.takeIf { !it.isRecycled }?.recycle()
            throw failure
        } finally {
            image.close()
            landmarker.close()
            maskBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    fun applyGeneratedEdit(source: Bitmap, generated: Bitmap, plan: EditPlan): Bitmap {
        require(!source.isRecycled && !generated.isRecycled)
        require(source.width == plan.imageWidth && source.height == plan.imageHeight)
        val bounds = plan.bounds
        val scaled = Bitmap.createScaledBitmap(generated, bounds.width(), bounds.height(), true)
        val originalPixels = IntArray(source.width * source.height)
        val generatedPixels = IntArray(bounds.width() * bounds.height())
        source.getPixels(originalPixels, 0, source.width, 0, 0, source.width, source.height)
        scaled.getPixels(generatedPixels, 0, bounds.width(), 0, 0, bounds.width(), bounds.height())
        try {
            val composited = SruGenMaskCompositor.composite(
                originalPixels, source.width, source.height,
                generatedPixels, bounds.width(), bounds.height(),
                bounds.left, bounds.top,
                plan.maskAlpha, plan.imageWidth, plan.imageHeight
            )
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(composited, 0, source.width, 0, 0, source.width, source.height)
                composited.fill(0)
            }
        } finally {
            originalPixels.fill(0)
            generatedPixels.fill(0)
            if (scaled !== generated && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot(x2 - x1, y2 - y1)
}
