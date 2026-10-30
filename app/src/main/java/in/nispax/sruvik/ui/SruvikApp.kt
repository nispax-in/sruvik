package `in`.nispax.sruvik.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Year
import `in`.nispax.sruvik.ui.theme.SruvikColors

private enum class Destination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home), Models("Models", Icons.Rounded.Memory),
    Activity("Activity", Icons.Rounded.History), Settings("Settings", Icons.Rounded.Settings)
}

@Composable
fun SruvikApp() {
    var splashVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(850)
        splashVisible = false
    }
    if (splashVisible) {
        SplashScreen()
        return
    }

    var selected by remember { mutableStateOf(Destination.Home) }
    Box(Modifier.fillMaxSize().background(SruvikColors.Background)
        .drawBehind { drawAtmosphere() }
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        AnimatedContent(
            targetState = selected, modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "screen"
        ) { page ->
            when (page) {
                Destination.Home -> HomeScreen({ selected = Destination.Models }, { selected = Destination.Settings })
                Destination.Models -> EmptyPage("YOUR DEVICE", "Model library",
                    "Choose on-device models that fit your phone and the work you want to do.",
                    Icons.Rounded.Memory, "No models installed",
                    "Model downloads and storage controls will appear here.")
                Destination.Activity -> EmptyPage("YOUR WORK", "Activity",
                    "A quiet record of the things you create with Sruvik.",
                    Icons.Rounded.History, "Nothing here yet",
                    "Completed and in-progress tasks will appear here.")
                Destination.Settings -> SettingsPage()
            }
        }
        FloatingDock(selected, { selected = it },
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
private fun SplashScreen() {
    Box(
        Modifier.fillMaxSize()
            .background(SruvikColors.Background)
            .drawBehind { drawAtmosphere() }
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.sruvik_logo),
                contentDescription = "Sruvik",
                modifier = Modifier.size(118.dp)
            )
            Text("Sruvik", style = MaterialTheme.typography.displayLarge,
                color = SruvikColors.TextPrimary)
            Text("IDEAS INTO REALITY", style = MaterialTheme.typography.labelLarge,
                color = SruvikColors.TextSecondary, letterSpacing = 3.sp)
        }
        Text(
            "a NISPAX InfoTech Product",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            style = MaterialTheme.typography.labelSmall,
            color = SruvikColors.TextMuted,
            letterSpacing = .7.sp
        )
    }
}

private fun DrawScope.drawAtmosphere() {
    drawRect(Brush.radialGradient(listOf(Color(0x553C4B29), Color.Transparent),
        Offset(size.width * .10f, size.height * .22f), size.maxDimension * .72f))
    drawRect(Brush.radialGradient(listOf(Color(0x44316A5B), Color.Transparent),
        Offset(size.width * .92f, size.height * .52f), size.maxDimension * .62f))
    drawRect(Brush.radialGradient(listOf(Color(0x332D311F), Color.Transparent),
        Offset(size.width * .48f, size.height * .96f), size.maxDimension * .48f))
    val step = 38.dp.toPx()
    var x = 0f
    while (x <= size.width) {
        drawLine(Color.White.copy(alpha = .025f), Offset(x, 0f), Offset(x, size.height), .7.dp.toPx())
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(Color.White.copy(alpha = .025f), Offset(0f, y), Offset(size.width, y), .7.dp.toPx())
        y += step
    }
}

