package `in`.nispax.sruvik.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `in`.nispax.sruvik.ui.theme.SruvikColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

private data class DeviceSnapshot(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val lowMemory: Boolean,
    val lowMemoryThresholdBytes: Long,
    val appPssMiB: Int,
    val appCpuPercent: Float,
    val dataVolumeTotalBytes: Long,
    val dataVolumeAvailableBytes: Long,
    val batteryPercent: Int?,
    val batteryState: String,
    val batteryTemperatureC: Float?,
    val thermalStatus: String,
    val rxBytesPerSecond: Long?,
    val txBytesPerSecond: Long?,
    val sampledAtElapsed: Long
)

private data class MonitorUiState(
    val snapshot: DeviceSnapshot? = null,
    val error: String? = null
)

@Composable
fun DeviceMonitorPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var state by remember { mutableStateOf(MonitorUiState()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        var previousCpu = Process.getElapsedCpuTime()
        var previousWall = SystemClock.elapsedRealtime()
        var previousRx = TrafficStats.getUidRxBytes(Process.myUid())
        var previousTx = TrafficStats.getUidTxBytes(Process.myUid())
        while (isActive) {
            try {
                val nowWall = SystemClock.elapsedRealtime()
                val nowCpu = Process.getElapsedCpuTime()
                val wallDelta = (nowWall - previousWall).coerceAtLeast(1L)
                val cpuDelta = (nowCpu - previousCpu).coerceAtLeast(0L)
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val appCpu = InstrumentMath.processCpuSharePercent(cpuDelta, wallDelta, cores)

                val rx = TrafficStats.getUidRxBytes(Process.myUid())
                val tx = TrafficStats.getUidTxBytes(Process.myUid())
                val rxRate = if (rx >= 0L && previousRx >= 0L) InstrumentMath.bytesPerSecond(rx - previousRx, wallDelta) else null
                val txRate = if (tx >= 0L && previousTx >= 0L) InstrumentMath.bytesPerSecond(tx - previousTx, wallDelta) else null

                state = MonitorUiState(snapshot = readDeviceSnapshot(context, appCpu, rxRate, txRate))
                previousCpu = nowCpu
                previousWall = nowWall
                previousRx = rx
                previousTx = tx
                delay(2500)
            } catch (failure: Exception) {
                state = MonitorUiState(
                    snapshot = state.snapshot,
                    error = failure.message ?: "Android did not return a resource snapshot."
                )
                delay(5000)
            }
        }
    }

    val data = state.snapshot
    val sampleAgeSeconds = data?.let {
        ((SystemClock.elapsedRealtime() - it.sampledAtElapsed).coerceAtLeast(0L) / 1000L)
    }
    val lowFraction = data?.let {
        if (it.totalRamBytes > 0L) it.lowMemoryThresholdBytes.toFloat() / it.totalRamBytes.toFloat() else 0f
    } ?: 0f
    val ramBands = if (lowFraction > 0f) {
        listOf(
            MeterBand(0f, lowFraction.coerceIn(0f, 1f), ObsidianTokens.Amber),
            MeterBand(lowFraction.coerceIn(0f, 1f), 1f, ObsidianTokens.Cyan)
        )
    } else {
        listOf(MeterBand(0f, 1f, ObsidianTokens.Cyan))
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 980.dp).fillMaxWidth().fillMaxHeight(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SYSTEM / TELEMETRY", style = MaterialTheme.typography.labelSmall,
                        color = ObsidianTokens.Cyan, letterSpacing = 1.7.sp)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.clip(CircleShape).background(
                        (if (data != null && state.error == null) ObsidianTokens.Cyan else ObsidianTokens.Amber).copy(alpha = .12f)
                    ).border(1.dp, ObsidianTokens.Border, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            when {
                                !resumed -> "PAUSED"
                                state.error != null && data != null -> "STALE"
                                data != null -> "LIVE · 2.5 S"
                                else -> "SAMPLING"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (data != null && state.error == null) ObsidianTokens.Cyan else ObsidianTokens.Amber
                        )
                    }
                }
                Text("Device instruments", style = MaterialTheme.typography.displaySmall,
                    color = ObsidianTokens.TextPrimary)
                Text("A precise view of system availability and this app's footprint.",
                    style = MaterialTheme.typography.bodyMedium, color = ObsidianTokens.TextSecondary)
            }
        }

        item {
            GlassInstrumentPanel {
                if (data == null) {
                    MonitorPlaceholder(if (state.error != null) "Memory snapshot unavailable" else "Reading Android memory…",
                        state.error ?: "Waiting for the first system sample.")
                } else {
                    SegmentedDial(
                        title = "Device-wide available RAM",
                        valueText = "${formatBytes(data.availableRamBytes)} / ${formatBytes(data.totalRamBytes)}",
                        detail = if (data.lowMemory) "Android reports low memory" else "Available · includes reclaimable memory",
                        value = data.availableRamBytes.toDouble(),
                        min = 0.0,
                        max = data.totalRamBytes.coerceAtLeast(1L).toDouble(),
                        minLabel = "0 available",
                        maxLabel = formatBytes(data.totalRamBytes),
                        bands = ramBands,
                        status = when {
                            !resumed -> "Paused · last sample ${sampleAgeSeconds ?: 0L}s old"
                            state.error != null -> "Stale · last good sample ${sampleAgeSeconds ?: 0L}s old"
                            data.lowMemory -> "Android low-memory signal"
                            else -> "Live · Android MemoryInfo"
                        }
                    )
                    Text("Estimated in use: ${formatBytes(InstrumentMath.estimatedUsedBytes(data.totalRamBytes, data.availableRamBytes))}. Android totalMem is kernel-accessible memory and may differ from the advertised RAM figure; available memory includes reclaimable pages. The in-use value is a derived estimate, not a sum of app allocations.",
                        style = MaterialTheme.typography.bodySmall, color = ObsidianTokens.TextMuted)
                }
            }
        }

        item {
            GlassInstrumentPanel {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelHeading("APP PROCESS", "Only Sruvik's own process")
                    if (data == null) MonitorPlaceholder("App memory pending", "PSS is sampled from Debug.MemoryInfo.")
                    else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${data.appPssMiB} MiB", style = MaterialTheme.typography.headlineMedium,
                                    color = ObsidianTokens.TextPrimary)
                                Text("App PSS · proportional set size", style = MaterialTheme.typography.bodySmall,
                                    color = ObsidianTokens.TextSecondary)
                            }
                            Icon(Icons.Rounded.Memory, null, tint = ObsidianTokens.Violet, modifier = Modifier.size(25.dp))
                        }
                        InstrumentLinearMeter("App CPU share", String.format(Locale.US, "%.1f%%", data.appCpuPercent),
                            data.appCpuPercent / 100f,
                            "Process CPU time normalised to total available core capacity.",
                            accent = ObsidianTokens.Cyan)
                    }
                }
            }
        }

        item {
            GlassInstrumentPanel {
                Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    PanelHeading("STORAGE", "Filesystem scope · /data volume")
                    if (data == null) MonitorPlaceholder("Storage pending", "Reading the Android data volume.")
                    else {
                        val used = (data.dataVolumeTotalBytes - data.dataVolumeAvailableBytes).coerceAtLeast(0L)
                        InstrumentLinearMeter("Data volume used", "${percent(used, data.dataVolumeTotalBytes)}%",
                            safeFraction(used, data.dataVolumeTotalBytes),
                            "${formatBytes(used)} used · ${formatBytes(data.dataVolumeAvailableBytes)} available")
                    }
                }
            }
        }

        item {
            GlassInstrumentPanel {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelHeading("POWER & THERMAL", "Platform-reported state")
                    if (data == null) MonitorPlaceholder("Power state pending", "Reading battery and thermal services.")
                    else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricColumn(Modifier.weight(1f), Icons.Rounded.BatteryChargingFull,
                                "Battery", data.batteryPercent?.let { "$it%" } ?: "Unavailable",
                                data.batteryState + (data.batteryTemperatureC?.let { " · ${String.format(Locale.US, "%.1f°C", it)} battery sensor" } ?: ""))
                            MetricColumn(Modifier.weight(1f), Icons.Rounded.Thermostat,
                                "Thermal status", data.thermalStatus,
                                "Android thermal severity; not a CPU temperature.")
                        }
                        InstrumentLinearMeter(
                            "Battery charge",
                            data.batteryPercent?.let { "$it%" } ?: "Unavailable",
                            data.batteryPercent?.let { it / 100f },
                            "Charge level only; battery health/capacity is not inferred.",
                            accent = ObsidianTokens.Cyan
                        )
                    }
                }
            }
        }

        item {
            GlassInstrumentPanel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelHeading("NETWORK", "This app UID · not device-wide traffic")
                    if (data == null) MonitorPlaceholder("Network counters pending", "Waiting for a second sample.")
                    else if (data.rxBytesPerSecond == null || data.txBytesPerSecond == null) {
                        Text("Per-app byte counters are unavailable on this device.", color = ObsidianTokens.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricColumn(Modifier.weight(1f), Icons.Rounded.NetworkCheck, "Download",
                                "${formatRate(data.rxBytesPerSecond)}", "App receive rate")
                            MetricColumn(Modifier.weight(1f), Icons.Rounded.NetworkCheck, "Upload",
                                "${formatRate(data.txBytesPerSecond)}", "App transmit rate")
                        }
                    }
                }
            }
        }

        item {
            Text(
                state.error?.let { "Last sample failed: $it" }
                    ?: "Samples are collected only while this page is resumed. CPU and network readings are scoped to this app's process/UID. Ordinary apps do not have a reliable public whole-device CPU percentage or unrestricted third-party process list.",
                style = MaterialTheme.typography.bodySmall,
                color = ObsidianTokens.TextMuted,
                modifier = Modifier.padding(horizontal = 3.dp)
            )
        }
    }
    }
}

