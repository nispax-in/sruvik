package `in`.nispax.sruvik.srugen

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.util.Locale

data class SruGenModelDeviceAssessment(
    val canDownload: Boolean,
    val reasons: List<String>,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val androidApi: Int,
    val supportedAbis: List<String>,
    val thermalStatus: Int?
)

/**
 * Conservative Sruvik preflight. It deliberately applies stricter thresholds
 * than model-card minimums. This is a screening heuristic, not a safety guarantee.
 */
object SruGenModelCompatibility {
    private const val GIB = 1024L * 1024L * 1024L
    private const val MIN_PHYSICAL_RAM = 8L * GIB
    private const val MIN_AVAILABLE_RAM = 4L * GIB
    private const val MIN_FREE_AFTER_DOWNLOAD = 750L * 1024L * 1024L

    fun assess(context: Context, model: SruGenGalleryModel): SruGenModelDeviceAssessment =
        assessInternal(context, model, downloading = true)

    fun assessForInstall(context: Context, model: SruGenGalleryModel): SruGenModelDeviceAssessment =
        assessInternal(context, model, downloading = false)

    private fun assessInternal(
        context: Context,
        model: SruGenGalleryModel,
        downloading: Boolean
    ): SruGenModelDeviceAssessment {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val freeStorage = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { power.currentThermalStatus }.getOrNull()
        } else null
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val reasons = mutableListOf<String>()

        if (!model.canInstall) reasons += "This model is listed for discovery, but its runtime or verified install package is not ready."
        if (Build.VERSION.SDK_INT < model.minAndroidApi) {
            reasons += "This model requires Android API ${model.minAndroidApi}; this device is API ${Build.VERSION.SDK_INT}."
        }
        if (model.requiredAbis.isNotEmpty() && model.requiredAbis.none { it in abis }) {
            reasons += "This model requires one of these CPU architectures: ${model.requiredAbis.joinToString()}."
        }
        if (am.isLowRamDevice) reasons += "Android identifies this as a low-RAM device."
        if (memory.totalMem < maxOf(MIN_PHYSICAL_RAM, model.recommendedRamGb.toLong() * GIB)) {
            reasons += "Sruvik requires at least ${maxOf(8, model.recommendedRamGb)} GB physical RAM for this model. Detected ${formatGb(memory.totalMem)} GB."
        }
        if (memory.lowMemory || memory.availMem < MIN_AVAILABLE_RAM) {
            reasons += "At least 4 GB currently available system RAM is required before installing or running a diffusion model. Detected ${formatGb(memory.availMem)} GB."
        }
        val existingModelBytes = runCatching {
            when (model.engine) {
                SruGenGalleryEngine.MEDIAPIPE_SD15 ->
                    SruGenModelInstaller.modelDirectory(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                SruGenGalleryEngine.NEZUMI_MNN_SD15 ->
                    SruGenModelPackInstaller.installedBytes(context, model.id)
                null -> 0L
            }
        }.getOrDefault(0L)
        val minimumStorage = if (downloading) {
            when (model.engine) {
                SruGenGalleryEngine.MEDIAPIPE_SD15 -> 8L * GIB
                SruGenGalleryEngine.NEZUMI_MNN_SD15 -> maxOf(
                    7L * GIB,
                    model.approximateDownloadBytes + model.maxExtractedBytes + existingModelBytes + MIN_FREE_AFTER_DOWNLOAD
                )
                null -> Long.MAX_VALUE
            }
        } else {
            model.approximateDownloadBytes + model.maxExtractedBytes + existingModelBytes + MIN_FREE_AFTER_DOWNLOAD
        }
        if (freeStorage < minimumStorage) {
            val minimumGb = formatGb(minimumStorage)
            reasons += "Not enough internal storage. Sruvik needs about $minimumGb GB free for ${if (downloading) "download and installation" else "installation and recovery"}; currently free: ${formatGb(freeStorage)} GB."
        }
        if (power.isPowerSaveMode) reasons += "Battery Saver is enabled. Turn it off before installing a long-running on-device model."
        if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_MODERATE) {
            reasons += "The phone is warm or thermally throttling. Let it cool before installing or running a model."
        }

        return SruGenModelDeviceAssessment(
            canDownload = reasons.isEmpty(),
            reasons = reasons,
            totalRamBytes = memory.totalMem,
            availableRamBytes = memory.availMem,
            freeStorageBytes = freeStorage,
            androidApi = Build.VERSION.SDK_INT,
            supportedAbis = abis,
            thermalStatus = thermal
        )
    }

    private fun formatGb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1_000_000_000.0)
}
