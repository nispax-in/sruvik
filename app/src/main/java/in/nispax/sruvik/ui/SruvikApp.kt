package `in`.nispax.sruvik.ui

import `in`.nispax.sruvik.R

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Year
import `in`.nispax.sruvik.ui.theme.SruvikColors

private enum class Destination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home), Upscaler("Upscale", Icons.Rounded.AutoFixHigh), Models("Models", Icons.Rounded.Memory),
    Activity("Activity", Icons.Rounded.History), Device("Device", Icons.Rounded.Speed), Settings("Settings", Icons.Rounded.Settings),
    SruGen("SruGen", Icons.Rounded.AutoAwesome), Srukrypt("Srukrypt", Icons.Rounded.Security)
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

    val context = LocalContext.current
    var profile by remember { mutableStateOf(SruvikPreferences.loadProfile(context)) }
    var showDailyTip by remember { mutableStateOf(SruvikPreferences.showDailyTip(context)) }
    var sruGenMemoryEnabled by remember { mutableStateOf(SruvikPreferences.sruGenMemoryEnabled(context)) }
    var selected by remember { mutableStateOf(Destination.Home) }
    Box(Modifier.fillMaxSize().background(SruvikColors.Background)
        .drawBehind { drawAtmosphere() }
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        AnimatedContent(
            targetState = selected, modifier = Modifier.fillMaxSize(),
            transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 18 }) togetherWith (fadeOut(tween(140)) + slideOutHorizontally(tween(190)) { -it / 24 }) },
            label = "screen"
        ) { page ->
            when (page) {
                Destination.Home -> HomeScreen(profile.name, showDailyTip, { selected = Destination.Models }, { selected = Destination.Settings }, { selected = Destination.Upscaler }, { selected = Destination.SruGen }, { selected = Destination.Srukrypt })
                Destination.Models -> ModelsPage(onOpenSruGen = { selected = Destination.SruGen })
                Destination.Activity -> EmptyPage("YOUR WORK", "Activity",
                    "A quiet record of the things you create with Sruvik.",
                    Icons.Rounded.History, "Nothing here yet",
                    "Completed and in-progress tasks will appear here.")
                Destination.Device -> DeviceMonitorPage()
                Destination.Settings -> SettingsPage(
                    profile = profile,
                    showDailyTip = showDailyTip,
                    sruGenMemoryEnabled = sruGenMemoryEnabled,
                    onSruGenMemoryChange = {
                        sruGenMemoryEnabled = it
                        SruvikPreferences.setSruGenMemoryEnabled(context, it)
                    },
                    onShowDailyTipChange = {
                        showDailyTip = it
                        SruvikPreferences.setShowDailyTip(context, it)
                    },
                    onProfileSaved = {
                        SruvikPreferences.saveProfile(context, it)
                        profile = it
                    }
                )
                Destination.Upscaler -> ImageUpscalerPage(onBack = { selected = Destination.Home })
                Destination.SruGen -> SruGenPage(memoryEnabled = sruGenMemoryEnabled)
                Destination.Srukrypt -> SrukryptPage(SruvikPreferences.vaultAutoLockMinutes(context))
            }
        }
        Text(
            "© ${Year.now().value} NISPAX InfoTech. All Rights Reserved",
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 18.dp).padding(bottom = 94.dp),
            style = MaterialTheme.typography.labelSmall,
            color = SruvikColors.TextMuted,
            textAlign = TextAlign.Center
        )
        FloatingDock(selected, { selected = it },
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp))
    }
    if (!profile.isComplete) {
        FirstRunProfileDialog { completed ->
            SruvikPreferences.saveProfile(context, completed)
            profile = completed
        }
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
    drawRect(Brush.radialGradient(listOf(Color(0x554B327D), Color.Transparent),
        Offset(size.width * .10f, size.height * .22f), size.maxDimension * .72f))
    drawRect(Brush.radialGradient(listOf(Color(0x44305C8A), Color.Transparent),
        Offset(size.width * .92f, size.height * .52f), size.maxDimension * .62f))
    drawRect(Brush.radialGradient(listOf(Color(0x334E2F77), Color.Transparent),
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
private fun HomeScreen(name: String, showDailyTip: Boolean, onModels: () -> Unit, onSettings: () -> Unit, onUpscale: () -> Unit, onSruGen: () -> Unit, onSrukrypt: () -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
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
            "Fun fact: the first digital photograph was made in 1957 — long before modern cameras.",
            "A clean original is the best starting point for a great upscale.",
            "Try changing one creative detail at a time. It makes results easier to compare.",
            "Fun fact: the word pixel blends “picture” and “element”.",
            "Your device can do useful creative work without sending files to the cloud.",
            "Save a copy before experimenting. Curiosity works best with an undo plan."
        )
        tips[java.time.LocalDate.now().dayOfYear % tips.size]
    }
    LazyColumn(Modifier.fillMaxSize(), state = state,
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(25.dp)) {
        item {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 12 }
            ) {
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
                    SruvikAvatar(name, Modifier.size(42.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$greeting, $name!", style = MaterialTheme.typography.displayLarge,
                        color = SruvikColors.TextPrimary)
                    if (showDailyTip) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = SruvikColors.LimeMuted,
                                modifier = Modifier.padding(top = 2.dp).size(14.dp))
                            Text(dailyTip, style = MaterialTheme.typography.bodySmall,
                                color = SruvikColors.TextSecondary, lineHeight = 17.sp)
                        }
                    }
                }
            }
            }
        }
        item {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(460, delayMillis = 70)) +
                    slideInVertically(tween(460, delayMillis = 70)) { it / 14 }
            ) {
                HeroCard { scope.launch { state.animateScrollToItem(2) } }
            }
        }
        item { SectionTitle("WORKSPACE", "02 TOOLS") }
        item {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(440, delayMillis = 120)) +
                    slideInVertically(tween(440, delayMillis = 120)) { it / 16 }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    WorkspaceTile(Modifier.weight(1f), "01", "Video\nnarration", Icons.Rounded.RecordVoiceOver,
                        Color(0xFF9A9863))
                    WorkspaceTile(Modifier.weight(1f), "02", "Image\nenhancement", Icons.Rounded.AutoFixHigh,
                        Color(0xFF7D75D8), "OPEN", onUpscale, artRes = R.drawable.sruvik_glass_art)
                }
            }
        }
        item { SectionTitle("CREATE & PROTECT", "SRUVIK TOOLS") }
        item {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(440, delayMillis = 170)) +
                    slideInVertically(tween(440, delayMillis = 170)) { it / 16 }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    WorkspaceTile(Modifier.weight(1f), "03", "SruGen\nimage studio", Icons.Rounded.AutoAwesome,
                        Color(0xFF8A72D9), "OPEN STUDIO", onSruGen, artRes = R.drawable.sruvik_glass_art)
                    WorkspaceTile(Modifier.weight(1f), "04", "Srukrypt\nprivate vault", Icons.Rounded.Security,
                        Color(0xFF4A9BBD), "OPEN VAULT", onSrukrypt, artRes = R.drawable.sruvik_alpine_art)
                }
            }
        }
        item { SectionTitle("VISUAL INSPIRATION", "SRUVIK ORIGINALS") }
        item {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(420, delayMillis = 220)) +
                    slideInVertically(tween(420, delayMillis = 220)) { it / 18 }
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        ArtCard(Modifier.width(198.dp).height(224.dp), R.drawable.sruvik_city_art,
                            "Dreamscapes", "Worlds without limits")
                    }
                    item {
                        ArtCard(Modifier.width(178.dp).height(224.dp), R.drawable.sruvik_glass_art,
                            "Fluid forms", "Light, colour and motion")
                    }
                    item {
                        ArtCard(Modifier.width(178.dp).height(224.dp), R.drawable.sruvik_alpine_art,
                            "Cinematic nature", "Atmosphere in every frame")
                    }
                }
            }
        }
        item { SectionTitle("APP", "FOUNDATION") }
        item {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(420, delayMillis = 260)) +
                    slideInVertically(tween(420, delayMillis = 260)) { it / 18 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppRow(Icons.Rounded.Memory, "Model manager", "Models for your device", "FOUNDATION", onModels)
                    AppRow(Icons.Rounded.Security, "Device & privacy", "Local-first controls and safety", "IN DEVELOPMENT", onSettings)
                }
            }
        }
    }
}

