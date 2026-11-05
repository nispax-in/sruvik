package `in`.nispax.sruvik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
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
fun ModelsPage(
    onOpenModel: (String) -> Unit,
    onBackToStudio: (() -> Unit)? = null
) {
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
            if (onBackToStudio != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextButton(
                        onClick = onBackToStudio,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Back to SruGen")
                    }
                }
            }
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
            divider = {},
            indicator = { positions ->
                Box(
                    Modifier
                        .tabIndicatorOffset(positions[tab.ordinal])
                        .height(2.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, SruvikColors.Primary, Color(0xFFB5A8FF), Color.Transparent)
                            )
                        )
                )
            }
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
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        if (tab == ModelLibraryTab.DISCOVER) "THE COLLECTION" else "LOCAL MODEL VAULT",
                        color = Color(0xFF414A86),
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
    val accent = if (icon == Icons.Rounded.CloudDownload) {
        Color(0xFF80DDEB)
    } else {
        Color(0xFFB4A8FF)
    }
    Surface(
        modifier = modifier,
        color = Color(0xA91A2432),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(.8.dp, Color.White.copy(alpha = .15f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                Modifier.size(31.dp).clip(CircleShape).background(accent.copy(alpha = .13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value, color = Color(0xFFF0F3FA), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(label, color = Color(0xFFA8B3C5), style = MaterialTheme.typography.labelSmall)
            }
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
    val accent = if (active) Color(0xFF79DFEC) else Color(0xFFA99BFF)
    CatalogGlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(
            role = Role.Button,
            onClickLabel = "Open ${model.name}",
            onClick = onClick
        ),
        accent = accent,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                model.name,
                color = Color(0xFFF2F4FA),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    model.subtitle,
                    modifier = Modifier.weight(1f),
                    color = Color(0xFFC0C8D7),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
                ModelStatusPill(
                    label = when {
                        active -> "ACTIVE"
                        installed -> "INSTALLED"
                        model.canInstall && assessment?.canDownload == true -> "READY"
                        model.canInstall -> "DEVICE BLOCK"
                        else -> "COMING SOON"
                    },
                    tint = when {
                        active -> Color(0xFF83E8EF)
                        installed -> Color(0xFF9BD9D2)
                        model.canInstall && assessment?.canDownload == true -> Color(0xFFBDB0FF)
                        model.canInstall -> Color(0xFFF0BE83)
                        else -> Color(0xFFADB7C8)
                    }
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                model.styleTags.take(3).forEach { tag -> CatalogTag(tag) }
            }
            Text(
                "“${model.lore}”",
                color = Color(0xFFD0D6E2),
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModelMetadataChip(model.baseModel.substringBefore(" · "), "BASE")
                ModelMetadataChip(formatModelSize(model.approximateDownloadBytes), "PACKAGE")
            }
            Text(
                "Android ${model.minAndroidApi}+  ·  ${model.recommendedRamGb} GB recommended RAM",
                color = Color(0xFFAAB5C6),
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 16.sp
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Memory, null, tint = Color(0xFF9BAAC3), modifier = Modifier.size(15.dp))
                Text(
                    model.runtime,
                    modifier = Modifier.weight(1f),
                    color = Color(0xFFB9C3D2),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    if (installed) "View details  ›" else if (model.canInstall && assessment?.canDownload == true) "View & install  ›" else "View profile  ›",
                    color = Color(0xFF83E5EF),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (model.canInstall && assessment != null && !assessment.canDownload && !installed) {
                Text(
                    assessment.reasons.firstOrNull() ?: "This model is currently blocked by the device check.",
                    color = Color(0xFFF0BE83),
                    style = MaterialTheme.typography.labelSmall,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun CatalogGlassSurface(
    modifier: Modifier = Modifier,
    accent: Color,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .shadow(12.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xD51B2534), Color(0xCC111925), Color(0xD00B111A))
                )
            )
            .border(
                BorderStroke(
                    .8.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = .22f), accent.copy(alpha = .25f), Color.White.copy(alpha = .09f))
                    )
                ),
                shape
            )
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        content = content
    )
}

@Composable
private fun CatalogTag(label: String) {
    Surface(
        color = Color(0xA8212B3B),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(.7.dp, Color.White.copy(alpha = .14f))
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color(0xFFD3DBE8),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ModelMetadataChip(value: String, label: String) {
    Surface(
        color = Color(0xA90C131E),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(.7.dp, Color.White.copy(alpha = .14f))
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color(0xFF9AA8BC), style = MaterialTheme.typography.labelSmall)
            Text(value, color = Color(0xFFE7ECF4), style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModelStatusPill(label: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = .13f),
        shape = CircleShape,
        border = BorderStroke(.7.dp, tint.copy(alpha = .38f))
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = .35.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

private fun formatModelSize(bytes: Long): String {
    if (bytes <= 0L) return "Not published"
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        String.format(Locale.getDefault(), "%.2f GB", gib)
    } else {
        String.format(Locale.getDefault(), "%.0f MB", bytes / (1024.0 * 1024.0))
    }
}
