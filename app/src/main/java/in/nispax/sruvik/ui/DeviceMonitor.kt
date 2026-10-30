package `in`.nispax.sruvik.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val appRamMb: Int,
    val cpuSharePercent: Float,
    val storageUsedBytes: Long,
    val storageTotalBytes: Long,
    val batteryPercent: Int?,
    val batteryState: String,
    val batteryTemperatureC: Float?,
    val thermalStatus: String
)

@Composable
fun DeviceMonitorPage() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var snapshot by remember { mutableStateOf<DeviceSnapshot?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isResumed) {
        if (isResumed) {
            var previousCpuMs = Process.getElapsedCpuTime()
            var previousWallMs = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(2_000)
                val nowWallMs = SystemClock.elapsedRealtime()
                val nowCpuMs = Process.getElapsedCpuTime()
                val wallDelta = (nowWallMs - previousWallMs).coerceAtLeast(1L)
                val cpuDelta = (nowCpuMs - previousCpuMs).coerceAtLeast(0L)
                val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val appCpuShare = (
                    cpuDelta.toFloat() / wallDelta.toFloat() * 100f / coreCount
                ).coerceIn(0f, 100f)

                snapshot = readDeviceSnapshot(context, appCpuShare)
                previousCpuMs = nowCpuMs
                previousWallMs = nowWallMs
            }
        }
    }

    val liveTransition = rememberInfiniteTransition(label = "live-indicator")
    val liveAlpha by liveTransition.animateFloat(
        initialValue = .62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1150), repeatMode = RepeatMode.Reverse),
        label = "live-indicator-alpha"
    )

    val data = snapshot
    val ramPercent = data?.let { safePercent(it.ramUsedBytes, it.ramTotalBytes) } ?: 0f
    val storagePercent = data?.let { safePercent(it.storageUsedBytes, it.storageTotalBytes) } ?: 0f

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            MonitorEntrance(index = 0) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "DEVICE HEALTH",
                        style = MaterialTheme.typography.labelLarge,
                        color = SruvikColors.LimeMuted,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(SruvikColors.Lime.copy(alpha = .12f))
                            .border(1.dp, SruvikColors.Lime.copy(alpha = .22f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(SruvikColors.Lime.copy(alpha = liveAlpha)))
                            Text("LIVE · 2 SEC", style = MaterialTheme.typography.labelSmall, color = SruvikColors.LimeMuted)
                        }
                    }
                }
                Text("Resource monitor", style = MaterialTheme.typography.displayLarge, color = SruvikColors.TextPrimary)
                Text(
                    "A live view of how your device and Sruvik are using their resources.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SruvikColors.TextSecondary
                )
            }
            }
        }
        item {
            MonitorEntrance(index = 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MonitorCard(
                    modifier = Modifier.weight(1f),
                    title = "DEVICE RAM",
                    icon = Icons.Rounded.Memory,
                    value = data?.let { "${formatGiB(it.ramUsedBytes)} / ${formatGiB(it.ramTotalBytes)}" } ?: "Sampling…",
                    detail = data?.let { "${ramPercent.toInt()}% of system memory used" } ?: "Reading system memory",
                    progress = if (data == null) null else ramPercent / 100f
                )
                MonitorCard(
                    modifier = Modifier.weight(1f),
                    title = "APP CPU",
                    icon = Icons.Rounded.Speed,
                    value = data?.let { String.format(Locale.US, "%.1f%%", it.cpuSharePercent) } ?: "—",
                    detail = "Sruvik share of total CPU capacity",
                    progress = data?.cpuSharePercent?.div(100f)
                )
            }
            }
        }
        item {
            MonitorEntrance(index = 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MonitorCard(
                    modifier = Modifier.weight(1f),
                    title = "APP RAM",
                    icon = Icons.Rounded.Memory,
                    value = data?.let { "${it.appRamMb} MB" } ?: "—",
                    detail = "Memory attributed to Sruvik"
                )
                MonitorCard(
                    modifier = Modifier.weight(1f),
                    title = "INTERNAL STORAGE",
                    icon = Icons.Rounded.Storage,
                    value = data?.let { "${formatGiB(it.storageUsedBytes)} / ${formatGiB(it.storageTotalBytes)}" } ?: "—",
                    detail = data?.let { "${storagePercent.toInt()}% of data storage used" } ?: "Reading device storage",
                    progress = if (data == null) null else storagePercent / 100f
                )
            }
            }
        }
        item {
            MonitorEntrance(index = 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MonitorCard(
                    modifier = Modifier.weight(1f),
                    title = "BATTERY",
                    icon = Icons.Rounded.BatteryChargingFull,
                    value = data?.batteryPercent?.let { "$it%" } ?: "—",
                    detail = data?.let {
                        buildString {
                            append(it.batteryState)
                            it.batteryTemperatureC?.let { temp ->
                                append(" · ")
                                append(String.format(Locale.US, "%.1f°C", temp))
                            }
                        }
                    } ?: "Reading battery status"
                )
                MonitorCard(
                    modifier = Modifier.weight(1f),
                    title = "THERMAL STATUS",
                    icon = Icons.Rounded.Thermostat,
                    value = data?.thermalStatus ?: "—",
                    detail = "Android system thermal signal",
                    warning = data?.thermalStatus in listOf("Severe", "Critical", "Emergency", "Shutdown")
                )
            }
            }
        }
        item {
            MonitorEntrance(index = 4) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color(0x5530473A))
                    .border(1.dp, SruvikColors.GlassBorder, RoundedCornerShape(20.dp))
                    .padding(15.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Icon(Icons.Rounded.Info, null, tint = SruvikColors.LimeMuted, modifier = Modifier.size(19.dp))
                Text(
                    "CPU shows Sruvik's share of total multi-core capacity, sampled every 2 seconds. App RAM is its process memory (PSS). Thermal status comes from Android; the optional temperature reading is the battery sensor, not the CPU temperature.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SruvikColors.TextSecondary
                )
            }
            }
        }
        item {
            MonitorEntrance(index = 5) {
            Text(
                "Readings stay on this device. Monitoring pauses when this page is not active.",
                style = MaterialTheme.typography.labelSmall,
                color = SruvikColors.TextMuted,
                modifier = Modifier.padding(horizontal = 3.dp)
            )
            }
        }
    }
}

