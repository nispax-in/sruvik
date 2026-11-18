package `in`.nispax.sruvik.ui

import `in`.nispax.sruvik.ui.theme.SruvikColors


import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min
import kotlin.math.roundToInt

private val LabInk = SruvikColors.Background
private val LabCyan = Color(0xFFD1CCC7)
private val LabCoral = SruvikColors.Primary
private val LabGold = Color(0xFFF0B58F)
private val LabSurface = SruvikColors.Surface
private val LabText = Color(0xFFF5F3F1)
private val LabMuted = Color(0xFFC5C0BB)

@Composable
fun ImageUpscalerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by UpscaleSession.state.collectAsState()
    var selectedUri by remember { mutableStateOf(session.sourceUri?.let { Uri.parse(it) }) }
    var imageLoadRequest by remember { mutableIntStateOf(0) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableIntStateOf(session.scale) }
    var previewMode by remember { mutableIntStateOf(0) } // 0 original, 1 enhanced, 2 compare
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isQuickResizing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Choose a photo to start enhancing it.") }
    var optimizedSource by remember { mutableStateOf(false) }
    var showQuickResize by remember { mutableStateOf(false) }
    val isProcessing = session.status == UpscaleStatus.RUNNING || isQuickResizing
    val progress = session.progress
    val progressLabel = session.progressLabel

    // Compose may still be drawing the previous frame when state changes. Recycle
    // native Bitmap memory only after the next frame has dropped that reference.
    fun recycleAfterFrame(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        scope.launch {
            withFrameNanos { }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun resetResult() {
        val previous = result
        result = null
        recycleAfterFrame(previous)
        optimizedSource = false
        showQuickResize = false
        previewMode = 0
        UpscaleSession.clearResult()
    }

    // Drop the old preview immediately so a replacement image cannot be mistaken
    // for the newly selected file while its content URI is being decoded.
    fun beginImageLoad(uri: Uri, loadingMessage: String) {
        val previous = original
        original = null
        recycleAfterFrame(previous)
        isLoading = true
        selectedUri = uri
        imageLoadRequest++
        resetResult()
        UpscaleSession.selectSource(uri.toString())
        message = loadingMessage
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && !isProcessing && !isSaving) {
            retainSelectedImageAccess(context, uri)
            beginImageLoad(uri, "Loading photo…")
        } else if (uri == null) {
            message = "No photo selected. Try again or browse files."
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !isProcessing && !isSaving) {
            runCatching {
                retainSelectedImageAccess(context, uri)
            }
            beginImageLoad(uri, "Loading image from files…")
        } else if (uri == null) {
            message = "No file selected."
        }
    }
    fun launchImagePicker() {
        if (isProcessing || isSaving || isLoading) return
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (pickerError: Exception) {
            message = "Photo Picker could not open. Trying Files instead…"
            try {
                filePicker.launch(arrayOf("image/*"))
            } catch (filesError: Exception) {
                message = "Unable to open an image picker: ${filesError.message ?: pickerError.message ?: "unknown error"}"
            }
        }
    }
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val bitmap = result
        if (uri != null && bitmap != null && !isSaving && !isProcessing) {
            scope.launch {
                isSaving = true
                message = "Saving enhanced image…"
                try {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: error("Couldn't open the selected save location.")
                        stream.use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                                "Image export failed."
                            }
                        }
                    }
                    message = "Saved. Your image is ready."
                } catch (error: Exception) {
                    message = error.message ?: "Couldn't save this image."
                } finally {
                    isSaving = false
                }
            }
        }
    }

    var pendingUpscale by remember { mutableStateOf<Pair<Uri, Int>?>(null) }

    fun launchUpscale(uri: Uri, requestedScale: Int, notificationsEnabled: Boolean) {
        resetResult()
        showQuickResize = false
        val startIntent = Intent(context, ImageUpscaleService::class.java).apply {
            action = ImageUpscaleService.ACTION_START
            putExtra(ImageUpscaleService.EXTRA_URI, uri.toString())
            putExtra(ImageUpscaleService.EXTRA_SCALE, requestedScale)
            putExtra(ImageUpscaleService.EXTRA_NOTIFICATIONS_ENABLED, notificationsEnabled)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
            if (!notificationsEnabled) {
                message = "Upscaling has started. Notifications are off, so Android may show this task only under Active apps."
            }
        } catch (error: Exception) {
            UpscaleSession.failed(error.message ?: "Android couldn't start the background image task.")
            message = error.message ?: "Android couldn't start the background image task."
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingUpscale
        pendingUpscale = null
        if (pending != null) {
            launchUpscale(
                pending.first,
                pending.second,
                granted && (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
            )
        }
    }

    fun requestUpscale(uri: Uri, requestedScale: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingUpscale = uri to requestedScale
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchUpscale(
                uri,
                requestedScale,
                notificationManager.areNotificationsEnabled()
            )
        }
    }

    LaunchedEffect(session.resultPath, session.status, original) {
        if (session.status == UpscaleStatus.COMPLETED && !session.resultPath.isNullOrBlank()) {
            val path = requireNotNull(session.resultPath)
            val restored = withContext(Dispatchers.IO) { decodeSavedPreview(path) }
            if (restored != null) {
                val previous = result
                result = restored
                previewMode = 1
                recycleAfterFrame(previous)
                optimizedSource = session.sourceWasOptimized
                showQuickResize = false
                message = session.message
            } else {
                message = "The enhanced image file couldn't be reopened. Try enhancing the photo again."
            }
        } else if (session.status == UpscaleStatus.FAILED) {
            showQuickResize = original != null
            message = session.message
        } else if (session.status == UpscaleStatus.CANCELLED) {
            message = session.message
        }
    }

    LaunchedEffect(selectedUri, imageLoadRequest) {
        val uri = selectedUri ?: return@LaunchedEffect
        val requestId = imageLoadRequest
        isLoading = true
        message = "Reading your photo…"
        try {
            val decoded = withContext(Dispatchers.IO) { decodeImage(context, uri) }
            val previous = original
            original = decoded
            recycleAfterFrame(previous)
            message = "Photo ready. Choose your AI output size."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: OutOfMemoryError) {
            val previous = original
            original = null
            recycleAfterFrame(previous)
            message = "This photo is too large to preview safely. Choose a smaller image."
        } catch (error: Exception) {
            val previous = original
            original = null
            recycleAfterFrame(previous)
            message = error.message ?: "Couldn't read this image."
        } finally {
            // A cancelled older decode must not clear the loading state for a
            // newer image selection.
            if (imageLoadRequest == requestId) isLoading = false
        }
    }

    val latestOriginalForDispose = rememberUpdatedState(original)
    val latestResultForDispose = rememberUpdatedState(result)
    DisposableEffect(Unit) {
        onDispose {
            latestOriginalForDispose.value?.takeUnless { it.isRecycled }?.recycle()
            latestResultForDispose.value?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, enabled = !isSaving, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to studio", tint = LabText)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Image upscaler", color = LabText,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Restore detail on your device", color = LabMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
            Surface(
                color = LabCyan.copy(alpha = .10f),
                shape = RoundedCornerShape(30.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(LabCyan))
                    Text("LOCAL", color = LabCyan, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold)
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LabSurface,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .07f))
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PHOTO CANVAS", color = LabMuted,
                            style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp)
                        Text(
                            when {
                                isLoading -> "Loading image…"
                                original != null -> "Ready to enhance"
                                else -> "Start with a photo"
                            },
                            color = LabText, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    original?.let {
                        Text("${it.width} × ${it.height}", color = LabMuted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (result != null && original != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Original", "Enhanced", "Compare").forEachIndexed { index, label ->
                            FilterChip(
                                selected = previewMode == index,
                                onClick = { previewMode = index },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                label = {
                                    Text(label, maxLines = 1,
                                        style = MaterialTheme.typography.labelMedium)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LabCyan.copy(alpha = .16f),
                                    selectedLabelColor = LabCyan,
                                    labelColor = LabMuted
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = previewMode == index,
                                    borderColor = Color.White.copy(alpha = .12f),
                                    selectedBorderColor = LabCyan.copy(alpha = .65f)
                                )
                            )
                        }
                    }
                }

                when {
                    original == null -> {
                        Box(
                            Modifier.fillMaxWidth().height(248.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFF111716))
                                .clickable(enabled = !isLoading && !isProcessing && !isSaving) {
                                    launchImagePicker()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(color = LabCyan)
                                    Text("Reading photo…", color = LabText)
                                } else {
                                    Icon(Icons.Rounded.AddPhotoAlternate, null,
                                        tint = LabCyan, modifier = Modifier.size(38.dp))
                                    Text("Choose a photo to begin", color = LabText,
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("JPG · PNG · WebP", color = LabMuted,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    result != null && previewMode == 2 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            original?.let {
                                ComparisonCard(
                                    "ORIGINAL", "${it.width} × ${it.height}", it,
                                    Modifier.weight(1f), LabMuted
                                )
                            }
                            result?.let {
                                ComparisonCard(
                                    if (optimizedSource) "RESIZED" else "ENHANCED",
                                    "${it.width} × ${it.height}", it,
                                    Modifier.weight(1f), LabCyan
                                )
                            }
                        }
                    }
                    result != null && previewMode == 1 -> {
                        Box {
                            ImagePreview(result!!, Modifier.fillMaxWidth().height(248.dp))
                            Surface(
                                Modifier.align(Alignment.BottomStart).padding(10.dp),
                                color = Color(0xD9101715), shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    if (optimizedSource) "BASIC RESIZE · NOT AI" else "AI ENHANCED",
                                    Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                    color = if (optimizedSource) LabGold else LabCyan,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    else -> {
                        ImagePreview(requireNotNull(original), Modifier.fillMaxWidth().height(248.dp))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(
                        onClick = { launchImagePicker() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = !isProcessing && !isSaving && !isLoading,
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (original == null) "Photos" else "Change photo")
                    }
                    OutlinedButton(
                        onClick = {
                            if (!isProcessing && !isSaving && !isLoading) {
                                runCatching { filePicker.launch(arrayOf("image/*")) }
                                    .onFailure { failure ->
                                        message = "Android couldn't open Files: ${failure.message ?: "unknown error"}"
                                    }
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = !isProcessing && !isSaving && !isLoading,
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Browse files")
                    }
                }

                if (isLoading || original == null || message.startsWith("Couldn't") ||
                    message.startsWith("This photo") || message.startsWith("No ")) {
                    val loadError = !isLoading &&
                        (message.startsWith("Couldn't") || message.startsWith("This photo"))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (loadError) LabCoral.copy(alpha = .10f)
                                else Color.White.copy(alpha = .035f))
                            .padding(11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (loadError) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                            contentDescription = null,
                            tint = if (loadError) LabCoral else LabMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(message, Modifier.padding(start = 8.dp), color = if (loadError) LabCoral else LabMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LabSurface,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .07f))
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OUTPUT", color = LabMuted,
                            style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp)
                        Text("Choose enlargement", color = LabText,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Real-ESRGAN x4v3", color = LabCyan,
                        style = MaterialTheme.typography.labelSmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4).forEach { option ->
                        FilterChip(
                            selected = scale == option,
                            onClick = {
                                if (!isProcessing) {
                                    scale = option
                                    resetResult()
                                }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            label = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${option}×", style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold)
                                    Text(
                                        if (option == 2) "Smaller export" else "Full output",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            },
                            leadingIcon = if (scale == option) {
                                { Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LabCyan.copy(alpha = .14f),
                                selectedLabelColor = LabCyan,
                                labelColor = LabMuted,
                                selectedLeadingIconColor = LabCyan
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = scale == option,
                                borderColor = Color.White.copy(alpha = .13f),
                                selectedBorderColor = LabCyan.copy(alpha = .6f)
                            )
                        )
                    }
                }

                Text(
                    if (scale == 2) "The x4 AI result is downsampled to a 2× export for a smaller file."
                    else "Uses the full 4× super-resolution output.",
                    color = LabMuted, style = MaterialTheme.typography.bodySmall
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Memory, null, tint = LabMuted, modifier = Modifier.size(17.dp))
                    Text(
                        "GPU when supported · CPU fallback · tiled for memory safety",
                        Modifier.padding(start = 7.dp),
                        color = LabMuted, style = MaterialTheme.typography.labelSmall
                    )
                }

                Button(
                    onClick = {
                        val uri = selectedUri ?: return@Button
                        requestUpscale(uri, scale)
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    enabled = original != null && !isLoading && !isProcessing && !isSaving,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LabCyan,
                        contentColor = LabInk,
                        disabledContainerColor = Color.White.copy(alpha = .08f),
                        disabledContentColor = LabMuted
                    )
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(Modifier.size(19.dp), color = LabInk, strokeWidth = 2.dp)
                        Text("  Enhancing…", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Rounded.AutoFixHigh, null)
                        Text("  Enhance photo", fontWeight = FontWeight.Bold)
                    }
                }

                if (isProcessing) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = LabCyan, trackColor = Color.White.copy(alpha = .10f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progressLabel, color = LabMuted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f))
                        Text("${(progress * 100).roundToInt()}%", color = LabCyan,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (session.notificationsEnabled)
                            "You can leave this screen; processing continues in the background."
                        else
                            "Notifications are off. Progress may only appear under Android Active apps.",
                        color = LabMuted, style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = {
                            context.startService(
                                Intent(context, ImageUpscaleService::class.java)
                                    .setAction(ImageUpscaleService.ACTION_CANCEL)
                            )
                        },
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = LabCoral)
                        Text("Cancel", color = LabCoral)
                    }
                } else {
                    val statusMessage = if (session.status == UpscaleStatus.IDLE) message else session.message
                    Text(statusMessage, color = when {
                        session.status == UpscaleStatus.FAILED -> LabCoral
                        result != null -> LabCyan
                        else -> LabMuted
                    }, style = MaterialTheme.typography.bodySmall)
                }

                if (showQuickResize && original != null) {
                    OutlinedButton(
                        onClick = {
                            val source = original ?: return@OutlinedButton
                            UpscaleSession.clearResult()
                            scope.launch {
                                isQuickResizing = true
                                try {
                                    result = withContext(Dispatchers.Default) {
                                        quickResize(source, scale, context)
                                    }
                                    optimizedSource = true
                                    previewMode = 1
                                    message = "Basic resize complete. This fallback does not reconstruct detail with AI."
                                    showQuickResize = false
                                } catch (error: Exception) {
                                    message = error.message ?: "Quick resize failed."
                                } finally {
                                    isQuickResizing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text("Use basic resize instead · not AI")
                    }
                }
            }
        }

        if (result != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (optimizedSource) "RESIZED PREVIEW" else "ENHANCED IMAGE",
                        color = LabCyan, style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.1.sp
                    )
                    Spacer(Modifier.weight(1f))
                    val output = requireNotNull(result)
                    Text("${output.width} × ${output.height}", color = LabMuted,
                        style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = { saver.launch("sruvik-ai-upscaled.png") },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isSaving && !isProcessing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LabCyan, contentColor = LabInk
                    )
                ) {
                    Icon(Icons.Rounded.Download, null)
                    Text(if (isSaving) "  Saving…" else "  Save image to device",
                        fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Rounded.Shield, null, tint = LabCyan, modifier = Modifier.size(17.dp))
            Text(
                "  Photo pixels are processed on this device. GPU runtime setup may happen through Google Play services.",
                color = LabMuted, style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ComparisonCard(title: String, size: String, bitmap: Bitmap, modifier: Modifier, accent: Color) {
    Surface(
        modifier = modifier, color = SruvikColors.Surface, shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .22f))
    ) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = accent, style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            ImagePreview(bitmap, Modifier.fillMaxWidth().height(164.dp))
            Text(size, color = LabMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ImagePreview(bitmap: Bitmap, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(17.dp)).background(Color(0xFF101010)),
        contentAlignment = Alignment.Center) {
        Image(bitmap.asImageBitmap(), "Image preview",
            Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
    }
}

/**
 * Keep the URI returned by either Android picker readable for the current processing
 * session, and across a process restart when the provider supports persistable grants.
 * Photo Picker and Storage Access Framework grants are item-scoped; broad storage
 * permissions are intentionally not requested.
 */
private fun retainSelectedImageAccess(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

/**
 * Decode picker URIs through Android's URI-aware decoder where available. Some
 * document providers expose typed assets or file descriptors instead of a normal
 * InputStream, so the legacy path tries all supported resolver access methods.
 */
internal fun decodeImage(context: Context, uri: Uri): Bitmap {
    var modernDecodeError: Exception? = null

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(previewSampleSize(info.size.width, info.size.height))
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            }
        } catch (error: Exception) {
            modernDecodeError = error
        }
    }

    return try {
        decodeWithBitmapFactory(context, uri)
    } catch (error: Exception) {
        modernDecodeError?.let(error::addSuppressed)
        throw error
    }
}

private fun decodeSavedPreview(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
        bounds.outWidth > MAX_SOURCE_DIMENSION || bounds.outHeight > MAX_SOURCE_DIMENSION
    ) return null
    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}

internal fun previewSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (
        (width.toLong() / sample.toLong()) *
            (height.toLong() / sample.toLong()) > MAX_PREVIEW_PIXELS ||
        maxOf(width / sample, height / sample) > MAX_PREVIEW_DIMENSION
    ) sample *= 2
    return sample
}

private fun openSelectedImageStream(context: Context, uri: Uri): InputStream {
    val resolver = context.contentResolver
    var lastError: Exception? = null

    fun attempt(open: () -> InputStream?): InputStream? = try {
        open()
    } catch (error: Exception) {
        lastError = error
        null
    }

    attempt { resolver.openInputStream(uri) }?.let { return it }
    attempt {
        resolver.openTypedAssetFileDescriptor(uri, "image/*", null)?.createInputStream()
    }?.let { return it }
    attempt { resolver.openAssetFileDescriptor(uri, "r")?.createInputStream() }
        ?.let { return it }
    attempt {
        resolver.openFileDescriptor(uri, "r")?.let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        }
    }?.let { return it }

    if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
        attempt { FileInputStream(uri.path!!) }?.let { return it }
    }

    val explanation = when (lastError) {
        is SecurityException ->
            "Android denied access to this image. Select it again, or use Browse files."
        else ->
            "The selected provider did not return a readable image. Download the photo to this device, then use Browse files."
    }
    throw IOException("Couldn't read this photo. $explanation", lastError)
}

