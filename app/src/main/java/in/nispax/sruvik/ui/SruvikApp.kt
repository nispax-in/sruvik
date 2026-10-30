package `in`.nispax.sruvik.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.nispax.sruvik.ui.theme.SruvikColors

private enum class Destination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Models("Models", Icons.Rounded.Memory),
    Activity("Activity", Icons.Rounded.History),
    Settings("Settings", Icons.Rounded.Settings)
}

@Composable
fun SruvikApp() {
    var selected by remember { mutableStateOf(Destination.Home) }
    Scaffold(
        containerColor = SruvikColors.Background,
        bottomBar = {
            NavigationBar(
                containerColor = SruvikColors.NavigationGlass,
                contentColor = SruvikColors.TextSecondary,
                tonalElevation = 0.dp
            ) {
                Destination.entries.forEach { destination ->
                    val active = selected == destination
                    val iconScale by animateFloatAsState(if (active) 1.12f else 1f, label = "nav-icon-scale")
                    NavigationBarItem(
                        selected = active,
                        onClick = { selected = destination },
                        icon = {
                            Icon(destination.icon, contentDescription = destination.label,
                                modifier = Modifier.scale(iconScale))
                        },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SruvikColors.Lime,
                            selectedTextColor = SruvikColors.Lime,
                            indicatorColor = SruvikColors.GlassHighlight,
                            unselectedIconColor = SruvikColors.TextMuted,
                            unselectedTextColor = SruvikColors.TextMuted
                        )
                    )
                }
            }
        }
    ) { padding ->
        when (selected) {
            Destination.Home -> HomeScreen(Modifier.padding(padding))
            Destination.Models -> PlaceholderScreen("Models", "Manage on-device AI models and their storage.")
            Destination.Activity -> PlaceholderScreen("Activity", "Your recent jobs and processing history will appear here.")
            Destination.Settings -> PlaceholderScreen("Settings", "Privacy, appearance, device safety and app preferences.")
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("NISPAX INFOTECH", style = MaterialTheme.typography.labelLarge,
                        color = SruvikColors.Lime)
                    Text("Good to see you.", style = MaterialTheme.typography.headlineLarge)
                    Text("Your creative workspace", style = MaterialTheme.typography.bodyMedium,
                        color = SruvikColors.TextSecondary)
                }
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(SruvikColors.GlassHighlight, SruvikColors.SurfaceSoft)))
                        .border(1.dp, SruvikColors.GlassBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PersonOutline, contentDescription = "Profile",
                        tint = SruvikColors.Lime, modifier = Modifier.size(25.dp))
                }
            }
        }
        item {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                accent = true
            ) {
                Column(
                    Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(SruvikColors.Lime))
                        Text("YOUR CREATIVE SPACE", style = MaterialTheme.typography.labelLarge,
                            color = SruvikColors.Lime)
                    }
                    Text("Make something\nworth sharing.", style = MaterialTheme.typography.displaySmall,
                        color = SruvikColors.TextPrimary)
                    Text("Your tools. Your device. Your workflow.",
                        style = MaterialTheme.typography.bodyLarge, color = SruvikColors.TextSecondary)
                    Row(
                        Modifier.padding(top = 5.dp).clip(RoundedCornerShape(15.dp))
                            .background(Brush.horizontalGradient(listOf(SruvikColors.Lime, SruvikColors.LimeBright)))
                            .clickable { }.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.Explore, null, tint = SruvikColors.Background)
                        Text("Explore tools", color = SruvikColors.Background,
                            style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Rounded.ArrowForward, null, tint = SruvikColors.Background,
                            modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
        item { SectionHeading("WORKSPACE", "Create with your media") }
        item { FeatureRow(Icons.Rounded.RecordVoiceOver, "Video narration",
            "Turn a video into a narrated story", "COMING LATER", "01") }
        item { FeatureRow(Icons.Rounded.AutoFixHigh, "Image enhancement",
            "Upscale and improve your images", "COMING LATER", "02") }
        item { SectionHeading("APP", "Your on-device toolkit") }
        item { FeatureRow(Icons.Rounded.Memory, "Model manager",
            "Choose models that suit your device", "FOUNDATION", "03") }
        item { FeatureRow(Icons.Rounded.Security, "Device & privacy",
            "Local-first processing and safety controls", "IN DEVELOPMENT", "04") }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SRUVIK", style = MaterialTheme.typography.labelMedium, color = SruvikColors.TextMuted)
                Text("0.1.0  •  FOUNDATION", style = MaterialTheme.typography.labelMedium,
                    color = SruvikColors.TextMuted)
            }
        }
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val fill = if (accent) {
        Brush.linearGradient(listOf(SruvikColors.GlassTop, SruvikColors.Surface, SruvikColors.GlassBottom))
    } else {
        Brush.linearGradient(listOf(SruvikColors.GlassTop, SruvikColors.SurfaceElevated))
    }
    Column(
        modifier.clip(shape)
            .background(fill)
            .border(1.dp, Brush.linearGradient(
                listOf(SruvikColors.GlassBorder, SruvikColors.Border, SruvikColors.GlassEdge)
            ), shape)
            .animateContentSize(),
        content = content
    )
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = SruvikColors.Lime)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextMuted)
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, subtitle: String, status: String, number: String) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(SruvikColors.IconGlass, SruvikColors.SurfaceSoft)))
                    .border(1.dp, SruvikColors.GlassBorder, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = SruvikColors.Lime, modifier = Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = SruvikColors.TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = SruvikColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.labelSmall, color = SruvikColors.LimeMuted)
            }
            Text(number, style = MaterialTheme.typography.labelMedium, color = SruvikColors.TextMuted)
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, description: String) {
    Column(
        Modifier.fillMaxSize().background(SruvikColors.Background).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyLarge, color = SruvikColors.TextSecondary)
        Spacer(Modifier.height(16.dp))
        Text("This section is part of the app foundation.",
            style = MaterialTheme.typography.labelMedium, color = SruvikColors.Lime)
    }
}
