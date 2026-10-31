package `in`.nispax.sruvik.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.security.MessageDigest
import java.util.Locale

private data class AvatarPalette(
    val background: Color,
    val deep: Color,
    val primary: Color,
    val secondary: Color,
    val glow: Color
)

private val avatarPalettes = listOf(
    AvatarPalette(Color(0xFF242052), Color(0xFF10152E), Color(0xFFB99BFF), Color(0xFF5DE1FF), Color(0xFFFFC6F4)),
    AvatarPalette(Color(0xFF152D58), Color(0xFF10172E), Color(0xFF73C8FF), Color(0xFF9C8BFF), Color(0xFFFFD4A2)),
    AvatarPalette(Color(0xFF40204F), Color(0xFF17152F), Color(0xFFFFA8DC), Color(0xFF9B8BFF), Color(0xFFFFD59A)),
    AvatarPalette(Color(0xFF163B47), Color(0xFF101A31), Color(0xFF65E6D2), Color(0xFF82B9FF), Color(0xFFD6FFB1)),
    AvatarPalette(Color(0xFF3B2D60), Color(0xFF15162F), Color(0xFFC2A3FF), Color(0xFFFFBAE6), Color(0xFF80E7FF)),
    AvatarPalette(Color(0xFF4A2D36), Color(0xFF1C1830), Color(0xFFFFB77E), Color(0xFFFF82BB), Color(0xFFB4A0FF)),
    AvatarPalette(Color(0xFF1C3157), Color(0xFF10172D), Color(0xFF6FAEFF), Color(0xFF75E0FF), Color(0xFFFFC7EE)),
    AvatarPalette(Color(0xFF3D285C), Color(0xFF15142D), Color(0xFFFFC2E8), Color(0xFF9F8CFF), Color(0xFFFFD58A))
)

/**
 * Creates a stable, Sruvik-branded avatar entirely on-device.
 * The SHA-256 seed means the same normalized name always maps to the same
 * motif, palette and decorative details. No network or account is involved.
 */
