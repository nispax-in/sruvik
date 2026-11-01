package `in`.nispax.sruvik.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.nispax.sruvik.srugen.SruGenActiveModelStore
import `in`.nispax.sruvik.srugen.SruGenEngineRegistry
import `in`.nispax.sruvik.srugen.SruGenGalleryAvailability
import `in`.nispax.sruvik.srugen.SruGenGalleryCatalog
import `in`.nispax.sruvik.srugen.SruGenGalleryEngine
import `in`.nispax.sruvik.srugen.SruGenGalleryModel
import `in`.nispax.sruvik.srugen.SruGenEngineFactory
import `in`.nispax.sruvik.srugen.SruGenModelCompatibility
import `in`.nispax.sruvik.srugen.SruGenModelDeviceAssessment
import `in`.nispax.sruvik.srugen.SruGenModelInstaller
import `in`.nispax.sruvik.srugen.SruGenModelPackInstaller
import `in`.nispax.sruvik.srugen.SruGenDeviceGuard
import `in`.nispax.sruvik.ui.theme.SruvikColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ModelDetailPage(
    modelId: String,
    onBack: () -> Unit,
    onInstalled: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val model = remember(modelId) { SruGenGalleryCatalog.find(modelId) }
    var installed by remember(modelId) { mutableStateOf(false) }
    var checking by remember(modelId) { mutableStateOf(true) }
    var active by remember(modelId) { mutableStateOf(false) }
    var assessment by remember(modelId) { mutableStateOf<SruGenModelDeviceAssessment?>(null) }
    var acceptedRisk by remember(modelId) { mutableStateOf(false) }
    var downloadId by remember(modelId) { mutableLongStateOf(-1L) }
    var downloadPercent by remember(modelId) { mutableIntStateOf(0) }
    var installing by remember(modelId) { mutableStateOf(false) }
    var installLabel by remember(modelId) { mutableStateOf("") }
    var error by remember(modelId) { mutableStateOf("") }
    var info by remember(modelId) { mutableStateOf("") }

    suspend fun refreshState() {
        val result = withContext(Dispatchers.IO) {
            val ready = model?.let { SruGenEngineFactory.isInstalled(context, it.id) } == true
            val check = model?.let { SruGenModelCompatibility.assess(context, it) }
            val activeId = SruGenActiveModelStore.activeModelId(context)
            Triple(ready, check, activeId == modelId)
        }
        installed = result.first
        assessment = result.second
        active = result.third
        checking = false
    }

    LaunchedEffect(modelId) {
        refreshState()
        downloadId = when (model?.engine) {
            SruGenGalleryEngine.MEDIAPIPE_SD15 -> SruGenModelInstaller.savedDownloadId(context)
            SruGenGalleryEngine.NEZUMI_MNN_SD15 -> SruGenModelPackInstaller.savedDownloadId(context, model.id)
            null -> -1L
        }
    }

    LaunchedEffect(downloadId, modelId) {
        while (downloadId >= 0L && model != null && !installing) {
            val legacy = model.engine == SruGenGalleryEngine.MEDIAPIPE_SD15
            val status = withContext(Dispatchers.IO) {
                if (legacy) SruGenModelInstaller.queryDownload(context, downloadId)
                else SruGenModelPackInstaller.queryDownload(context, downloadId)
            }
            if (status == null) {
                delay(1000)
                continue
            }
            downloadPercent = status.percent
            val overLimit = !legacy && SruGenModelPackInstaller.exceedsApprovedDownloadLimit(model, status)
            val freeStorageLow = withContext(Dispatchers.IO) {
                runCatching { StatFs(context.filesDir.absolutePath).availableBytes < 750L * 1024L * 1024L }
                    .getOrDefault(true)
            }
            when {
                overLimit -> {
                    error = "The download exceeded the approved archive size. Sruvik cancelled and removed it."
                    withContext(Dispatchers.IO) { SruGenModelPackInstaller.cancelDownload(context, model.id, downloadId) }
                    downloadId = -1L
                }
                freeStorageLow -> {
                    error = "Sruvik stopped the download to preserve 750 MB of free internal storage."
                    withContext(Dispatchers.IO) {
                        if (legacy) SruGenModelInstaller.cancelDownload(context, downloadId)
                        else SruGenModelPackInstaller.cancelDownload(context, model.id, downloadId)
                    }
                    downloadId = -1L
                }
                status.status == DownloadManager.STATUS_FAILED -> {
                    error = "Download failed (reason ${status.reason}). Check your connection and storage, then retry."
                    withContext(Dispatchers.IO) {
                        if (legacy) SruGenModelInstaller.cancelDownload(context, model.id, downloadId)
                        else SruGenModelPackInstaller.cancelDownload(context, model.id, downloadId)
                    }
                    downloadId = -1L
                }
                status.status == DownloadManager.STATUS_SUCCESSFUL -> {
                    installing = true
                    installLabel = "Verifying downloaded package"
                    try {
                        val uri = withContext(Dispatchers.IO) {
                            if (legacy) {
                                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                manager.getUriForDownloadedFile(downloadId)
                            } else {
                                SruGenModelPackInstaller.downloadUri(context, downloadId)
                            }
                        } ?: error("The completed model archive could not be opened.")
                        withContext(Dispatchers.IO) {
                            val input = context.contentResolver.openInputStream(uri)
                                ?: throw IllegalStateException("Sruvik could not read the downloaded model archive.")
                            input.use {
                                if (legacy) {
                                    SruGenModelInstaller.importArchive(context, it) { label ->
                                        installLabel = label
                                    }
                                } else {
                                    SruGenModelPackInstaller.installArchive(context, model, it) { label ->
                                        installLabel = label
                                    }
                                }
                            }
                        }
                        // Close an older engine before switching the persisted model choice.
                        val oldEngine = SruGenEngineRegistry.engine
                        if (oldEngine != null) {
                            oldEngine.requestStop()
                            withContext(Dispatchers.IO) {
                                SruGenEngineRegistry.closeAndDetach(oldEngine)
                            }
                        }
                        SruGenActiveModelStore.setActiveModel(context, model.id)
                        info = "${model.name} is installed, checksum-verified and selected for SruGen."
                        error = ""
                        refreshState()
                    } catch (failure: Exception) {
                        error = failure.message ?: "Model installation failed."
                    } finally {
                        withContext(Dispatchers.IO) {
                            if (legacy) {
                                SruGenModelInstaller.clearDownloadId(context)
                                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
                            } else {
                                SruGenModelPackInstaller.removeDownload(context, downloadId)
                                SruGenModelPackInstaller.clearDownloadId(context, model.id)
                            }
                        }
                        installing = false
                        installLabel = ""
                        downloadId = -1L
                    }
                }
            }
            if (downloadId >= 0L) delay(900)
        }
    }

    val actualModel = model
    if (actualModel == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Text("Back") }
            Text("Model not found", color = SruvikColors.TextPrimary, style = MaterialTheme.typography.headlineMedium)
            Text("This model is no longer listed in the Sruvik catalogue.", color = SruvikColors.TextSecondary)
        }
        return
    }

    val status = assessment
    val installAllowed = !checking && !installed && actualModel.canInstall &&
        status?.canDownload == true && acceptedRisk && downloadId < 0L && !installing
    val runtimeReasons = remember(status, actualModel, installed) {
        val reasons = mutableListOf<String>()
        if (!installed) reasons += "Install the model before selecting it as active."
        val guard = runCatching { SruGenDeviceGuard.assessBeforeGeneration(context) }.getOrNull()
        reasons += guard?.reasons.orEmpty()
        if (android.os.Build.VERSION.SDK_INT < actualModel.minAndroidApi) {
            reasons += "This model requires Android API ${actualModel.minAndroidApi}."
        }
        if (actualModel.requiredAbis.isNotEmpty() &&
            actualModel.requiredAbis.none { it in android.os.Build.SUPPORTED_ABIS }) {
            reasons += "This model requires CPU architecture ${actualModel.requiredAbis.joinToString()}."
        }
        reasons.distinct()
    }
    val canSelectActive = installed && runtimeReasons.isEmpty() && acceptedRisk

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 16.dp, bottom = 128.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onBack,
                color = Color.White.copy(alpha = .07f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.ArrowBack, null, tint = SruvikColors.TextPrimary, modifier = Modifier.size(17.dp))
                    Text("MODEL LIBRARY", color = SruvikColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            if (active) ModelStatusChip("ACTIVE", Color(0xFF9FE2C1))
            else if (installed) ModelStatusChip("INSTALLED", SruvikColors.Lime)
        }

        Box(
            Modifier.fillMaxWidth().height(184.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF343C65), Color(0xFF272A49), Color(0xFF172B37))
                    ),
                    RoundedCornerShape(25.dp)
                )
                .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(25.dp))
        ) {
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(actualModel.runtime.uppercase(Locale.ROOT), color = Color(0xFFB8D9C0),
                    style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp)
                Text(actualModel.name, color = Color.White, style = MaterialTheme.typography.headlineLarge)
                Text(actualModel.subtitle, color = Color(0xFFD2D5DF), style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            actualModel.styleTags.forEach { tag -> ModelTag(tag) }
        }

        SectionTitle("THE STORY")
        Surface(
            color = Color(0x772F2F4E),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("“${actualModel.lore}”", color = Color(0xFFE5DDC8),
                    style = MaterialTheme.typography.titleMedium, lineHeight = 24.sp)
                Text(actualModel.description, color = SruvikColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
            }
        }

        SectionTitle("MODEL PROFILE")
        ModelDetailInfo("Base model", actualModel.baseModel)
        ModelDetailInfo("Runtime", actualModel.runtime)
        ModelDetailInfo("Model family", "Stable Diffusion 1.5 / MNN".takeIf {
            actualModel.engine == SruGenGalleryEngine.NEZUMI_MNN_SD15
        } ?: actualModel.baseModel)
        ModelDetailInfo("Model download", if (actualModel.approximateDownloadBytes > 0L) formatBytes(actualModel.approximateDownloadBytes) else "Not available yet")
        ModelDetailInfo("Recommended device memory", "${actualModel.recommendedRamGb} GB RAM")
        ModelDetailInfo("Android version", "Android API ${actualModel.minAndroidApi}+")
        ModelDetailInfo("Inference", "On-device · offline · 512 × 512")
        ModelDetailInfo("Attribution", actualModel.attribution)

        SectionTitle("DEVICE COMPATIBILITY")
        when {
            checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = SruvikColors.Lime, strokeWidth = 2.dp)
                Text("Checking this device…", color = SruvikColors.TextSecondary)
            }
            installed -> DeviceAssessmentCard(
                allowed = runtimeReasons.isEmpty(),
                heading = if (runtimeReasons.isEmpty()) "Installed · device check passed" else "Installed · runtime check blocked",
                reasons = runtimeReasons.ifEmpty { listOf("The current device passes Sruvik's conservative runtime preflight. This is not a guarantee of crash-free operation.") }
            )
            status != null -> DeviceAssessmentCard(
                allowed = status.canDownload,
                heading = if (status.canDownload) "Sruvik preflight passed" else "Download blocked on this device",
                reasons = if (status.canDownload) listOf(
                    "Android API ${status.androidApi}",
                    "Physical RAM: ${formatBytes(status.totalRamBytes)}",
                    "Currently available RAM: ${formatBytes(status.availableRamBytes)}",
                    "Free internal storage: ${formatBytes(status.freeStorageBytes)}",
                    "CPU ABI: ${status.supportedAbis.joinToString()}"
                ) else status.reasons
            )
            else -> DeviceAssessmentCard(false, "This model is not installable", listOf(actualModel.licenseNote))
        }

        if (actualModel.availability != SruGenGalleryAvailability.INSTALLABLE || !actualModel.canInstall) {
            Surface(
                color = Color(0x442B2B35),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .08f))
            ) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.Rounded.Info, null, tint = Color(0xFFE8B67B))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Not ready for installation", color = Color(0xFFE8B67B), fontWeight = FontWeight.SemiBold)
                        Text(
                            when (actualModel.availability) {
                                SruGenGalleryAvailability.PACK_VERIFICATION_PENDING -> "The package or model manifest is still being verified."
                                SruGenGalleryAvailability.CONVERSION_PENDING -> "The original checkpoint still needs a reproducible conversion to Sruvik's supported MNN format."
                                SruGenGalleryAvailability.RUNTIME_PENDING -> "The Android inference runtime for this model is not integrated yet."
                                else -> actualModel.licenseNote
                            },
                            color = SruvikColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        SectionTitle("SOURCE & LICENCE")
        ModelLinkRow("Open model source", actualModel.sourceUrl) {
            runCatching { uriHandler.openUri(actualModel.sourceUrl) }
        }
        ModelLinkRow("Read model licence", actualModel.licenseUrl) {
            runCatching { uriHandler.openUri(actualModel.licenseUrl) }
        }
        Text(actualModel.licenseNote, color = SruvikColors.TextMuted,
            style = MaterialTheme.typography.labelSmall, lineHeight = 17.sp)

        SectionTitle("BEFORE YOU INSTALL")
        Surface(
            color = Color(0x66372F27),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x66E7B77A))
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Rounded.Security, null, tint = Color(0xFFE8B67B))
                    Text("Third-party model warning", color = Color(0xFFE8B67B), fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Sruvik's device screening is a best-effort compatibility check, not a guarantee that a model is safe, stable, accurate or suitable for every use. Third-party model weights can behave unexpectedly. You choose to install and run them at your own discretion, and are responsible for reviewing and complying with the source licence. Sruvik and its publisher do not warrant third-party models or accept responsibility for model behaviour, output, device instability, data loss or damage, to the extent permitted by applicable law.",
                    color = SruvikColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(checked = acceptedRisk, onCheckedChange = { acceptedRisk = it })
                    Text(
                        "I have read the warning and model licence, and understand that passing Sruvik's device check is not a safety guarantee.",
                        Modifier.padding(top = 10.dp),
                        color = SruvikColors.TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        if (error.isNotBlank()) FeedbackPanel(error, isError = true)
        if (info.isNotBlank()) FeedbackPanel(info, isError = false)

        if (downloadId >= 0L || installing) {
            Surface(
                color = Color(0x77303448),
                shape = RoundedCornerShape(17.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .09f))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (installing) installLabel else "DOWNLOADING  ·  $downloadPercent%",
                        color = SruvikColors.Lime,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = .8.sp
                    )
                    LinearProgressIndicator(
                        progress = { if (installing) 0.5f else downloadPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = SruvikColors.Lime,
                        trackColor = Color.White.copy(alpha = .1f)
                    )
                    if (downloadId >= 0L) TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (actualModel.engine == SruGenGalleryEngine.MEDIAPIPE_SD15) {
                                    SruGenModelInstaller.cancelDownload(context, downloadId)
                                } else {
                                    SruGenModelPackInstaller.cancelDownload(context, actualModel.id, downloadId)
                                }
                            }
                            downloadId = -1L
                            error = "Download cancelled. Temporary files were removed."
                        }
                    }) { Text("CANCEL DOWNLOAD", color = SruvikColors.TextSecondary) }
                }
            }
        }

        if (installed) {
            Button(
                onClick = {
                    scope.launch {
                        val old = SruGenEngineRegistry.engine
                        if (old != null) {
                            old.requestStop()
                            withContext(Dispatchers.IO) { SruGenEngineRegistry.closeAndDetach(old) }
                        }
                        SruGenActiveModelStore.setActiveModel(context, actualModel.id)
                        active = true
                        info = "${actualModel.name} is now selected for SruGen. The engine will load only after the runtime preflight passes when generation starts."
                        error = ""
                    }
                },
                enabled = canSelectActive && !active && downloadId < 0L && !installing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF526A58), contentColor = Color.White)
            ) {
                Icon(Icons.Rounded.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text(if (active) "ACTIVE IN SRUGEN" else "SET AS ACTIVE MODEL")
            }
        } else {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            error = ""
                            info = ""
                            val fresh = withContext(Dispatchers.IO) {
                                SruGenModelCompatibility.assess(context, actualModel)
                            }
                            assessment = fresh
                            if (!fresh.canDownload) {
                                error = fresh.reasons.joinToString("\n")
                                return@launch
                            }
                            if (!acceptedRisk) {
                                error = "Read and acknowledge the third-party model warning before installing."
                                return@launch
                            }
                            if (!actualModel.canInstall) {
                                error = "This model package is not verified and cannot be downloaded."
                                return@launch
                            }
                            downloadId = withContext(Dispatchers.IO) {
                                when (actualModel.engine) {
                                    SruGenGalleryEngine.MEDIAPIPE_SD15 ->
                                        SruGenModelInstaller.startDownload(context)
                                    SruGenGalleryEngine.NEZUMI_MNN_SD15 ->
                                        SruGenModelPackInstaller.startDownload(context, actualModel)
                                    null -> error("This model does not have an installable runtime.")
                                }
                            }
                            info = "Download started. You can cancel it here at any time."
                        } catch (failure: Exception) {
                            error = failure.message ?: "Sruvik could not start this model download."
                        }
                    }
                },
                enabled = installAllowed,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SruvikColors.Lime,
                    contentColor = Color(0xFF172015),
                    disabledContainerColor = Color(0xFF303641),
                    disabledContentColor = SruvikColors.TextMuted
                )
            ) {
                Icon(Icons.Rounded.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        installed -> "INSTALLED"
                        !actualModel.canInstall -> "NOT READY TO INSTALL"
                        checking -> "CHECKING DEVICE…"
                        status?.canDownload == false -> "NOT COMPATIBLE WITH THIS DEVICE"
                        else -> "INSTALL MODEL  ·  ${formatBytes(actualModel.approximateDownloadBytes)}"
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelStatusChip(label: String, tint: Color) {
    Surface(color = tint.copy(alpha = .14f), shape = RoundedCornerShape(20.dp)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = tint, style = MaterialTheme.typography.labelSmall, letterSpacing = .7.sp)
    }
}

@Composable
private fun ModelTag(label: String) {
    Surface(color = Color.White.copy(alpha = .06f), shape = RoundedCornerShape(20.dp)) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            color = SruvikColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = SruvikColors.LimeMuted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.3.sp)
}