private fun decodeWithBitmapFactory(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openSelectedImageStream(context, uri).use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
        bounds.outWidth > MAX_SOURCE_DIMENSION || bounds.outHeight > MAX_SOURCE_DIMENSION
    ) {
        throw IOException("This image has invalid or unsupported dimensions. Choose a smaller JPG, PNG, or WebP photo.")
    }

    val decoded = openSelectedImageStream(context, uri).use { stream ->
        BitmapFactory.decodeStream(
            stream,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    } ?: throw IOException("Couldn't decode this image. Try another JPG, PNG, or WebP file.")

    val oriented = applyExifOrientation(context, uri, decoded)
    if (oriented !== decoded) decoded.recycle()
    return oriented
}

private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun quickResize(source: Bitmap, requestedScale: Int, context: Context): Bitmap {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    check(!info.lowMemory && info.availMem > 220L * 1024L * 1024L) {
        "Your phone is low on free memory. Try after closing a few apps."
    }
    val pixelCap = if (manager.isLowRamDevice) 2_000_000L else 4_000_000L
    val maxSource = pixelCap / (requestedScale * requestedScale)
    val pixelScale = kotlin.math.sqrt(maxSource.toDouble() /
        (source.width.toLong() * source.height)).toFloat().coerceAtMost(1f)
    val dimScale = 6144f / (maxOf(source.width, source.height) * requestedScale)
    val factor = min(pixelScale, dimScale).coerceAtMost(1f)
    check(factor * requestedScale >= 1.25f) {
        "Quick resize cannot safely create a meaningfully larger image on this device. " +
            "Use a smaller source photo or free up device memory."
    }
    val input = if (factor < .999f) Bitmap.createScaledBitmap(
        source, (source.width * factor).roundToInt().coerceAtLeast(1),
        (source.height * factor).roundToInt().coerceAtLeast(1), true
    ) else source
    return try {
        Bitmap.createScaledBitmap(input, input.width * requestedScale,
            input.height * requestedScale, true)
    } finally {
        if (input !== source) input.recycle()
    }
}

private const val MAX_PREVIEW_PIXELS = 4_000_000L
private const val MAX_PREVIEW_DIMENSION = 4096
private const val MAX_SOURCE_DIMENSION = 100_000