@Composable
fun SruvikAvatar(name: String, modifier: Modifier = Modifier) {
    val label = name.trim().ifBlank { "Sruvik" }
    val seed = remember(label) {
        MessageDigest.getInstance("SHA-256")
            .digest(label.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
    }
    val motif = (seed[0].toInt() and 0xFF) % 12
    val palette = avatarPalettes[(seed[1].toInt() and 0xFF) % avatarPalettes.size]
    val variant = seed[2].toInt() and 0xFF

    Canvas(modifier.semantics { contentDescription = "$label's Sruvik avatar" }) {
        val d = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val unit = d / 100f
        val ring = Brush.sweepGradient(
            listOf(palette.primary, palette.glow, palette.secondary, palette.primary),
            center = c
        )

        drawCircle(
            brush = Brush.radialGradient(
                listOf(palette.background, palette.deep),
                center = c,
                radius = d * .72f
            ),
            radius = d * .49f,
            center = c
        )
        drawCircle(
            brush = ring,
            radius = d * .455f,
            center = c,
            style = Stroke(width = unit * 1.5f)
        )
        drawCircle(
            color = Color.White.copy(alpha = .09f),
            radius = d * .385f,
            center = c,
            style = Stroke(width = unit * .8f)
        )

        rotate((variant % 360).toFloat(), pivot = c) {
            when (motif) {
                0 -> { // Four-point star / Sruvik spark
                    val star = Path().apply {
                        moveTo(c.x, c.y - d * .30f)
                        quadraticTo(c.x + d * .06f, c.y - d * .06f, c.x + d * .29f, c.y)
                        quadraticTo(c.x + d * .06f, c.y + d * .06f, c.x, c.y + d * .30f)
                        quadraticTo(c.x - d * .06f, c.y + d * .06f, c.x - d * .29f, c.y)
                        quadraticTo(c.x - d * .06f, c.y - d * .06f, c.x, c.y - d * .30f)
                        close()
                    }
                    drawPath(star, Brush.linearGradient(listOf(palette.glow, palette.primary, palette.secondary)))
                    drawCircle(palette.glow, d * .045f, c)
                }
                1 -> { // Faceted crystal
                    val crystal = Path().apply {
                        moveTo(c.x, c.y - d * .30f)
                        lineTo(c.x + d * .22f, c.y - d * .02f)
                        lineTo(c.x, c.y + d * .30f)
                        lineTo(c.x - d * .22f, c.y - d * .02f)
                        close()
                    }
                    drawPath(crystal, Brush.linearGradient(listOf(palette.glow, palette.primary, palette.secondary)))
                    drawLine(palette.deep.copy(alpha = .75f), Offset(c.x, c.y - d * .30f), Offset(c.x, c.y + d * .30f), unit * 1.1f)
                    drawLine(palette.deep.copy(alpha = .75f), Offset(c.x - d * .22f, c.y - d * .02f), Offset(c.x + d * .22f, c.y - d * .02f), unit * 1.1f)
                }
                2 -> { // Crescent moon
                    drawCircle(palette.primary, d * .25f, Offset(c.x - d * .035f, c.y))
                    drawCircle(palette.deep, d * .235f, Offset(c.x + d * .095f, c.y - d * .065f))
                    drawCircle(palette.glow, d * .035f, Offset(c.x + d * .18f, c.y - d * .20f))
                }
                3 -> { // Orbital world
                    drawOval(palette.secondary.copy(alpha = .9f),
                        topLeft = Offset(c.x - d * .34f, c.y - d * .105f),
                        size = Size(d * .68f, d * .21f), style = Stroke(unit * 3.1f))
                    drawCircle(Brush.radialGradient(listOf(palette.glow, palette.primary, palette.deep)),
                        d * .19f, c)
                    drawCircle(palette.glow, d * .035f, Offset(c.x + d * .28f, c.y - d * .10f))
                }
                4 -> { // Six-petal bloom
                    repeat(6) { i ->
                        rotate(i * 60f, pivot = c) {
                            drawOval(
                                Brush.linearGradient(listOf(palette.glow, palette.primary)),
                                topLeft = Offset(c.x - d * .075f, c.y - d * .31f),
                                size = Size(d * .15f, d * .24f)
                            )
                        }
                    }
                    drawCircle(palette.secondary, d * .095f, c)
                    drawCircle(palette.glow, d * .035f, c)
                }
                5 -> { // Flowing wave
                    val wave = Path().apply {
                        moveTo(c.x - d * .30f, c.y + d * .13f)
                        cubicTo(c.x - d * .17f, c.y - d * .29f, c.x + d * .02f, c.y + d * .32f, c.x + d * .29f, c.y - d * .12f)
                    }
                    drawPath(wave, palette.primary, style = Stroke(d * .105f, cap = StrokeCap.Round))
                    val wave2 = Path().apply {
                        moveTo(c.x - d * .25f, c.y + d * .24f)
                        cubicTo(c.x - d * .02f, c.y - d * .12f, c.x + d * .10f, c.y + d * .22f, c.x + d * .28f, c.y - d * .23f)
                    }
                    drawPath(wave2, palette.glow, style = Stroke(d * .045f, cap = StrokeCap.Round))
                }
                6 -> { // Friendly little bot
                    drawLine(palette.glow, Offset(c.x, c.y - d * .22f), Offset(c.x, c.y - d * .32f), unit * 3f, StrokeCap.Round)
                    drawCircle(palette.glow, d * .045f, Offset(c.x, c.y - d * .34f))
                    drawRoundRect(
                        Brush.linearGradient(listOf(palette.primary, palette.secondary)),
                        topLeft = Offset(c.x - d * .245f, c.y - d * .20f),
                        size = Size(d * .49f, d * .42f),
                        cornerRadius = CornerRadius(d * .12f)
                    )
                    drawCircle(palette.deep, d * .04f, Offset(c.x - d * .10f, c.y - d * .01f))
                    drawCircle(palette.deep, d * .04f, Offset(c.x + d * .10f, c.y - d * .01f))
                    drawLine(palette.deep, Offset(c.x - d * .08f, c.y + d * .12f), Offset(c.x + d * .08f, c.y + d * .12f), unit * 2f, StrokeCap.Round)
                }
                7 -> { // Mountain peaks
                    val mountain = Path().apply {
                        moveTo(c.x - d * .32f, c.y + d * .22f)
                        lineTo(c.x - d * .04f, c.y - d * .25f)
                        lineTo(c.x + d * .10f, c.y - d * .03f)
                        lineTo(c.x + d * .20f, c.y - d * .19f)
                        lineTo(c.x + d * .34f, c.y + d * .22f)
                        close()
                    }
                    drawPath(mountain, Brush.linearGradient(listOf(palette.secondary, palette.primary)))
                    drawLine(palette.glow, Offset(c.x - d * .12f, c.y - d * .11f), Offset(c.x - d * .04f, c.y - d * .25f), unit * 2f, StrokeCap.Round)
                    drawLine(palette.glow, Offset(c.x - d * .04f, c.y - d * .25f), Offset(c.x + d * .045f, c.y - d * .12f), unit * 2f, StrokeCap.Round)
                }
                8 -> { // Comet
                    val tail = Path().apply {
                        moveTo(c.x - d * .29f, c.y + d * .23f)
                        cubicTo(c.x - d * .05f, c.y + d * .04f, c.x + d * .02f, c.y - d * .10f, c.x + d * .24f, c.y - d * .25f)
                    }
                    drawPath(tail, palette.secondary, style = Stroke(d * .13f, cap = StrokeCap.Round))
                    drawCircle(Brush.radialGradient(listOf(palette.glow, palette.primary)), d * .13f,
                        Offset(c.x + d * .20f, c.y - d * .20f))
                }
                9 -> { // Infinity loop
                    val loop = Path().apply {
                        moveTo(c.x, c.y)
                        cubicTo(c.x - d * .35f, c.y - d * .38f, c.x - d * .38f, c.y + d * .32f, c.x, c.y)
                        cubicTo(c.x + d * .38f, c.y - d * .32f, c.x + d * .35f, c.y + d * .38f, c.x, c.y)
                    }
                    drawPath(loop, palette.primary, style = Stroke(d * .09f, cap = StrokeCap.Round))
                    drawCircle(palette.glow, d * .045f, c)
                }
                10 -> { // Radiant sun
                    repeat(10) { i ->
                        val angle = Math.toRadians(i * 36.0)
                        val inner = d * .19f
                        val outer = d * if (i % 2 == 0) .32f else .28f
                        drawLine(
                            palette.secondary,
                            Offset(c.x + kotlin.math.cos(angle).toFloat() * inner, c.y + kotlin.math.sin(angle).toFloat() * inner),
                            Offset(c.x + kotlin.math.cos(angle).toFloat() * outer, c.y + kotlin.math.sin(angle).toFloat() * outer),
                            unit * 2.5f, StrokeCap.Round
                        )
                    }
                    drawCircle(Brush.radialGradient(listOf(palette.glow, palette.primary)), d * .17f, c)
                }
                else -> { // Layered geometric portal
                    drawCircle(palette.secondary, d * .28f, c, style = Stroke(d * .055f))
                    drawCircle(palette.primary, d * .19f, c, style = Stroke(d * .055f))
                    drawCircle(palette.glow, d * .075f, c)
                    drawLine(palette.glow, Offset(c.x - d * .25f, c.y + d * .20f),
                        Offset(c.x + d * .25f, c.y - d * .20f), unit * 2f, StrokeCap.Round)
                }
            }
        }

        // Tiny constellation details are also name-seeded.
        repeat(3) { i ->
            val rawX = (seed[4 + i * 2].toInt() and 0xFF) / 255f
            val rawY = (seed[5 + i * 2].toInt() and 0xFF) / 255f
            val x = size.width * (.18f + rawX * .64f)
            val y = size.height * (.18f + rawY * .64f)
            drawCircle(palette.glow.copy(alpha = .75f), d * .018f, Offset(x, y))
        }
    }
}