@Composable
private fun ModelDetailInfo(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, Modifier.width(122.dp), color = SruvikColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(1f), color = SruvikColors.TextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeviceAssessmentCard(allowed: Boolean, heading: String, reasons: List<String>) {
    Surface(
        color = if (allowed) Color(0x442C4B3C) else Color(0x443B342D),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (allowed) Color(0x5578B99B) else Color(0x55E7B77A)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(
                    if (allowed) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                    null,
                    tint = if (allowed) Color(0xFF9FE2C1) else Color(0xFFE8B67B)
                )
                Text(heading, color = if (allowed) Color(0xFF9FE2C1) else Color(0xFFE8B67B),
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
            reasons.forEach { reason ->
                Text("• $reason", color = SruvikColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun ModelLinkRow(label: String, url: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), color = SruvikColors.TextPrimary, style = MaterialTheme.typography.bodySmall)
        Text("OPEN  ↗", color = SruvikColors.LimeMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FeedbackPanel(text: String, isError: Boolean) {
    Surface(
        color = if (isError) Color(0x443D2B31) else Color(0x4430463A),
        shape = RoundedCornerShape(15.dp)
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(12.dp),
            color = if (isError) Color(0xFFF0A28E) else Color(0xFFB8D8C4),
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) String.format(Locale.US, "%.2f GB", gb)
    else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}
