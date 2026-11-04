package `in`.nispax.sruvik.ui

import `in`.nispax.sruvik.R

import androidx.compose.animation.*
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Year
import `in`.nispax.sruvik.ui.theme.SruvikColors

private enum class Destination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home), Upscaler("Upscale", Icons.Rounded.AutoFixHigh), Models("Models", Icons.Rounded.Memory),
    Activity("Activity", Icons.Rounded.History), Device("Device", Icons.Rounded.Speed), Settings("Settings", Icons.Rounded.Settings),
    SruGen("SruGen", Icons.Rounded.AutoAwesome), Srukrypt("Srukrypt", Icons.Rounded.Security),
    ModelDetail("Model detail", Icons.Rounded.Memory)
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
    var selectedModelId by remember { mutableStateOf("sd15-mediapipe") }
    // SruGen is a focused creative workspace, not a peer destination in the
    // global app navigation. Remember its return route while opening the model library.
    var modelsReturnToSruGen by remember { mutableStateOf(false) }
    var vaultReturnToSruGen by remember { mutableStateOf(false) }

    val canHandleInAppBack = selected == Destination.SruGen ||
        selected == Destination.ModelDetail ||
        (selected == Destination.Models && modelsReturnToSruGen) ||
        (selected == Destination.Srukrypt && vaultReturnToSruGen)
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }

    fun navigateBackInApp() {
        when (selected) {
            Destination.SruGen -> selected = Destination.Home
            Destination.ModelDetail -> selected = Destination.Models
            Destination.Models -> {
                selected = Destination.SruGen
                modelsReturnToSruGen = false
            }
            Destination.Srukrypt -> {
                selected = Destination.SruGen
                vaultReturnToSruGen = false
            }
            else -> Unit
        }
    }

    PredictiveBackHandler(enabled = canHandleInAppBack) { backEvents ->
        try {
            backEvents.collect { event -> predictiveBackProgress = event.progress }
            navigateBackInApp()
        } catch (_: CancellationException) {
            // A cancelled edge gesture leaves the current destination untouched.
        } finally {
            predictiveBackProgress = 0f
        }
    }
    Box(Modifier.fillMaxSize().background(SruvikColors.Background)
        .drawBehind { drawAtmosphere() }
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        AnimatedContent(
            targetState = selected,
            modifier = Modifier.fillMaxSize().padding(
                bottom = if (
                    selected == Destination.SruGen ||
                    (modelsReturnToSruGen && selected in setOf(Destination.Models, Destination.ModelDetail))
                ) 0.dp else 80.dp
            ).graphicsLayer {
                translationX = predictiveBackProgress * size.width * 0.12f
                val scale = 1f - predictiveBackProgress * 0.025f
                scaleX = scale
                scaleY = scale
            },
            transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 18 }) togetherWith (fadeOut(tween(140)) + slideOutHorizontally(tween(190)) { -it / 24 }) },
            label = "screen"
        ) { page ->
            when (page) {
                Destination.Home -> HomeScreenLiquidGlass(
                    name = profile.name,
                    showDailyTip = showDailyTip,
                    onModels = { selected = Destination.Models },
                    onSettings = { selected = Destination.Settings },
                    onUpscale = { selected = Destination.Upscaler },
                    onSruGen = { selected = Destination.SruGen },
                    onSrukrypt = {
                        vaultReturnToSruGen = false
                        selected = Destination.Srukrypt
                    },
                    onDevice = { selected = Destination.Device }
                )
                Destination.Models -> ModelsPage(
                    onOpenModel = { modelId ->
                        selectedModelId = modelId
                        selected = Destination.ModelDetail
                    },
                    onBackToStudio = if (modelsReturnToSruGen) {
                        {
                            selected = Destination.SruGen
                            modelsReturnToSruGen = false
                        }
                    } else null
                )
                Destination.ModelDetail -> ModelDetailPage(
                    modelId = selectedModelId,
                    onBack = { selected = Destination.Models },
                    onInstalled = { selected = Destination.Models }
                )
                Destination.Activity -> EmptyPage("YOUR WORK", "Activity",
                    "A quiet record of the things you create with Sruvik.",
                    Icons.Rounded.History, "Nothing here yet",
                    "Activity history is not persisted in this build yet. Your creations and upscales remain available in their own workspaces.")
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
                Destination.SruGen -> SruGenPage(
                    memoryEnabled = sruGenMemoryEnabled,
                    onBack = { selected = Destination.Home },
                    onOpenModels = {
                        modelsReturnToSruGen = true
                        selected = Destination.Models
                    },
                    onOpenVault = {
                        vaultReturnToSruGen = true
                        selected = Destination.Srukrypt
                    }
                )
                Destination.Srukrypt -> SrukryptPage(SruvikPreferences.vaultAutoLockMinutes(context))
            }
        }
        // Primary destinations stay in a conventional Android navigation bar.
        // The SruGen studio is a focused task surface, so it owns the full window
        // and does not compete with global navigation while the user is creating.
        val focusedStudioFlow = selected == Destination.SruGen ||
            (modelsReturnToSruGen && selected in setOf(Destination.Models, Destination.ModelDetail))
        if (!focusedStudioFlow) {
            AppNavigationBar(
                selected = when (selected) {
                    Destination.ModelDetail -> Destination.Models
                    else -> selected
                },
                glass = true,
                onSelect = {
                    modelsReturnToSruGen = false
                    vaultReturnToSruGen = false
                    selected = it
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
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
    // Ambient reflections stay at the perimeter so the reading canvas remains quiet.
    drawRect(Brush.radialGradient(
        listOf(Color(0x263D2F78), Color(0x103D2F78), Color.Transparent),
        Offset(size.width * .04f, size.height * .08f), size.maxDimension * .66f
    ))
    drawRect(Brush.radialGradient(
        listOf(Color(0x1E1D6575), Color(0x0B1D6575), Color.Transparent),
        Offset(size.width * .98f, size.height * .42f), size.maxDimension * .58f
    ))
    drawRect(Brush.radialGradient(
        listOf(Color(0x101E315A), Color.Transparent),
        Offset(size.width * .48f, size.height * 1.02f), size.maxDimension * .50f
    ))
}

@Composable
private fun HomeScreen(
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
        val horizontal = if (maxWidth >= 600.dp) 32.dp else 20.dp
        LazyColumn(
            Modifier.widthIn(max = 920.dp).fillMaxWidth().align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = horizontal, end = horizontal, top = 22.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.sruvik_logo),
                        contentDescription = "Sruvik",
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("SRUVIK", style = MaterialTheme.typography.labelLarge,
                            color = SruvikColors.TextPrimary, letterSpacing = 2.2.sp)
                        Text("PRIVATE AI WORKSPACE", style = MaterialTheme.typography.labelSmall,
                            color = SruvikColors.TextMuted, letterSpacing = 1.0.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    SruvikAvatar(name, Modifier.size(48.dp))
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$greeting, $name.", style = MaterialTheme.typography.displaySmall,
                        color = SruvikColors.TextPrimary)
                    Text("A quieter place to make, refine and keep.", style = MaterialTheme.typography.bodyLarge,
                        color = SruvikColors.TextSecondary)
                    if (showDailyTip) {
                        Spacer(Modifier.height(3.dp))
                        Text(dailyTip, style = MaterialTheme.typography.bodySmall,
                            color = SruvikColors.TextMuted)
                    }
                }
            }
            item {
                GlassCard(
                    Modifier.fillMaxWidth(),
                    RoundedCornerShape(26.dp),
                    Brush.linearGradient(listOf(Color(0x76242B3B), Color(0x700C111B), Color(0xE61A1A2A)))
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 228.dp).wrapContentHeight()) {
                        Image(
                            painter = painterResource(R.drawable.sruvik_glass_art),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(listOf(Color(0xF2080A10), Color(0xC9080A10), Color(0x66101624)))
                        ))
                        Box(Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color(0x22080A10), Color(0xEF080A10)))
                        ))
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 21.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(SruvikColors.Primary))
                                Text("LOCAL IMAGE STUDIO", style = MaterialTheme.typography.labelSmall,
                                    color = SruvikColors.Primary, letterSpacing = 1.5.sp)
                            }
                            Text("Turn a thought\ninto an image.", style = MaterialTheme.typography.headlineLarge,
                                color = SruvikColors.TextPrimary)
                            Text("Create with on-device models. Keep prompts and generated work in your encrypted studio history.",
                                style = MaterialTheme.typography.bodyMedium, color = SruvikColors.TextSecondary)
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = onSruGen,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SruvikColors.Primary,
                                        contentColor = SruvikColors.OnPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 11.dp)
                                ) {
                                    Text("Open SruGen")
                                    Spacer(Modifier.width(7.dp))
                                    Icon(Icons.Rounded.ArrowForward, null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text("ON-DEVICE", style = MaterialTheme.typography.labelSmall,
                                    color = SruvikColors.TextMuted, letterSpacing = 1.sp)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("TOOLS & LIBRARY", style = MaterialTheme.typography.labelSmall,
                            color = SruvikColors.SecondaryAccent, letterSpacing = 1.4.sp)
                        Text("Your workspace", style = MaterialTheme.typography.titleLarge,
                            color = SruvikColors.TextPrimary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("LOCAL FIRST", style = MaterialTheme.typography.labelSmall,
                        color = SruvikColors.TextMuted, letterSpacing = .8.sp)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    AppRow(Icons.Rounded.AutoFixHigh, "Image upscaler",
                        "Enhance a photo with the local Real-ESRGAN model", "OPEN TOOL", onUpscale)
                    AppRow(Icons.Rounded.Memory, "Model library",
                        "Check compatibility, install and switch models", "MANAGE", onModels)
                    AppRow(Icons.Rounded.Security, "Srukrypt vault",
                        "Keep private files in encrypted device storage", "PRIVATE", onSrukrypt)
                    AppRow(Icons.Rounded.Speed, "Device instruments",
                        "Inspect system availability and this app's footprint", "MONITOR", onDevice)
                }
            }
            item {
                GlassCard(
                    Modifier.fillMaxWidth(),
                    RoundedCornerShape(17.dp),
                    Brush.linearGradient(listOf(Color(0x661A2230), Color(0x770D111A)))
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Icon(Icons.Rounded.Shield, null, tint = SruvikColors.SecondaryAccent, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Privacy & preferences", style = MaterialTheme.typography.titleSmall,
                                color = SruvikColors.TextPrimary)
                            Text("Memory, vault timeout and personal settings", style = MaterialTheme.typography.bodySmall,
                                color = SruvikColors.TextSecondary)
                        }
                        TextButton(onClick = onSettings) { Text("Settings") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(onExplore: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(30.dp),
        Brush.linearGradient(listOf(Color(0x77A8AA78), Color(0x66516558), Color(0x801B3933)))) {
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) .975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "workspace-tile-press"
    )
    GlassCard(
        modifier.height(178.dp).scale(pressScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = onClick != null
            ) { onClick?.invoke() },
        RoundedCornerShape(27.dp),
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
        Brush.linearGradient(listOf(Color(0x761B2230), Color(0xF20D111A)))) {
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
@Suppress("UNUSED_PARAMETER")
private fun GlassCard(modifier: Modifier, shape: RoundedCornerShape, fill: Brush, content: @Composable BoxScope.() -> Unit) {
    ObsidianGlassBox(
        modifier = modifier.animateContentSize(),
        shape = shape,
        fill = fill
    ) {
        // A single restrained upper-edge reflection gives the surface a material cue.
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color(0x887F8BC2), Color(0x6675DCEB), Color.Transparent)
                )
            )
        )
        content()
    }
}
/**
 * The app's five peer destinations use a labelled native navigation bar.
 * Creative sessions such as SruGen temporarily leave this global navigation
 * so the prompt, conversation and image canvas keep the full available height.
 */