@Composable
private fun HomeScreen(onModels: () -> Unit, onSettings: () -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), state = state,
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(25.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(21.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.sruvik_logo),
                        contentDescription = "Sruvik logo",
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("SRUVIK", style = MaterialTheme.typography.labelLarge,
                            color = SruvikColors.TextSecondary, letterSpacing = 2.1.sp)
                        Text("IDEAS INTO REALITY", style = MaterialTheme.typography.labelSmall,
                            color = SruvikColors.TextMuted, letterSpacing = 1.1.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("01", style = MaterialTheme.typography.labelSmall,
                        color = SruvikColors.TextMuted, letterSpacing = 1.sp)
                }
                Column {
                    Text("Good to see", style = MaterialTheme.typography.displayLarge, color = SruvikColors.TextPrimary)
                    Text("you.", style = MaterialTheme.typography.displayLarge.copy(fontStyle = FontStyle.Italic),
                        color = SruvikColors.TextPrimary)
                    Text("Your creative workspace", style = MaterialTheme.typography.bodyLarge,
                        color = SruvikColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            HeroCard { scope.launch { state.animateScrollToItem(2) } }
        }
        item { SectionTitle("WORKSPACE", "02 TOOLS") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                WorkspaceTile(Modifier.weight(1f), "01", "Video\nnarration", Icons.Rounded.RecordVoiceOver,
                    Color(0xFF9A9863))
                WorkspaceTile(Modifier.weight(1f), "02", "Image\nenhancement", Icons.Rounded.AutoFixHigh,
                    Color(0xFF6EAAA0))
            }
        }
        item { SectionTitle("APP", "FOUNDATION") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppRow(Icons.Rounded.Memory, "Model manager", "Models for your device", "FOUNDATION", onModels)
                AppRow(Icons.Rounded.Security, "Device & privacy", "Local-first controls and safety", "IN DEVELOPMENT", onSettings)
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("SRUVIK  •  0.1.0", style = MaterialTheme.typography.labelSmall,
                    color = SruvikColors.TextMuted, letterSpacing = 1.sp)
                Text(
                    "© ${Year.now().value} NISPAX InfoTech. All Rights Reserved",
                    style = MaterialTheme.typography.labelSmall,
                    color = SruvikColors.TextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun HeroCard(onExplore: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(30.dp),
        Brush.linearGradient(listOf(Color(0xBDA8AA78), Color(0xA5416558), Color(0xC31B3933)))) {
        Box(Modifier.fillMaxWidth().height(248.dp)) {
            Box(Modifier.align(Alignment.BottomEnd).size(190.dp, 145.dp)
                .clip(RoundedCornerShape(topStart = 42.dp))
                .background(Brush.linearGradient(listOf(Color(0x334CC3AD), Color(0x224D8F80), Color(0x114C796A)))))
            Column(Modifier.fillMaxSize().padding(horizontal = 21.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(SruvikColors.Lime))
                    Text("YOUR CREATIVE SPACE", style = MaterialTheme.typography.labelLarge,
                        color = SruvikColors.TextPrimary.copy(alpha = .78f), letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(4.dp))
                Column {
                    Text("Make something worth", style = MaterialTheme.typography.headlineMedium, color = SruvikColors.TextPrimary)
                    Text("sharing.", style = MaterialTheme.typography.headlineMedium.copy(fontStyle = FontStyle.Italic),
                        color = SruvikColors.TextPrimary)
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(30.dp)).background(Color.White.copy(alpha = .16f))
                    .border(1.dp, Color.White.copy(alpha = .17f), RoundedCornerShape(30.dp))
                    .clickable(onClick = onExplore)
                    .padding(start = 17.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Explore tools", style = MaterialTheme.typography.labelLarge, color = SruvikColors.TextPrimary)
                    Box(Modifier.size(31.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ArrowForward, null, tint = SruvikColors.TextPrimary, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = SruvikColors.TextSecondary, letterSpacing = 2.1.sp)
        Text(trailing, style = MaterialTheme.typography.labelSmall, color = SruvikColors.TextMuted, letterSpacing = .8.sp)
    }
}

@Composable
private fun WorkspaceTile(modifier: Modifier, number: String, title: String, icon: ImageVector, tint: Color) {
    GlassCard(modifier.height(178.dp), RoundedCornerShape(27.dp),
        Brush.linearGradient(listOf(tint.copy(alpha = .24f), Color(0xA51A241F), Color(0xB316201B)))) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = .13f))
                    .border(1.dp, Color.White.copy(alpha = .13f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = SruvikColors.TextPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(number, style = MaterialTheme.typography.labelMedium, color = SruvikColors.TextSecondary)
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.titleLarge, color = SruvikColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Text("COMING SOON", style = MaterialTheme.typography.labelSmall,
                color = SruvikColors.TextSecondary.copy(alpha = .78f), letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun AppRow(icon: ImageVector, title: String, subtitle: String, status: String, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onClick), RoundedCornerShape(21.dp),
        Brush.linearGradient(listOf(Color(0x552D3E35), Color(0xAA121D18)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(43.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = .08f))
                .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = SruvikColors.TextSecondary, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = SruvikColors.TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(status, style = MaterialTheme.typography.labelSmall, color = SruvikColors.LimeMuted)
                Icon(Icons.Rounded.ArrowForward, "Open $title", tint = SruvikColors.TextMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier, shape: RoundedCornerShape, fill: Brush, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(shape).background(fill)
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .22f),
            SruvikColors.GlassBorder, Color.White.copy(alpha = .07f))), shape)
        .animateContentSize()) {
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .22f), Color.Transparent))))
        content()
    }
}

