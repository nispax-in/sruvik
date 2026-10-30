package `in`.nispax.sruvik.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.nispax.sruvik.ai.DeviceThermalLimitException
import `in`.nispax.sruvik.ai.RealEsrganUpscaler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private val LabInk = Color(0xFF0D1421)
private val LabViolet = Color(0xFFB6A1FF)
private val LabCyan = Color(0xFF78DFE4)
private val LabCoral = Color(0xFFFF9A83)
private val LabGold = Color(0xFFFFD08A)
private val LabSurface = Color(0xFF172334)
private val LabText = Color(0xFFF4F4FA)
private val LabMuted = Color(0xFFAEB9C9)

@Composable
fun ImageUpscalerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var imageLoadRequest by remember { mutableIntStateOf(0) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableIntStateOf(4) }
    var isLoading by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Choose a photo to start enhancing it.") }
    var optimizedSource by remember { mutableStateOf(false) }
    var processingJob by remember { mutableStateOf<Job?>(null) }
    var showQuickResize by remember { mutableStateOf(false) }

    fun resetResult() {
        result = null
        optimizedSource = false
        progress = 0f
        showQuickResize = false
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && !isProcessing && !isSaving) {
            selectedUri = uri
            imageLoadRequest++
            resetResult()
            message = "Loading photo…"
        } else if (uri == null) {
            message = "No photo selected. Try again or browse files."
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !isProcessing && !isSaving) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            selectedUri = uri
            imageLoadRequest++
            resetResult()
            message = "Loading image from files…"
        } else if (uri == null) {
            message = "No file selected."
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
            TextButton(onClick = onBack, enabled = !isProcessing && !isSaving) {
                Icon(Icons.Rounded.ArrowBack, null, tint = LabMuted)
                Text("  STUDIO", color = LabMuted, style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 1.4.sp)
            }
            Spacer(Modifier.weight(1f))
            Surface(color = Color(0xFF163B3E), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(LabCyan))
                    Text("  ON-DEVICE AI", color = LabCyan,
                        style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("IMAGE RESTORATION", color = LabCoral,
                style = MaterialTheme.typography.labelLarge, letterSpacing = 2.3.sp)
            Text("Bring back the\ndetail.", color = LabText,
                style = MaterialTheme.typography.displayMedium, lineHeight = 40.sp)
            Text("Reconstruct fine detail and enlarge your photo with a local AI model.",
                color = LabMuted, style = MaterialTheme.typography.bodyMedium)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LabSurface,
            shape = RoundedCornerShape(28.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .09f))
        ) {
            Column(
                Modifier.background(Brush.linearGradient(
                    listOf(Color(0xFF25344F), Color(0xFF27233F), Color(0xFF17313C))
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
                                listOf(Color(0xFF34435D), Color(0xFF483A5C), Color(0xFF25515B))
                            ))
                            .clickable(enabled = !isProcessing && !isSaving && !isLoading) {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Rounded.AddPhotoAlternate, null, tint = LabCyan,
                                modifier = Modifier.size(37.dp))
                            Text("Choose an image", color = LabText, fontWeight = FontWeight.SemiBold)
                            Text("JPG  ·  PNG  ·  WebP", color = LabMuted,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        enabled = !isProcessing && !isSaving && !isLoading
                    ) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null)
                        Text(if (original == null) "Photo library" else "Change photo")
                    }
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        enabled = !isProcessing && !isSaving && !isLoading
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null)
                        Text("Browse files")
                    }
                }
                if (original == null || message.startsWith("Couldn't") ||
                    message.startsWith("This photo") || message.startsWith("No ")) {
                    Text(
                        message,
                        color = if (message.startsWith("Couldn't") || message.startsWith("This photo"))
                            LabCoral else LabMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF29253E),
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
                                    Text(if (option == 4) "Full AI output" else "AI then resize",
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
                Button(
                    onClick = {
                        val source = original ?: return@Button
                        processingJob = scope.launch {
                            isProcessing = true
                            progress = 0f
                            progressLabel = "Preparing on-device model…"
                            message = "Starting local AI…"
                            result = null
                            showQuickResize = false
                            try {
                                val processed = RealEsrganUpscaler.upscale(context, source, scale) { done, total ->
                                    progress = done.toFloat() / total.toFloat()
                                    progressLabel = "Reconstructing tile $done of $total"
                                }
                                result = processed.bitmap
                                optimizedSource = processed.sourceWasOptimized
                                message = if (processed.sourceWasOptimized) {
                                    "AI complete. Source resolution was reduced to stay within this phone's memory budget."
                                } else {
                                    "AI enhancement complete. Your image stayed on this device."
                                }
                            } catch (cancelled: CancellationException) {
                                message = "Processing cancelled. No file was saved."
                                throw cancelled
                            } catch (error: OutOfMemoryError) {
                                message = "The phone ran out of memory. Try a smaller source image."
                                showQuickResize = true
                            } catch (error: Exception) {
                                message = if (error is DeviceThermalLimitException) {
                                    error.message ?: "Phone is too warm."
                                } else error.message ?: "AI enhancement couldn't start on this device."
                                showQuickResize = true
                            } finally {
                                isProcessing = false
                                processingJob = null
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    enabled = original != null && !isLoading && !isProcessing && !isSaving,
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LabViolet, contentColor = LabInk,
                        disabledContainerColor = Color(0xFF494559),
                        disabledContentColor = Color(0xFFA7A1B7)
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
                    TextButton(onClick = { processingJob?.cancel() }, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Rounded.Close, null, tint = LabCoral)
                        Text("  Cancel", color = LabCoral)
                    }
                } else {
                    Text(message, color = if (result != null) LabCyan else LabMuted,
                        style = MaterialTheme.typography.bodySmall)
                }

                if (showQuickResize && original != null) {
                    OutlinedButton(
                        onClick = {
                            val source = original ?: return@OutlinedButton
                            scope.launch {
                                isProcessing = true
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
                                    isProcessing = false
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
                    Text(if (optimizedSource) "DEVICE-SAFE SIZE" else "READY",
                        color = LabGold, style = MaterialTheme.typography.labelSmall)
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
            color = Color(0xFF1B2A2F), shape = RoundedCornerShape(18.dp),
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
        modifier = modifier, color = Color(0xFF172334), shape = RoundedCornerShape(20.dp),
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
    Box(modifier.clip(RoundedCornerShape(17.dp)).background(Color(0xFF0A101A)),
        contentAlignment = Alignment.Center) {
        Image(bitmap.asImageBitmap(), "Image preview",
            Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
    }
}

private fun decodeImage(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    } ?: error("Couldn't open the selected image.")
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format isn't supported." }

    var sample = 1
    while (
        (bounds.outWidth.toLong() / sample.toLong()) *
            (bounds.outHeight.toLong() / sample.toLong()) > MAX_PREVIEW_PIXELS ||
        maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_PREVIEW_DIMENSION
    ) sample *= 2

    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } ?: error("Couldn't decode this image.")
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