@Composable
private fun HeroCard(onExplore: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(30.dp),
        Brush.linearGradient(listOf(Color(0xBDA8AA78), Color(0xA5416558), Color(0xC31B3933)))) {
        Box(Modifier.fillMaxWidth().height(258.dp)) {
            Image(
                painter = painterResource(R.drawable.sruvik_city_art),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0x1810162B), Color(0x6D101126), Color(0xF20A0B19))
                    )
                )
            )
            Column(Modifier.fillMaxSize().padding(horizontal = 21.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(SruvikColors.Lime))
                    Text("YOUR CREATIVE SPACE", style = MaterialTheme.typography.labelLarge,
                        color = SruvikColors.TextPrimary.copy(alpha = .88f), letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(4.dp))
                Column {
                    Text("Make something worth", style = MaterialTheme.typography.headlineMedium, color = SruvikColors.TextPrimary)
                    Text("sharing.", style = MaterialTheme.typography.headlineMedium.copy(fontStyle = FontStyle.Italic),
                        color = SruvikColors.TextPrimary)
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(30.dp)).background(Color.White.copy(alpha = .16f))
                    .border(1.dp, Color.White.copy(alpha = .22f), RoundedCornerShape(30.dp))
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
private fun WorkspaceTile(modifier: Modifier, number: String, title: String, icon: ImageVector, tint: Color,
    status: String = "COMING SOON", onClick: (() -> Unit)? = null, artRes: Int? = null) {
    GlassCard(modifier.height(178.dp).clickable(enabled = onClick != null) { onClick?.invoke() }, RoundedCornerShape(27.dp),
        Brush.linearGradient(listOf(tint.copy(alpha = .24f), Color(0xA51A241F), Color(0xB316201B)))) {
        Box(Modifier.fillMaxSize()) {
            if (artRes != null) {
                Image(
                    painter = painterResource(artRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0x24101425), Color(0xAE101226), Color(0xF20A0D18)))
                ))
            }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = .13f))
                        .border(1.dp, Color.White.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = SruvikColors.TextPrimary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(number, style = MaterialTheme.typography.labelMedium, color = SruvikColors.TextPrimary.copy(alpha = .82f))
                }
                Spacer(Modifier.weight(1f))
                Text(title, style = MaterialTheme.typography.titleLarge, color = SruvikColors.TextPrimary)
                Spacer(Modifier.height(12.dp))
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = if (onClick != null) SruvikColors.Lime else SruvikColors.TextSecondary.copy(alpha = .9f), letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun ArtCard(modifier: Modifier, artRes: Int, title: String, subtitle: String) {
    Box(modifier.clip(RoundedCornerShape(24.dp))
        .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(24.dp))) {
        Image(
            painter = painterResource(artRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0x08090D1B), Color(0x3510142B), Color(0xF20A0B18)))
        ))
        Column(Modifier.align(Alignment.BottomStart).padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SruvikColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
        }
    }
}