@Composable
private fun MonitorEntrance(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        delay(index * 75L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 16 }
    ) {
        content()
    }
}

@Composable
private fun MonitorCard(
    modifier: Modifier,
    title: String,
    icon: ImageVector,
    value: String,
    detail: String,
    progress: Float? = null,
    warning: Boolean = false
) {
    val valueColor = if (warning) SruvikColors.Danger else SruvikColors.TextPrimary
    Column(
        modifier = modifier.heightIn(min = 156.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Brush.linearGradient(listOf(Color(0x88384D3E), Color(0xAA17221C), Color(0xCC111A15))))
            .border(1.dp, SruvikColors.GlassBorder, RoundedCornerShape(23.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = SruvikColors.LimeMuted, modifier = Modifier.size(17.dp))
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.labelSmall, color = SruvikColors.TextMuted, letterSpacing = .8.sp)
        }
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (fadeIn(tween(190)) + slideInVertically(tween(190)) { it / 4 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 4 })
            },
            label = "monitor-value"
        ) { displayedValue ->
            Text(
                displayedValue,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = valueColor,
                maxLines = 1
            )
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary, minLines = 2)
        if (progress != null) {
            Spacer(Modifier.height(1.dp))
            Box(
                Modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = .09f))
            ) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progress.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 520),
                    label = "resource-progress"
                )
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight()
                        .graphicsLayer {
                            scaleX = animatedProgress
                            transformOrigin = TransformOrigin(0f, .5f)
                        }
                        .clip(CircleShape)
                        .background(if (warning) SruvikColors.Danger else SruvikColors.Lime)
                )
            }
        }
    }
}

private fun readDeviceSnapshot(context: Context, appCpuShare: Float): DeviceSnapshot {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)

    val appMemoryInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(appMemoryInfo)

    val stat = StatFs(Environment.getDataDirectory().absolutePath)
    val storageTotal = stat.totalBytes.coerceAtLeast(0L)
    val storageUsed = (storageTotal - stat.availableBytes).coerceIn(0L, storageTotal)

    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
    val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val batteryState = when (batteryStatus) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        else -> "On battery"
    }
    val rawBatteryTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val batteryTemp = if (rawBatteryTemp > 0) rawBatteryTemp / 10f else null

    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        when (powerManager.currentThermalStatus) {
            0 -> "Nominal"
            1 -> "Light"
            2 -> "Moderate"
            3 -> "Severe"
            4 -> "Critical"
            5 -> "Emergency"
            6 -> "Shutdown"
            else -> "Unknown"
        }
    } else {
        "Not exposed"
    }

    return DeviceSnapshot(
        ramUsedBytes = (memoryInfo.totalMem - memoryInfo.availMem).coerceAtLeast(0L),
        ramTotalBytes = memoryInfo.totalMem.coerceAtLeast(0L),
        appRamMb = (appMemoryInfo.totalPss / 1024).coerceAtLeast(0),
        cpuSharePercent = appCpuShare,
        storageUsedBytes = storageUsed,
        storageTotalBytes = storageTotal,
        batteryPercent = batteryPercent,
        batteryState = batteryState,
        batteryTemperatureC = batteryTemp,
        thermalStatus = thermalStatus
    )
}

private fun safePercent(used: Long, total: Long): Float {
    if (total <= 0L) return 0f
    return (used.toDouble() * 100.0 / total.toDouble()).toFloat().coerceIn(0f, 100f)
}

private fun formatGiB(bytes: Long): String =
    String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
