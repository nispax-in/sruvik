package `in`.nispax.sruvik.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal data class MeterBand(
    val startFraction: Float,
    val endFraction: Float,
    val color: Color
)

internal object InstrumentMath {
    fun fraction(value: Double, min: Double, max: Double): Float {
        if (!value.isFinite() || !min.isFinite() || !max.isFinite() || max <= min) return 0f
        return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    }

    fun angle(value: Double, min: Double, max: Double): Float =
        180f + fraction(value, min, max) * 180f

    fun processCpuSharePercent(cpuMillisDelta: Long, wallMillisDelta: Long, coreCount: Int): Float {
        if (wallMillisDelta <= 0L || coreCount <= 0 || cpuMillisDelta < 0L) return 0f
        return (cpuMillisDelta.toDouble() / wallMillisDelta.toDouble() * 100.0 / coreCount)
            .toFloat().coerceIn(0f, 100f)
    }

    fun bytesPerSecond(byteDelta: Long, intervalMillis: Long): Long? {
        if (byteDelta < 0L || intervalMillis <= 0L) return null
        return (byteDelta.toDouble() * 1000.0 / intervalMillis.toDouble())
            .coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
    }

    fun estimatedUsedBytes(totalBytes: Long, availableBytes: Long): Long {
        val total = totalBytes.coerceAtLeast(0L)
        val available = availableBytes.coerceIn(0L, total)
        return (total - available).coerceAtLeast(0L)
    }
}