@Composable
private fun GlassInstrumentPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xE61A202D), Color(0xF210131C), Color(0xE8171B29))),
                RoundedCornerShape(22.dp)
            )
            .border(androidx.compose.foundation.BorderStroke(1.dp, Brush.linearGradient(listOf(Color(0x667E91B8), Color(0x223C4A64), Color(0x556BDAE8)))),
                RoundedCornerShape(22.dp))
            .padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
private fun PanelHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = ObsidianTokens.Violet, letterSpacing = 1.4.sp)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ObsidianTokens.TextMuted)
    }
}

@Composable
private fun MonitorPlaceholder(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = ObsidianTokens.TextPrimary)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = ObsidianTokens.TextSecondary)
    }
}

@Composable
private fun MetricColumn(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    detail: String
) {
    Column(modifier = modifier.background(Color(0x551D2431), RoundedCornerShape(14.dp))
        .border(1.dp, Color(0x333D4A61), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = ObsidianTokens.Cyan, modifier = Modifier.size(15.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, color = ObsidianTokens.TextMuted)
        }
        Text(value, style = MaterialTheme.typography.titleLarge, color = ObsidianTokens.TextPrimary)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = ObsidianTokens.TextSecondary)
    }
}

private fun readDeviceSnapshot(
    context: Context,
    appCpuPercent: Float,
    rxRate: Long?,
    txRate: Long?
): DeviceSnapshot {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(memory)
    val totalRam = memory.totalMem.coerceAtLeast(0L)
    val availableRam = memory.availMem.coerceIn(0L, totalRam)

    val processMemory = Debug.MemoryInfo()
    Debug.getMemoryInfo(processMemory)

    val stat = StatFs(Environment.getDataDirectory().absolutePath)
    val volumeTotal = stat.totalBytes.coerceAtLeast(0L)
    val volumeAvailable = stat.availableBytes.coerceIn(0L, volumeTotal)

    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val batteryState = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Status unavailable"
    }
    val rawTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val batteryTemp = if (rawTemp >= 0) rawTemp / 10f else null

    val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "None"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }
    } else "Unsupported on this Android version"

    return DeviceSnapshot(
        totalRamBytes = totalRam,
        availableRamBytes = availableRam,
        lowMemory = memory.lowMemory,
        lowMemoryThresholdBytes = memory.threshold.coerceAtLeast(0L),
        appPssMiB = (processMemory.totalPss / 1024).coerceAtLeast(0),
        appCpuPercent = appCpuPercent,
        dataVolumeTotalBytes = volumeTotal,
        dataVolumeAvailableBytes = volumeAvailable,
        batteryPercent = batteryPercent,
        batteryState = batteryState,
        batteryTemperatureC = batteryTemp,
        thermalStatus = thermal,
        rxBytesPerSecond = rxRate,
        txBytesPerSecond = txRate,
        sampledAtElapsed = SystemClock.elapsedRealtime()
    )
}

private fun safeFraction(value: Long, total: Long): Float? =
    if (total <= 0L) null else (value.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)

private fun percent(value: Long, total: Long): Int =
    if (total <= 0L) 0 else (value.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(0, 100)

private fun formatBytes(bytes: Long): String {
    val gib = 1024.0 * 1024.0 * 1024.0
    val mib = 1024.0 * 1024.0
    return when {
        bytes >= gib -> String.format(Locale.US, "%.1f GiB", bytes / gib)
        bytes >= mib -> String.format(Locale.US, "%.0f MiB", bytes / mib)
        else -> String.format(Locale.US, "%.0f KiB", bytes / 1024.0)
    }
}

private fun formatRate(bytesPerSecond: Long): String {
    val kib = 1024.0
    val mib = kib * 1024.0
    return when {
        bytesPerSecond >= mib -> String.format(Locale.US, "%.1f MiB/s", bytesPerSecond / mib)
        bytesPerSecond >= kib -> String.format(Locale.US, "%.0f KiB/s", bytesPerSecond / kib)
        else -> "$bytesPerSecond B/s"
    }
}
