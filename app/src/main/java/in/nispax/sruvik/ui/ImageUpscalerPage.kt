package `in`.nispax.sruvik.ui

import `in`.nispax.sruvik.R

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private val LabInk = Color(0xFF0B0B0B)
private val LabViolet = Color(0xFFE98261)
private val LabCyan = Color(0xFFD1CCC7)
private val LabCoral = Color(0xFFE98261)
private val LabGold = Color(0xFFF0B58F)
private val LabSurface = Color(0xFF1B1B1B)
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
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isQuickResizing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Choose a photo to start enhancing it.") }
    var optimizedSource by remember { mutableStateOf(false) }
    var showQuickResize by remember { mutableStateOf(false) }
    val isProcessing = session.status == UpscaleStatus.RUNNING || isQuickResizing
    val progress = session.progress
    val progressLabel = session.progressLabel

    fun resetResult() {
        result?.recycle()
        result = null
        optimizedSource = false
        showQuickResize = false
        UpscaleSession.clearResult()
    }

    // Drop the old preview immediately so a replacement image cannot be mistaken
    // for the newly selected file while its content URI is being decoded.
    fun beginImageLoad(uri: Uri, loadingMessage: String) {
        original?.recycle()
        original = null
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
            val path = session.resultPath
            val restored = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            if (restored != null) {
                result?.recycle()
                result = restored
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
        isLoading = true
        message = "Reading your photo…"
        try {
            val decoded = withContext(Dispatchers.IO) { decodeImage(context, uri) }
            original?.recycle()
            original = decoded
            message = "Photo ready. Choose your AI output size."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: OutOfMemoryError) {
            original?.recycle()
            original = null
            message = "This photo is too large to preview safely. Choose a smaller image."
        } catch (error: Exception) {
            original?.recycle()
            original = null
            message = error.message ?: "Couldn't read this image."
        } finally {
            isLoading = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, enabled = !isSaving) {
                Icon(Icons.Rounded.ArrowBack, null, tint = LabMuted)
                Text("  STUDIO", color = LabMuted, style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 1.4.sp)
            }
            Spacer(Modifier.weight(1f))
            Surface(color = Color(0xFF252525), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(LabCyan))
                    Text("  ON-DEVICE AI", color = LabCyan,
                        style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().height(194.dp).clip(RoundedCornerShape(27.dp))
        ) {
            Image(
                painter = painterResource(R.drawable.sruvik_alpine_art),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x16212D4A), Color(0x8C101727), Color(0xF20C1421)))
            ))
            Column(
                Modifier.fillMaxSize().padding(19.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text("IMAGE RESTORATION", color = LabCoral,
                    style = MaterialTheme.typography.labelLarge, letterSpacing = 2.3.sp)
                Text("Bring back the\ndetail.", color = LabText,
                    style = MaterialTheme.typography.displayMedium, lineHeight = 40.sp)
                Text("Reconstruct fine detail and enlarge your photo with a local AI model.",
                    color = LabMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LabSurface,
            shape = RoundedCornerShape(28.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .09f))
        ) {
            Column(
                Modifier.background(Brush.linearGradient(
                    listOf(Color(0xFF262626), Color(0xFF1D1D1D), Color(0xFF141414))
                )).padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(43.dp).clip(RoundedCornerShape(15.dp))
                        .background(LabViolet.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoFixHigh, null, tint = LabViolet)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("REAL-ESRGAN", color = LabText,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("General x4v3  ·  1.21M parameters", color = LabMuted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Text("AI MODEL", color = LabGold,
                        style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ModelTag("4× detail")
                    ModelTag("Noise aware")
                    ModelTag("Local processing")
                }
                Text(
                    "The model reconstructs at 4×. Choose 2× to downsample its AI result for a smaller file.",
                    color = Color(0xFFD0D7E4), style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LabSurface,
            shape = RoundedCornerShape(26.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Image, null, tint = LabCyan)
                    Text("  SOURCE PHOTO", color = LabText,
                        style = MaterialTheme.typography.labelLarge, letterSpacing = 1.4.sp)
                    Spacer(Modifier.weight(1f))
                    original?.let {
                        Text("${it.width} × ${it.height}", color = LabMuted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (original != null) {
                    ImagePreview(original!!, Modifier.fillMaxWidth().height(190.dp))
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(20.dp))
                            .background(Brush.linearGradient(
                                listOf(Color(0xFF292929), Color(0xFF202020), Color(0xFF161616))
                            ))
                            .clickable(enabled = !isProcessing && !isSaving && !isLoading) {
                                launchImagePicker()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(34.dp),
                                    color = LabCyan,
                                    strokeWidth = 3.dp
                                )
                                Text("Loading selected image…", color = LabText,
                                    fontWeight = FontWeight.SemiBold)
                            } else {
                                Icon(Icons.Rounded.AddPhotoAlternate, null, tint = LabCyan,
                                    modifier = Modifier.size(37.dp))
                                Text("Choose an image", color = LabText,
                                    fontWeight = FontWeight.SemiBold)
                                Text("JPG  ·  PNG  ·  WebP", color = LabMuted,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(
                        onClick = {
                            launchImagePicker()
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        enabled = !isProcessing && !isSaving && !isLoading
                    ) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null)
                        Text(if (original == null) "Photo library" else "Change photo")
                    }
                    OutlinedButton(
                        onClick = { launchImagePicker() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        enabled = !isProcessing && !isSaving && !isLoading
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null)
                        Text("Browse files")
                    }
                }
                if (isLoading || original == null || message.startsWith("Couldn't") ||
                    message.startsWith("This photo") || message.startsWith("No ")) {
                    val isLoadError = !isLoading &&
                        (message.startsWith("Couldn't") || message.startsWith("This photo"))
                    Surface(
                        color = if (isLoadError) LabCoral.copy(alpha = .10f)
                            else Color.White.copy(alpha = .045f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isLoadError) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                                contentDescription = null,
                                tint = if (isLoadError) LabCoral else LabMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                message,
                                modifier = Modifier.padding(start = 9.dp),
                                color = if (isLoadError) LabCoral else LabMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Text(
                    "Access is limited to the image you choose. No full-library storage permission is needed.",
                    color = LabMuted.copy(alpha = .85f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF2A2A2A),
            shape = RoundedCornerShape(25.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LabViolet.copy(alpha = .24f))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("OUTPUT SIZE", color = LabText,
                        style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp)
                    Spacer(Modifier.weight(1f))
                    Text("AI SUPER-RESOLUTION", color = LabViolet,
                        style = MaterialTheme.typography.labelSmall, letterSpacing = .8.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    listOf(2, 4).forEach { option ->
                        FilterChip(
                            selected = scale == option,
                            onClick = { if (!isProcessing) { scale = option; resetResult() } },
                            label = {
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text("${option}×", style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold)
                                    Text(if (option == 4) "4× output" else "2× output",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            leadingIcon = if (scale == option) {
                                { Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LabViolet.copy(alpha = .22f),
                                selectedLabelColor = Color.White,
                                labelColor = LabMuted,
                                selectedLeadingIconColor = LabCyan
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = scale == option,
                                borderColor = Color.White.copy(alpha = .15f),
                                selectedBorderColor = LabViolet
                            )
                        )
                    }
                }
                Text(
                    "Progress notifications let the enhancement continue visibly while you switch tabs or apps.",
                    color = LabMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        val uri = selectedUri ?: return@Button
                        requestUpscale(uri, scale)
                    },
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    enabled = original != null && !isLoading && !isProcessing && !isSaving,
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LabViolet, contentColor = LabInk,
                        disabledContainerColor = Color(0xFF45413F),
                        disabledContentColor = Color(0xFF96918D)
                    )
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(Modifier.size(19.dp), color = LabInk, strokeWidth = 2.dp)
                        Text("  Enhancing…", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Rounded.AutoFixHigh, null)
                        Text("  Enhance with AI", fontWeight = FontWeight.Bold)
                    }
                }

                if (isProcessing) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = LabCyan, trackColor = Color.White.copy(alpha = .10f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progressLabel, color = LabMuted, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f))
                        Text("${(progress * 100).roundToInt()}%", color = LabCyan,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    if (session.notificationsEnabled) {
                        Text(
                            "This task keeps running when you switch tabs or leave Sruvik in Recents.",
                            color = LabMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Text(
                            "Notifications are off. Android may show this task under Active apps, but hide progress from the notification shade.",
                            color = LabGold,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(
                        onClick = {
                            context.startService(
                                Intent(context, ImageUpscaleService::class.java)
                                    .setAction(ImageUpscaleService.ACTION_CANCEL)
                            )
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = LabCoral)
                        Text("  Cancel", color = LabCoral)
                    }
                } else {
                    val currentMessage = if (session.status == UpscaleStatus.IDLE) message else session.message
                    Text(currentMessage, color = if (result != null) LabCyan else LabMuted,
                        style = MaterialTheme.typography.bodySmall)
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
                                    message = "Basic resize complete. This fallback does not reconstruct detail with AI."
                                    showQuickResize = false
                                } catch (error: Exception) {
                                    message = error.message ?: "Quick resize failed."
                                } finally {
                                    isQuickResizing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)
                    ) {
                        Text("Use quick resize instead  ·  not AI")
                    }
                }
            }
        }

        AnimatedVisibility(visible = result != null, enter = fadeIn() + slideInVertically { it / 6 }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BEFORE / AFTER", color = LabCyan,
                        style = MaterialTheme.typography.labelLarge, letterSpacing = 1.7.sp)
                    Spacer(Modifier.weight(1f))
                    val actualScale = if (original != null && result != null) {
                        minOf(
                            result!!.width.toFloat() / original!!.width,
                            result!!.height.toFloat() / original!!.height
                        )
                    } else 0f
                    val actualScaleLabel = ((actualScale * 100f).roundToInt() / 100f)
                    Text(
                        if (result != null) "ACTUAL ${actualScaleLabel}× OUTPUT" else "READY",
                        color = LabGold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    original?.let {
                        ComparisonCard("ORIGINAL", "${it.width} × ${it.height}", it,
                            Modifier.weight(1f), LabMuted)
                    }
                    result?.let {
                        ComparisonCard("ENHANCED", "${it.width} × ${it.height}", it,
                            Modifier.weight(1f), LabCyan)
                    }
                }
                result?.let {
                    Button(
                        onClick = { saver.launch("sruvik-ai-upscaled.png") },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(17.dp),
                        enabled = !isSaving && !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = LabCyan, contentColor = LabInk)
                    ) {
                        Icon(Icons.Rounded.Download, null)
                        Text(if (isSaving) "  Saving…" else "  Save enhanced PNG", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Surface(
            color = Color(0xFF202020), shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LabCyan.copy(alpha = .14f))
        ) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Shield, null, tint = LabCyan, modifier = Modifier.size(18.dp))
                Text(
                    "  Your photo is processed locally and is never uploaded. The first run may prepare the LiteRT runtime through Google Play services.",
                    color = LabMuted, style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun ModelTag(label: String) {
    Surface(color = Color.White.copy(alpha = .08f), shape = CircleShape) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color(0xFFD7E5EE), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ComparisonCard(title: String, size: String, bitmap: Bitmap, modifier: Modifier, accent: Color) {
    Surface(
        modifier = modifier, color = Color(0xFF1B1B1B), shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .22f))
    ) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = accent, style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            ImagePreview(bitmap, Modifier.fillMaxWidth().height(126.dp))
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

private fun previewSampleSize(width: Int, height: Int): Int {
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
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IOException("Couldn't decode this image. Choose a JPG, PNG, or WebP photo.")
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