@Composable
internal fun SegmentedDial(
    title: String,
    valueText: String,
    detail: String,
    value: Double?,
    min: Double,
    max: Double,
    minLabel: String,
    maxLabel: String,
    bands: List<MeterBand>,
    status: String = "Live",
    modifier: Modifier = Modifier
) {
    val rawFraction = value?.let { InstrumentMath.fraction(it, min, max) } ?: 0f
    val displayedFraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(420),
        label = "instrument-needle"
    )
    val accessible = buildString {
        append(title).append(", ").append(valueText).append(". ").append(detail)
        append(". ").append(status)
    }

    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = accessible
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ObsidianTokens.TextMuted,
            letterSpacing = 1.25.sp
        )
        Canvas(
            Modifier.fillMaxWidth().height(142.dp)
                .padding(horizontal = 8.dp, vertical = 1.dp)
        ) {
            val stroke = 13.dp.toPx()
            val radius = min(size.width / 2f - stroke, size.height * .91f - stroke / 2f)
                .coerceAtLeast(1f)
            val center = Offset(size.width / 2f, size.height * .93f)
            val arcSize = Size(radius * 2f, radius * 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val count = 36
            val gap = 1.35f
            val segmentSweep = (180f - gap * (count - 1)) / count

            for (index in 0 until count) {
                val startFraction = index.toFloat() / count
                val endFraction = (index + 1f) / count
                val band = bands.firstOrNull {
                    (startFraction + endFraction) / 2f >= it.startFraction &&
                        (startFraction + endFraction) / 2f < it.endFraction
                }
                val zoneColor = band?.color ?: ObsidianTokens.Cyan
                val active = value != null && endFraction <= displayedFraction + .0001f
                val segmentStart = 180f + index * (segmentSweep + gap)
                if (active) {
                    drawArc(
                        color = zoneColor.copy(alpha = .15f),
                        startAngle = segmentStart,
                        sweepAngle = segmentSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke + 8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                drawArc(
                    color = if (active) zoneColor else zoneColor.copy(alpha = .25f),
                    startAngle = segmentStart,
                    sweepAngle = segmentSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
            }

            // Calibrated major ticks, intentionally independent of the decorative segments.
            for (index in 0..4) {
                val angle = (180.0 + index * 45.0) * PI / 180.0
                val outer = radius - stroke * 1.0f
                val inner = outer - if (index == 0 || index == 2 || index == 4) 8.dp.toPx() else 4.dp.toPx()
                val start = Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner)
                val end = Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer)
                drawLine(Color(0xFF8793A6), start, end, strokeWidth = if (index % 2 == 0) 1.5.dp.toPx() else 1.dp.toPx())
            }

            if (value != null) {
                val angle = (180.0 + displayedFraction * 180.0) * PI / 180.0
                val needleLength = radius - 18.dp.toPx()
                val tip = Offset(
                    center.x + cos(angle).toFloat() * needleLength,
                    center.y + sin(angle).toFloat() * needleLength
                )
                drawLine(Color(0xFF080B11), center, tip, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Color(0xFFF3F6FC), center, tip, strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Color(0xFF090C13), radius = 8.dp.toPx(), center = center)
                drawCircle(Color(0xFFE8EDF6), radius = 3.3.dp.toPx(), center = center)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(minLabel, style = MaterialTheme.typography.labelSmall, color = ObsidianTokens.TextMuted)
            Text(maxLabel, style = MaterialTheme.typography.labelSmall, color = ObsidianTokens.TextMuted)
        }
        Text(
            valueText,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = ObsidianTokens.TextPrimary
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = ObsidianTokens.TextSecondary)
        Text(status.uppercase(), style = MaterialTheme.typography.labelSmall, color = ObsidianTokens.Cyan.copy(alpha = .85f), letterSpacing = .8.sp)
    }
}

@Composable
internal fun InstrumentSparkline(
    values: List<Long>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.fillMaxWidth().height(27.dp)) {
        drawLine(
            color = Color(0xFF303849),
            start = Offset(0f, size.height - 1.dp.toPx()),
            end = Offset(size.width, size.height - 1.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
        if (values.size < 2) return@Canvas
        val low = values.minOrNull()?.toFloat() ?: 0f
        val high = values.maxOrNull()?.toFloat() ?: 0f
        val span = (high - low).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1).toFloat()
        val points = values.mapIndexed { index, value ->
            Offset(
                index * stepX,
                size.height - 3.dp.toPx() - ((value.toFloat() - low) / span) * (size.height - 6.dp.toPx())
            )
        }
        points.zipWithNext().forEach { (start, end) ->
            drawLine(accent.copy(alpha = .18f), start, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(accent, start, end, strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
        }
        points.lastOrNull()?.let { drawCircle(accent, radius = 2.5.dp.toPx(), center = it) }
    }
}

@Composable
internal fun InstrumentLinearMeter(
    title: String,
    valueText: String,
    fraction: Float?,
    detail: String,
    accent: Color = ObsidianTokens.Violet,
    modifier: Modifier = Modifier
) {
    val target = fraction?.coerceIn(0f, 1f) ?: 0f
    val animated by animateFloatAsState(target, animationSpec = tween(380), label = "linear-instrument")
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = ObsidianTokens.TextSecondary)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.titleMedium, color = ObsidianTokens.TextPrimary)
        }
        Canvas(Modifier.fillMaxWidth().height(13.dp)) {
            val segments = 30
            val gap = 3.dp.toPx()
            val width = (size.width - gap * (segments - 1)) / segments
            for (i in 0 until segments) {
                val left = i * (width + gap)
                val active = fraction != null && (i + 1f) / segments <= animated + .0001f
                drawRoundRect(
                    color = if (active) accent else Color(0xFF242A34),
                    topLeft = Offset(left, 1.dp.toPx()),
                    size = Size(width, size.height - 2.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = ObsidianTokens.TextMuted)
    }
}

internal object ObsidianTokens {
    val TextPrimary = Color(0xFFF0F2F8)
    val TextSecondary = Color(0xFFB0B8C9)
    val TextMuted = Color(0xFF7E899E)
    val Cyan = Color(0xFF75E3F3)
    val Violet = Color(0xFFA99BFF)
    val Amber = Color(0xFFF1B86D)
    val Rose = Color(0xFFFF829B)
    val Surface = Color(0xFF151923)
    val Border = Color(0xFF343B4B)
}
