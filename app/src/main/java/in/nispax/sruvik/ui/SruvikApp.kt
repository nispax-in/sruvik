package `in`.nispax.sruvik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            NavigationBar(containerColor = SruvikColors.Surface, contentColor = SruvikColors.TextSecondary) {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { selected = destination },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SruvikColors.Lime,
                            selectedTextColor = SruvikColors.Lime,
                            indicatorColor = SruvikColors.SurfaceSoft,
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
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("NISPAX INFOTECH", style = MaterialTheme.typography.labelMedium, color = SruvikColors.Lime)
                    Spacer(Modifier.height(5.dp))
                    Text("Good to see you.", style = MaterialTheme.typography.headlineLarge)
                }
                Surface(shape = RoundedCornerShape(50), color = SruvikColors.SurfaceElevated) {
                    Icon(Icons.Rounded.PersonOutline, null, tint = SruvikColors.Lime, modifier = Modifier.padding(12.dp).size(23.dp))
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(26.dp), color = SruvikColors.Surface, border = androidx.compose.foundation.BorderStroke(1.dp, SruvikColors.Border)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("YOUR CREATIVE SPACE", style = MaterialTheme.typography.labelMedium, color = SruvikColors.TextSecondary)
                    Text("Make something\nworth sharing.", style = MaterialTheme.typography.headlineLarge)
                    Text("Your tools, your device, your workflow.", style = MaterialTheme.typography.bodyMedium, color = SruvikColors.TextSecondary)
                    Surface(shape = RoundedCornerShape(14.dp), color = SruvikColors.Lime) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Rounded.Add, null, tint = SruvikColors.Background)
                            Text("Explore tools", color = SruvikColors.Background, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        item { SectionHeading("WORKSPACE") }
        item { FeatureRow(Icons.Rounded.RecordVoiceOver, "Video narration", "Turn a video into a narrated story", "Coming later") }
        item { FeatureRow(Icons.Rounded.AutoFixHigh, "Image enhancement", "Upscale and improve your images", "Coming later") }
        item { SectionHeading("APP") }
        item { FeatureRow(Icons.Rounded.Memory, "Model manager", "Choose models that suit your device", "Foundation ready") }
        item { FeatureRow(Icons.Rounded.Security, "Device & privacy", "Local-first processing and safety controls", "In development") }
        item {
            Text("Sruvik  •  0.1.0 foundation", style = MaterialTheme.typography.labelMedium, color = SruvikColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = SruvikColors.Lime, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, subtitle: String, status: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = SruvikColors.SurfaceElevated, border = androidx.compose.foundation.BorderStroke(1.dp, SruvikColors.Border)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Surface(shape = RoundedCornerShape(13.dp), color = SruvikColors.SurfaceSoft) {
                Icon(icon, null, tint = SruvikColors.Lime, modifier = Modifier.padding(11.dp).size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SruvikColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(status, style = MaterialTheme.typography.labelMedium, color = SruvikColors.TextMuted)
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, description: String) {
    Column(Modifier.fillMaxSize().background(SruvikColors.Background).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyLarge, color = SruvikColors.TextSecondary)
        Spacer(Modifier.height(16.dp))
        Text("This section is part of the app foundation.", style = MaterialTheme.typography.labelMedium, color = SruvikColors.Lime)
    }
}
