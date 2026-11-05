package `in`.nispax.sruvik.ui

import `in`.nispax.sruvik.R
import `in`.nispax.sruvik.ui.theme.SruvikColors
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * First-pass Obsidian Liquid Glass material study.
 *
 * This is intentionally scoped to Home while the rest of the application
 * retains its existing components and behaviour pending visual approval.
 */
@Composable
fun HomeScreenLiquidGlass(
    name: String,
    showDailyTip: Boolean,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onUpscale: () -> Unit,
    onSruGen: () -> Unit,
    onSrukrypt: () -> Unit,
    onDevice: () -> Unit
) {
    var greeting by remember { mutableStateOf(greetingForNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            greeting = greetingForNow()
        }
    }
    val dailyTip = remember {
        val tips = listOf(
            "Tiny steps count. A good idea becomes great when you keep iterating.",
            "A clean original is the best starting point for a great upscale.",
            "Try changing one creative detail at a time. It makes results easier to compare.",
            "Your device can do useful creative work without sending files to the cloud.",
            "Save a copy before experimenting. Curiosity works best with an undo plan."
        )
        tips[java.time.LocalDate.now().dayOfYear % tips.size]
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontal = if (maxWidth >= 600.dp) 30.dp else 20.dp
        val greetingFontSize = if (maxWidth < 380.dp) 29.sp else 33.sp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontal,
                end = horizontal,
                top = 18.dp,
                bottom = 116.dp
            ),
            verticalArrangement = Arrangement.spacedBy(25.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.sruvik_logo),
                        contentDescription = "Sruvik",
                        modifier = Modifier.size(43.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "SRUVIK",
                            color = Color(0xFFF4F3FA),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.6.sp
                        )
                        Text(
                            "PRIVATE CREATIVE SPACE",
                            color = Color(0xFFB4B8CB),
                            fontSize = 9.sp,
                            letterSpacing = 1.35.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Open profile and settings",
                                onClick = onSettings
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Reuse the user's assigned, name-derived Sruvik identity mark.
                        SruvikAvatar(name, Modifier.fillMaxSize())
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        greeting + ", " + name + ".",
                        color = Color(0xFFF4F3FA),
                        fontSize = greetingFontSize,
                        lineHeight = 39.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-.8).sp
                    )
                    Text(
                        "Make something only you could imagine.",
                        color = Color(0xFFC1C5D3),
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                    if (showDailyTip) {
                        Text(
                            dailyTip,
                            color = Color(0xFF9FA9BD),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            item {
                ObsidianGlassHero(
                    onCreate = onSruGen,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "YOUR WORKSPACE",
                            color = Color(0xFFC9C3F7),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "Tools & library",
                            color = Color(0xFFF1F0F7),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "ON DEVICE",
                        color = Color(0xFF9FA9BD),
                        fontSize = 9.sp,
                        letterSpacing = 1.4.sp
                    )
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    WorkspaceGlassRow(
                        icon = { Icon(Icons.Rounded.AutoFixHigh, null, tint = Color(0xFFE6E3F6), modifier = Modifier.size(21.dp)) },
                        title = "Image upscaler",
                        subtitle = "Refine detail with Real-ESRGAN",
                        trailing = "01",
                        onClick = onUpscale
                    )
                    GlassDivider()
                    WorkspaceGlassRow(
                        icon = { Icon(Icons.Rounded.Memory, null, tint = Color(0xFFE6E3F6), modifier = Modifier.size(21.dp)) },
                        title = "Model library",
                        subtitle = "Explore, install and switch models",
                        trailing = "02",
                        onClick = onModels
                    )
                    GlassDivider()
                    WorkspaceGlassRow(
                        icon = { Icon(Icons.Rounded.Security, null, tint = Color(0xFFE6E3F6), modifier = Modifier.size(21.dp)) },
                        title = "Srukrypt vault",
                        subtitle = "Your encrypted private files",
                        trailing = "03",
                        onClick = onSrukrypt
                    )
                    GlassDivider()
                    WorkspaceGlassRow(
                        icon = { Icon(Icons.Rounded.Speed, null, tint = Color(0xFFE6E3F6), modifier = Modifier.size(21.dp)) },
                        title = "Device instruments",
                        subtitle = "System and app-level diagnostics",
                        trailing = "04",
                        onClick = onDevice
                    )
                }
            }
        }
    }
}

