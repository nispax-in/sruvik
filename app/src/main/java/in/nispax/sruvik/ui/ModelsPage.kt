package `in`.nispax.sruvik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.nispax.sruvik.srugen.SruGenActiveModelStore
import `in`.nispax.sruvik.srugen.SruGenGalleryAvailability
import `in`.nispax.sruvik.srugen.SruGenGalleryCatalog
import `in`.nispax.sruvik.srugen.SruGenGalleryModel
import `in`.nispax.sruvik.srugen.SruGenEngineFactory
import `in`.nispax.sruvik.srugen.SruGenModelCompatibility
import `in`.nispax.sruvik.srugen.SruGenModelDeviceAssessment
import `in`.nispax.sruvik.ui.theme.SruvikColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class ModelLibraryTab(val label: String) {
    DISCOVER("Discover & install"),
    INSTALLED("Installed models")
}

@Composable
fun ModelsPage(onOpenModel: (String) -> Unit) {
    val context = LocalContext.current
    var tab by androidx.compose.runtime.remember { mutableStateOf(ModelLibraryTab.DISCOVER) }
    var checking by androidx.compose.runtime.remember { mutableStateOf(true) }
    var installedIds by androidx.compose.runtime.remember { mutableStateOf<Set<String>>(emptySet()) }
    var assessments by androidx.compose.runtime.remember {
        mutableStateOf<Map<String, SruGenModelDeviceAssessment>>(emptyMap())
    }
    var activeId by androidx.compose.runtime.remember {
        mutableStateOf(SruGenActiveModelStore.activeModelId(context))
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            val installed = SruGenGalleryCatalog.models.filter { model ->
                SruGenEngineFactory.isInstalled(context, model.id)
            }.map { it.id }.toSet()
            val checks = SruGenGalleryCatalog.models.associate { model ->
                model.id to SruGenModelCompatibility.assess(context, model)
            }
            installed to checks
        }
        installedIds = result.first
        assessments = result.second
        activeId = SruGenActiveModelStore.activeModelId(context)
        checking = false
    }

    val visibleModels = when (tab) {
        ModelLibraryTab.DISCOVER -> SruGenGalleryCatalog.models
        ModelLibraryTab.INSTALLED -> SruGenGalleryCatalog.models.filter { it.id in installedIds }
    }

    Column(
        Modifier.fillMaxSize().padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "SRUVIK  /  MODEL EXCHANGE",
                color = SruvikColors.LimeMuted,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.8.sp
            )
            Text(
                "Model library",
                color = SruvikColors.TextPrimary,
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                "Choose the creative engine behind your next idea. Every model has its own style, story and device requirements.",
                color = SruvikColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                LibrarySummaryTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.CloudDownload,
                    value = "${SruGenGalleryCatalog.models.count { it.canInstall }} ready",
                    label = "Available to install"
                )
                LibrarySummaryTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.CheckCircle,
                    value = if (checking) "Checking…" else "${installedIds.size} installed",
                    label = "On this device"
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = Color.Transparent,
            contentColor = SruvikColors.Lime,
            divider = {}
        ) {
            ModelLibraryTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tab = item },
                    text = {
                        Text(
                            item.label,
                            color = if (tab == item) SruvikColors.Lime else SruvikColors.TextMuted,
                            fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        if (checking) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SruvikColors.Lime)
            }
        } else if (tab == ModelLibraryTab.INSTALLED && visibleModels.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.Memory, null, tint = SruvikColors.LimeMuted, modifier = Modifier.size(36.dp))
                Text("No models installed yet", color = SruvikColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Explore the catalogue, open a model profile and install one that passes the device checks.",
                    color = SruvikColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 124.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        if (tab == ModelLibraryTab.DISCOVER) "THE COLLECTION" else "LOCAL MODEL VAULT",
                        color = SruvikColors.LimeMuted,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.4.sp
                    )
                }
                items(visibleModels, key = { it.id }) { model ->
                    val installed = model.id in installedIds
                    ModelGalleryCard(
                        model = model,
                        installed = installed,
                        active = activeId == model.id,
                        assessment = assessments[model.id],
                        onClick = { onOpenModel(model.id) }
                    )
                }
                item {
                    Text(
                        "Device approval is a conservative compatibility estimate, not a promise of crash-free operation. Review each model's source and licence before installing.",
                        color = SruvikColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySummaryTile(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0x662D3443),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .09f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, tint = SruvikColors.LimeMuted, modifier = Modifier.size(15.dp))
                Text(value, color = SruvikColors.TextPrimary, style = MaterialTheme.typography.titleSmall)
            }
            Text(label, color = SruvikColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ModelGalleryCard(
    model: SruGenGalleryModel,
    installed: Boolean,
    active: Boolean,
    assessment: SruGenModelDeviceAssessment?,
    onClick: () -> Unit
) {
    val accent = when {
        active -> Color(0xFFE98261)
        model.engine == `in`.nispax.sruvik.srugen.SruGenGalleryEngine.NEZUMI_MNN_SD15 -> Color(0xFFBDB9B5)
        else -> Color(0xFFBDB9B5)
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xAA1A2430)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .10f))
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(7.dp)
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = .85f), Color.Transparent)))
            )
            Column(
                Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(model.name, color = SruvikColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                        Text(model.subtitle, color = SruvikColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    ModelStatusPill(
                        label = when {
                            active -> "ACTIVE"
                            installed -> "INSTALLED"
                            model.canInstall && assessment?.canDownload == true -> "READY"
                            model.canInstall -> "DEVICE BLOCK"
                            else -> "COMING SOON"
                        },
                        tint = when {
                            active || installed -> Color(0xFFE98261)
                            model.canInstall && assessment?.canDownload == true -> SruvikColors.Lime
                            model.canInstall -> Color(0xFFF0B58F)
                            else -> SruvikColors.TextMuted
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.styleTags.take(3).forEach { tag ->
                        Surface(color = Color.White.copy(alpha = .06f), shape = RoundedCornerShape(20.dp)) {
                            Text(tag, Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                color = SruvikColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(
                    "“${model.lore}”",
                    color = Color(0xFFD1CCBE),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.Memory, null, tint = SruvikColors.TextMuted, modifier = Modifier.size(15.dp))
                    Text(model.runtime, color = SruvikColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (installed) "View details  ›" else if (model.canInstall && assessment?.canDownload == true) "View & install  ›" else "View profile  ›",
                        color = SruvikColors.LimeMuted,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (model.canInstall && assessment != null && !assessment.canDownload && !installed) {
                    Text(
                        assessment.reasons.firstOrNull() ?: "This model is currently blocked by the device check.",
                        color = Color(0xFFF0B58F),
                        style = MaterialTheme.typography.labelSmall,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelStatusPill(label: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = .13f),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = .34f))
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = .5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatModelSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        String.format(Locale.getDefault(), "%.2f GB", gib)
    } else {
        String.format(Locale.getDefault(), "%.0f MB", bytes / (1024.0 * 1024.0))
    }
}
