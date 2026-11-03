package `in`.nispax.sruvik.ai

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

internal data class AiUpscaleResult(
    val bitmap: Bitmap,
    val scale: Int,
    val sourceWasOptimized: Boolean
)

internal class DeviceThermalLimitException :
    IllegalStateException("Your phone is running hot. Let it cool, then try again.")

internal object RealEsrganUpscaler {
    private const val ASSET_NAME = "realesr_general_x4v3.tflite"
    private const val STAGED_NAME = "realesr-general-x4v3-bsd-v1.tflite"
    private const val MODEL_INPUT = 128
    private const val MODEL_OUTPUT = 512
    private const val MODEL_SCALE = 4
    private const val TILE_PAD = 10
    private const val TILE_CORE = MODEL_INPUT - TILE_PAD * 2
    private const val OUTPUT_CORE = TILE_CORE * MODEL_SCALE
    private const val OUTPUT_TILE_AREA = MODEL_OUTPUT * MODEL_OUTPUT
    private const val MAX_OUTPUT_PIXELS = 8_000_000L
    private const val MAX_OUTPUT_DIMENSION = 6_144
    private const val MIN_FREE_MEMORY_BYTES = 400L * 1024L * 1024L
    private const val RESERVED_MEMORY_BYTES = 180L * 1024L * 1024L
    // Never call a near-1× or downscaled result an upscale.
    private const val MIN_EFFECTIVE_UPSCALE = 1.25f