@Composable
private fun FloatingDock(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier) {
    GlassCard(modifier.fillMaxWidth().height(76.dp), RoundedCornerShape(40.dp),
        Brush.linearGradient(listOf(Color(0xD76C7057), Color(0xE1283028), Color(0xE018241E)))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Destination.entries.forEach { destination ->
                val active = selected == destination
                val scale by animateFloatAsState(if (active) 1.08f else 1f, label = "dock-icon-scale")
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                    .then(if (active) Modifier.background(Color.White.copy(alpha = .13f))
                        .border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(28.dp)) else Modifier)
                    .clickable { onSelect(destination) }, contentAlignment = Alignment.Center) {
                    Icon(destination.icon, destination.label,
                        tint = if (active) SruvikColors.TextPrimary else SruvikColors.TextSecondary.copy(alpha = .72f),
                        modifier = Modifier.size(23.dp).scale(scale))
                    if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                        .size(4.dp).clip(CircleShape).background(SruvikColors.TextPrimary))
                }
            }
        }
    }
}

@Composable
private fun EmptyPage(eyebrow: String, title: String, description: String, icon: ImageVector, message: String, detail: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 30.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = SruvikColors.LimeMuted, letterSpacing = 2.sp)
        Text(title, style = MaterialTheme.typography.displayLarge, color = SruvikColors.TextPrimary)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = SruvikColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(25.dp),
            Brush.linearGradient(listOf(Color(0x663B5946), Color(0xAA121D18)))) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = SruvikColors.LimeMuted, modifier = Modifier.size(28.dp))
                Text(message, style = MaterialTheme.typography.titleLarge, color = SruvikColors.TextPrimary)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = SruvikColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun SettingsPage() {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 30.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("PREFERENCES", style = MaterialTheme.typography.labelLarge, color = SruvikColors.LimeMuted, letterSpacing = 2.sp)
        Text("Settings", style = MaterialTheme.typography.displayLarge, color = SruvikColors.TextPrimary)
        Text("The essentials for how Sruvik works on your device.",
            style = MaterialTheme.typography.bodyLarge, color = SruvikColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        listOf(
            Triple(Icons.Rounded.Security, "Privacy & permissions", "Local-first processing principles"),
            Triple(Icons.Rounded.Tune, "Appearance", "A considered, low-distraction interface"),
            Triple(Icons.Rounded.Memory, "Device safety", "Performance and thermal safeguards")
        ).forEach { (icon, title, subtitle) ->
            GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                Brush.linearGradient(listOf(Color(0x5530473A), Color(0xAA111B16)))) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Icon(icon, null, tint = SruvikColors.TextSecondary, modifier = Modifier.size(23.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = SruvikColors.TextPrimary)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
                    }
                    Text("SOON", style = MaterialTheme.typography.labelSmall, color = SruvikColors.LimeMuted)
                }
            }
        }
    }
}