@Composable
private fun AppRow(icon: ImageVector, title: String, subtitle: String, status: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) .985f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "app-row-press"
    )
    GlassCard(Modifier.fillMaxWidth().scale(pressScale)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick), RoundedCornerShape(21.dp),
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
            Destination.entries.filter { it in setOf(Destination.Home, Destination.Models, Destination.Activity, Destination.Device, Destination.Settings) }.forEach { destination ->
                val active = (if (selected in setOf(Destination.Upscaler, Destination.SruGen, Destination.Srukrypt)) Destination.Home else selected) == destination
                val selectionProgress by animateFloatAsState(
                    targetValue = if (active) 1f else 0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                    label = "dock-selection"
                )
                val scale by animateFloatAsState(
                    targetValue = if (active) 1.08f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    label = "dock-icon-scale"
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                        .clickable { onSelect(destination) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))
                            .background(Color.White.copy(alpha = .13f * selectionProgress))
                            .border(1.dp, Color.White.copy(alpha = .13f * selectionProgress), RoundedCornerShape(28.dp))
                    )
                    Icon(destination.icon, destination.label,
                        tint = if (active) SruvikColors.TextPrimary else SruvikColors.TextSecondary.copy(alpha = .72f),
                        modifier = Modifier.size(23.dp).scale(scale))
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                            .size(4.dp).scale(selectionProgress)
                            .clip(CircleShape).background(SruvikColors.TextPrimary)
                    )
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


private fun greetingForNow(): String {
    val hour = java.time.LocalTime.now().hour
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else -> "Good night"
    }
}