    /** Model creation, warm-up, inference and close are confined to one worker thread. */
    suspend fun upscale(
        context: Context,
        source: Bitmap,
        requestedScale: Int,
        onProgress: suspend (completed: Int, total: Int) -> Unit
    ): AiUpscaleResult {
        require(requestedScale == 2 || requestedScale == 4)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Sruvik-Image-AI").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        return try {
            withContext(dispatcher) {
                val appContext = context.applicationContext
                checkThermalState(appContext)
                val outputBudget = outputPixelBudget(appContext)
                val sourcePixels = source.width.toLong() * source.height.toLong()
                val desiredSourcePixels = outputBudget / (requestedScale * requestedScale)
                val dimensionScale = MAX_OUTPUT_DIMENSION.toFloat() /
                    (maxOf(source.width, source.height).toFloat() * requestedScale)
                val pixelScale = kotlin.math.sqrt(
                    desiredSourcePixels.toDouble() / sourcePixels
                ).toFloat()
                val inputScale = min(1f, min(dimensionScale, pixelScale))
                val minimumInputScale = MIN_EFFECTIVE_UPSCALE / requestedScale.toFloat()
                check(inputScale >= minimumInputScale) {
                    val suggestion = if (requestedScale == 4) {
                        "Choose 2×, select a smaller photo, or free up device memory."
                    } else {
                        "Select a smaller photo or free up device memory, then try again."
                    }
                    "This phone cannot safely create a meaningful ${requestedScale}× AI upscale from this photo. " +
                        "No reduced-resolution result was created. $suggestion"
                }
                val working = if (inputScale < 0.999f) {
                    Bitmap.createScaledBitmap(
                        source,
                        (source.width * inputScale).roundToInt().coerceAtLeast(1),
                        (source.height * inputScale).roundToInt().coerceAtLeast(1),
                        true
                    )
                } else source

                var model: CompiledModel? = null
                var inputs: List<TensorBuffer> = emptyList()
                var outputs: List<TensorBuffer> = emptyList()
                try {
                    val environment = PlayServicesLiteRtEnvironment.get(appContext)
                    val compiled = CompiledModel.create(
                        appContext.assets,
                        ASSET_NAME,
                        CompiledModel.Options(Accelerator.CPU),
                        environment
                    )
                    model = compiled
                    inputs = compiled.createInputBuffers()
                    outputs = compiled.createOutputBuffers()
                    check(inputs.isNotEmpty() && outputs.isNotEmpty()) {
                        "The upscaling model has no input or output tensors."
                    }

                    // Warm up once before user-visible tile progress begins.
                    inputs[0].writeFloat(FloatArray(MODEL_INPUT * MODEL_INPUT * 3))
                    compiled.run(inputs, outputs)
                    outputs[0].readFloat()

                    val width = working.width
                    val height = working.height
                    val outputWidth = width * requestedScale
                    val outputHeight = height * requestedScale
                    check(
                        outputWidth >= (source.width * MIN_EFFECTIVE_UPSCALE).roundToInt() &&
                            outputHeight >= (source.height * MIN_EFFECTIVE_UPSCALE).roundToInt()
                    ) {
                        "The safe working size would not produce a meaningful increase over the original. " +
                            "Choose a smaller source photo or try again after freeing device memory."
                    }
                    check(outputWidth.toLong() * outputHeight.toLong() <= outputBudget) {
                        "The selected image is larger than this phone's safe processing limit."
                    }
                    val outputBitmap = Bitmap.createBitmap(
                        outputWidth, outputHeight, Bitmap.Config.ARGB_8888
                    )
                    val sourcePixelsArray = IntArray(width * height)
                    working.getPixels(sourcePixelsArray, 0, width, 0, 0, width, height)
                    val input = FloatArray(MODEL_INPUT * MODEL_INPUT * 3)
                    val outputPatch = IntArray(OUTPUT_CORE * OUTPUT_CORE)
                    val tileColumns = ceil(width.toDouble() / TILE_CORE).toInt()
                    val tileRows = ceil(height.toDouble() / TILE_CORE).toInt()
                    val totalTiles = tileColumns * tileRows
                    var completed = 0

                    try {
                        for (tileY in 0 until tileRows) {
                            for (tileX in 0 until tileColumns) {
                                currentCoroutineContext().ensureActive()
                                checkThermalState(appContext)

                                val destinationX = tileX * TILE_CORE
                                val destinationY = tileY * TILE_CORE
                                fillInputTile(
                                    sourcePixelsArray, width, height,
                                    destinationX - TILE_PAD,
                                    destinationY - TILE_PAD,
                                    input
                                )
                                inputs[0].writeFloat(input)
                                compiled.run(inputs, outputs)
                                val prediction = outputs[0].readFloat()
                                check(prediction.size == OUTPUT_TILE_AREA * 3) {
                                    "Unexpected model output. The AI model file may be incomplete."
                                }

                                var offset = 0
                                for (y in 0 until OUTPUT_CORE) {
                                    val modelY = y + TILE_PAD * MODEL_SCALE
                                    val rowOffset = modelY * MODEL_OUTPUT + TILE_PAD * MODEL_SCALE
                                    for (x in 0 until OUTPUT_CORE) {
                                        val pixelIndex = rowOffset + x
                                        val red = (prediction[pixelIndex].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                                        val green = (prediction[OUTPUT_TILE_AREA + pixelIndex].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                                        val blue = (prediction[OUTPUT_TILE_AREA * 2 + pixelIndex].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                                        outputPatch[offset++] = (0xFF shl 24) or
                                            (red shl 16) or (green shl 8) or blue
                                    }
                                }

                                val validWidth = min(TILE_CORE, width - destinationX)
                                val validHeight = min(TILE_CORE, height - destinationY)
                                val outX = destinationX * requestedScale
                                val outY = destinationY * requestedScale
                                if (requestedScale == 4) {
                                    outputBitmap.setPixels(
                                        outputPatch, 0, OUTPUT_CORE,
                                        outX, outY,
                                        validWidth * requestedScale,
                                        validHeight * requestedScale
                                    )
                                } else {
                                    val patch = Bitmap.createBitmap(
                                        outputPatch, OUTPUT_CORE, OUTPUT_CORE,
                                        Bitmap.Config.ARGB_8888
                                    )
                                    val halfPatch = Bitmap.createScaledBitmap(
                                        patch, OUTPUT_CORE / 2, OUTPUT_CORE / 2, true
                                    )
                                    patch.recycle()
                                    val halfPixels = IntArray((OUTPUT_CORE / 2) * (OUTPUT_CORE / 2))
                                    halfPatch.getPixels(
                                        halfPixels, 0, OUTPUT_CORE / 2,
                                        0, 0, OUTPUT_CORE / 2, OUTPUT_CORE / 2
                                    )
                                    halfPatch.recycle()
                                    outputBitmap.setPixels(
                                        halfPixels, 0, OUTPUT_CORE / 2,
                                        outX, outY,
                                        validWidth * requestedScale,
                                        validHeight * requestedScale
                                    )
                                }

                                completed++
                                withContext(Dispatchers.Main.immediate) {
                                    onProgress(completed, totalTiles)
                                }
                                if (thermalStatus(appContext) >= PowerManager.THERMAL_STATUS_MODERATE) {
                                    delay(24)
                                }
                            }
                        }
                        AiUpscaleResult(outputBitmap, requestedScale, working !== source)
                    } catch (error: Throwable) {
                        outputBitmap.recycle()
                        throw error
                    }
                } finally {
                    outputs.forEach { runCatching { it.close() } }
                    inputs.forEach { runCatching { it.close() } }
                    runCatching { model?.close() }
                    if (working !== source) working.recycle()
                }
            }
        } finally {
            dispatcher.close()
            executor.shutdown()
        }
    }

    private fun fillInputTile(
        source: IntArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        target: FloatArray
    ) {
        val area = MODEL_INPUT * MODEL_INPUT
        for (y in 0 until MODEL_INPUT) {
            val sourceY = (startY + y).coerceIn(0, height - 1)
            for (x in 0 until MODEL_INPUT) {
                val sourceX = (startX + x).coerceIn(0, width - 1)
                val pixel = source[sourceY * width + sourceX]
                val index = (y * MODEL_INPUT + x) * 3
                // LiteRT input is RGB NHWC, normalized to [0, 1].
                target[index] = ((pixel shr 16) and 0xFF) / 255f
                target[index + 1] = ((pixel shr 8) and 0xFF) / 255f
                target[index + 2] = (pixel and 0xFF) / 255f
            }
        }
    }


    private fun outputPixelBudget(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        if (memory.lowMemory || memory.availMem < MIN_FREE_MEMORY_BYTES) {
            error("Your phone is low on free memory. Close a few apps and try again.")
        }
        val classBudget = manager.memoryClass.toLong() * 1024L * 1024L * 18L / 100L / 4L
        val availableBudget = (memory.availMem - RESERVED_MEMORY_BYTES).coerceAtLeast(0L) * 18L / 100L / 4L
        var budget = minOf(MAX_OUTPUT_PIXELS, classBudget, availableBudget)
        if (manager.isLowRamDevice) budget = minOf(budget, 3_000_000L)
        check(budget >= 1_000_000L) {
            "There isn't enough free memory for a safe AI upscale right now."
        }
        return budget
    }

    private fun thermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return PowerManager.THERMAL_STATUS_NONE
        return (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
    }

    private fun checkThermalState(context: Context) {
        if (thermalStatus(context) >= PowerManager.THERMAL_STATUS_SEVERE) {
            throw DeviceThermalLimitException()
        }
    }
}