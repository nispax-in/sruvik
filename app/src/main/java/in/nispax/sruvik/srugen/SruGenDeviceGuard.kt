package `in`.nispax.sruvik.srugen

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

/**
 * Conservative preflight and runtime checks for local diffusion workloads.
 *
 * These checks reduce risk; Android may still reclaim or terminate any app under
 * system-wide memory pressure. We deliberately fail closed when the device is
 * already under memory/thermal pressure.
 */
object SruGenDeviceGuard {
    private const val GIB = 1024L * 1024L * 1024L
    private const val MIN_TOTAL_RAM_BYTES = 8L * GIB
    private const val MIN_AVAILABLE_BEFORE_LOAD_BYTES = 4L * GIB
    private const val MIN_AVAILABLE_DURING_RUN_BYTES = 2L * GIB
    private const val MIN_FREE_STORAGE_BYTES = 1L * GIB

    data class Assessment(
        val allowed: Boolean,
        val reasons: List<String>,
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val availableStorageBytes: Long,
        val thermalStatus: Int?
    )

    fun assessBeforeGeneration(context: Context): Assessment {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val storage = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        val thermal = currentThermalStatus(context)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val reasons = mutableListOf<String>()

        if (activityManager.isLowRamDevice) {
            reasons += "Android identifies this as a low-RAM device. SruGen will not load a large diffusion model on it."
        }
        if (memory.totalMem < MIN_TOTAL_RAM_BYTES) {
            reasons += "This model requires at least 8 GB physical RAM. SruGen stopped before loading it."
        }
        if (memory.lowMemory || memory.availMem < MIN_AVAILABLE_BEFORE_LOAD_BYTES) {
            val available = String.format(java.util.Locale.US, "%.1f", memory.availMem / 1_000_000_000.0)
            reasons += "Only $available GB system RAM is currently available; at least 4 GB of headroom is required before loading the model."
        }
        if (storage < MIN_FREE_STORAGE_BYTES) {
            val available = String.format(java.util.Locale.US, "%.1f", storage / 1_000_000_000.0)
            reasons += "Only $available GB storage is free. Keep at least 1 GB free for safe temporary output and recovery."
        }
        if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_MODERATE) {
            reasons += "The phone is already warm or thermally throttling. Let it cool before starting image generation."
        }
        if (powerManager.isPowerSaveMode) {
            reasons += "Battery Saver is enabled. Turn it off before starting a long local image-generation task."
        }

        return Assessment(
            allowed = reasons.isEmpty(),
            reasons = reasons,
            totalRamBytes = memory.totalMem,
            availableRamBytes = memory.availMem,
            availableStorageBytes = storage,
            thermalStatus = thermal
        )
    }

    /**
     * Less restrictive checkpoints after model allocation. If Android reports
     * low memory, the free headroom falls below 2 GB, or thermal status rises
     * to MODERATE, the next diffusion step is not started.
     */
    fun assertSafeToContinue(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        if (memory.lowMemory || memory.availMem < MIN_AVAILABLE_DURING_RUN_BYTES) {
            throw IllegalStateException(
                "SruGen paused generation because available system memory fell below its 2 GB safety reserve. " +
                    "Your prompt and encrypted chat remain saved; close other apps and retry after memory recovers."
            )
        }
        val thermal = currentThermalStatus(context)
        if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_MODERATE) {
            throw IllegalStateException(
                "SruGen stopped before the next generation step because the phone became too warm. " +
                    "Let it cool; your encrypted chat remains saved."
            )
        }
    }

    fun requireSafeToStart(context: Context) {
        val result = assessBeforeGeneration(context)
        check(result.allowed) { result.reasons.joinToString("\n") }
    }

    private fun currentThermalStatus(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        }.getOrNull()
    }
}