@Composable
private fun ObsidianGlassHero(
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier
            .heightIn(min = 310.dp)
            .shadow(26.dp, shape, clip = false)
            .clip(shape)
            .background(Color(0xFF101522))
            .border(1.dp, Color(0x99FFFFFF), shape)
    ) {
        // The real artwork remains visible through the material; it is not buried
        // under the opaque charcoal overlays used by the previous hero.
        Image(
            painter = painterResource(R.drawable.sruvik_glass_art),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(16.dp) else Modifier),
            contentScale = ContentScale.Crop
        )
        // A low-opacity sharp transmission layer keeps the scene recognisable
        // while the diffused image underneath supplies the frosted-glass depth.
        Image(
            painter = painterResource(R.drawable.sruvik_glass_art),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(.16f),
            contentScale = ContentScale.Crop
        )

        // Tinted glass body: enough transmission to reveal the artwork beneath.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0x6E111A2B),
                            Color(0x5A17243A),
                            Color(0x73332D52),
                            Color(0x99101522)
                        )
                    )
                )
        )

        // Subtle internal refraction and broad reflected light, kept off the copy.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0x55D9E8FF),
                                Color(0x1E9DB9E8),
                                Color.Transparent
                            ),
                            center = Offset(size.width * .84f, size.height * .08f),
                            radius = size.maxDimension * .9f
                        )
                    )
                }
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(54.dp)
                .align(Alignment.CenterEnd)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0x12FFFFFF),
                            Color(0x25D9E6FF),
                            Color(0x08FFFFFF),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xAAFFFFFF),
                            Color(0x66C9D9FF),
                            Color(0x88FFFFFF),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color(0xCCFFFFFF),
                            Color(0x447F9BC8),
                            Color(0x6678DCEB),
                            Color(0x22FFFFFF)
                        )
                    ),
                    shape
                )
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .border(1.dp, Color(0x66FFFFFF), CircleShape)
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    Text(
                        "LOCAL IMAGE STUDIO",
                        color = Color(0xFFF1F4FF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.55.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "ON DEVICE",
                    color = Color(0xFFE4EAF7),
                    fontSize = 9.sp,
                    letterSpacing = 1.1.sp
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Turn a thought\ninto an image.",
                color = Color(0xFFFFFFFF),
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-.7).sp
            )
            Text(
                "Create with on-device models. Your prompts and generated work stay in your encrypted studio history.",
                color = Color(0xFFE0E5F0),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(12.dp))
            ObsidianGlassButton(
                onClick = onCreate,
                shape = RoundedCornerShape(30.dp),
                fillAlpha = .62f,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 15.dp)
            ) {
                Text(
                    "Open SruGen",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun WorkspaceGlassRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0x44FFFFFF), Color(0x222D3B5C), Color(0x447F78B8))
                    )
                )
                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFFF1F0F7), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFFADB5C7), fontSize = 12.sp, lineHeight = 17.sp)
        }
        Text(trailing, color = Color(0xFF9BA8C1), fontSize = 10.sp, letterSpacing = 1.2.sp)
    }
}

@Composable
private fun GlassDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 61.dp)
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0x00FFFFFF), Color(0x337F93B8), Color(0x55FFFFFF), Color(0x00FFFFFF))
                )
            )
    )
}

private fun greetingForNow(): String {
    val hour = java.time.LocalTime.now().hour
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else -> "Good night"
    }
}