@Composable
private fun AppNavigationBar(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = true
) {
    val destinations = listOf(
        Destination.Home,
        Destination.Models,
        Destination.Activity,
        Destination.Device,
        Destination.Settings
    )
    NavigationBar(
        modifier = modifier.fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Brush.verticalGradient(
                listOf(Color(0xD91B2230), Color(0xF20B0E16))
            ))
            .border(
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(0x667F91B5), Color(0x5575DCEB), Color.Transparent)
                    )
                ),
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ),
        containerColor = Color.Transparent,
        contentColor = SruvikColors.TextPrimary,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        destination.icon,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp)
                    )
                },
                label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF07121A),
                    selectedTextColor = Color(0xFF75E3F3),
                    indicatorColor = Color(0xFF75E3F3),
                    unselectedIconColor = Color(0xFFB0B8C9),
                    unselectedTextColor = Color(0xFFB0B8C9)
                )
            )
        }
    }
}

@Composable
private fun EmptyPage(eyebrow: String, title: String, description: String, icon: ImageVector, message: String, detail: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 30.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = ObsidianTokens.Cyan, letterSpacing = 1.7.sp)
        Text(title, style = MaterialTheme.typography.displayLarge, color = SruvikColors.TextPrimary)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = SruvikColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(25.dp),
            Brush.linearGradient(listOf(Color(0x761B2230), Color(0xF20D111A)))) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = ObsidianTokens.Cyan, modifier = Modifier.size(28.dp))
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
