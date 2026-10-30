package `in`.nispax.sruvik.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val UpscaleInk = Color(0xFF111827)
private val UpscaleViolet = Color(0xFFB9A3FF)
private val UpscaleCyan = Color(0xFF78DCE5)
private val UpscaleAmber = Color(0xFFFFC477)
private val UpscaleCard = Color(0xFF182330)

/**
 * First local upscaling pass. Uses Android's filtered bitmap scaler, with strict
 * output limits to avoid allocating huge bitmaps on lower-memory phones.
 * A neural super-resolution model can replace this processing step later.
 */
@Composable
fun ImageUpscalerPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableIntStateOf(2) }
    var isLoading by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Choose a photo to get started.") }
    var outputScale by remember { mutableStateOf<Float?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            result = null
            outputScale = null
            message = "Loading your image…"
        }
    }

    val saver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val bitmap = result
        if (uri != null && bitmap != null) {
            scope.launch {
                isSaving = true
                message = "Saving image…"
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
                    message = "Saved. Your upscaled image is ready."
                } catch (error: Exception) {
                    message = error.message ?: "Couldn't save this image."
                } finally {
                    isSaving = false
                }
            }
        }
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        isLoading = true
        message = "Loading your image…"
        try {
            val decoded = withContext(Dispatchers.IO) { decodeForUpscaling(context, uri) }
            original = decoded
            result = null
            message = "Image ready. Choose 2× or 4×."
        } catch (error: Exception) {
            original = null
            message = error.message ?: "Couldn't read this image."
        } finally {
            isLoading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            original?.recycle()
            result?.recycle()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "IMAGE LAB  /  01",
                color = UpscaleCyan,
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 2.2.sp
            )
            Text(
                "Give your images\nmore room.",
                color = Color(0xFFF4F2FA),
                style = MaterialTheme.typography.displayMedium,
                lineHeight = 39.sp
            )
            Text(
                "Upscale a photo locally on your phone. No account or upload.",
                color = Color(0xFFB4C0CD),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            color = UpscaleCard,
            shape = RoundedCornerShape(26.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .09f))
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
                            .background(UpscaleViolet.copy(alpha = .17f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Image, null, tint = UpscaleViolet)
                    }
                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                        Text("Your image", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            original?.let { "${it.width} × ${it.height}" } ?: "PNG, JPG or WebP",
                            color = Color(0xFF9EABBA),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    OutlinedButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.size(7.dp))
                        Text(if (original == null) "Choose" else "Change")
                    }
                }

                if (original != null) {
                    BitmapPreview(bitmap = original!!, modifier = Modifier.fillMaxWidth())
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(19.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF26354B), Color(0xFF302744)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.AddPhotoAlternate, null, tint = UpscaleCyan, modifier = Modifier.size(34.dp))
                            Text("Pick an image to preview it here", color = Color(0xFFD4D9E7))
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF211F33),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, UpscaleViolet.copy(alpha = .20f))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoFixHigh, null, tint = UpscaleAmber)
                    Text(
                        "  OUTPUT SIZE",
                        color = Color(0xFFE7E1F5),
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.4.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(2, 4).forEach { option ->
                        FilterChip(
                            selected = scale == option,
                            onClick = { scale = option; result = null; outputScale = null },
                            label = {
                                Text(
                                    "${option}×",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            },
                            leadingIcon = if (scale == option) {
                                { Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = UpscaleViolet.copy(alpha = .23f),
                                selectedLabelColor = Color.White,
                                labelColor = Color(0xFFD7D2E5),
                                selectedLeadingIconColor = UpscaleCyan
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = scale == option,
                                borderColor = Color.White.copy(alpha = .16f),
                                selectedBorderColor = UpscaleViolet
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "ON-DEVICE",
                        color = UpscaleCyan,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                Button(
                    onClick = {
                        val source = original ?: return@Button
                        scope.launch {
                            isProcessing = true
                            result = null
                            message = "Upscaling on your device…"
                            try {
                                val processed = withContext(Dispatchers.Default) {
                                    createUpscaledBitmap(source, scale)
                                }
                                result = processed.bitmap
                                outputScale = processed.actualScale
                                message = if (processed.actualScale + .02f < scale) {
                                    "Adjusted to ${"%.1f".format(processed.actualScale)}× to protect device memory."
                                } else {
                                    "Done. Preview your upscaled image below."
                                }
                            } catch (error: OutOfMemoryError) {
                                message = "This image is too large for the available memory. Try a smaller source image."
                            } catch (error: Exception) {
                                message = error.message ?: "Upscaling failed. Please try another image."
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    enabled = original != null && !isLoading && !isProcessing && !isSaving,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UpscaleViolet,
                        contentColor = UpscaleInk,
                        disabledContainerColor = Color(0xFF45404F),
                        disabledContentColor = Color(0xFF9D97AA)
                    )
                ) {
                    if (isProcessing || isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            color = UpscaleInk,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(10.dp))
                    } else {
                        Icon(Icons.Rounded.AutoFixHigh, null)
                        Spacer(Modifier.size(9.dp))
                    }
                    Text(
                        when {
                            isLoading -> "Loading image…"
                            isProcessing -> "Upscaling…"
                            else -> "Upscale image"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(message, color = if (message.startsWith("Done") || message.startsWith("Saved")) UpscaleCyan else Color(0xFFB6B1C5),
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        AnimatedVisibility(
            visible = result != null,
            enter = fadeIn() + slideInVertically { it / 5 }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RESULT", color = UpscaleCyan, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        outputScale?.let { "${"%.1f".format(it)}× output" } ?: "",
                        color = UpscaleAmber,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                result?.let { bitmap ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFF142B2F),
                        border = androidx.compose.foundation.BorderStroke(1.dp, UpscaleCyan.copy(alpha = .30f))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BitmapPreview(bitmap, Modifier.fillMaxWidth())
                            Text(
                                "${bitmap.width} × ${bitmap.height} px",
                                color = UpscaleCyan,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Button(
                                onClick = { saver.launch("sruvik-upscaled.png") },
                                enabled = !isSaving && !isProcessing,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = UpscaleCyan,
                                    contentColor = UpscaleInk
                                )
                            ) {
                                Icon(Icons.Rounded.Download, null)
                                Spacer(Modifier.size(8.dp))
                                Text(if (isSaving) "Saving…" else "Save upscaled image", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = Color(0xFF20242B),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .06f))
        ) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Refresh, null, tint = UpscaleAmber, modifier = Modifier.size(18.dp))
                Text(
                    "  This first version uses high-quality local resizing. AI detail reconstruction will be added with Sruvik's on-device model.",
                    color = Color(0xFFAEB7C3),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(98.dp))
    }
}

@Composable
private fun BitmapPreview(bitmap: Bitmap, modifier: Modifier = Modifier) {
    Box(
        modifier.height(220.dp).clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0B1119)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Image preview",
            modifier = Modifier.fillMaxSize().padding(5.dp),
            contentScale = ContentScale.Fit
        )
    }
}

private data class UpscaleOutput(val bitmap: Bitmap, val actualScale: Float)

private fun decodeForUpscaling(context: Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = context.contentResolver.openInputStream(uri)
        ?: error("Couldn't open the selected image.")
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format isn't supported." }

    var sample = 1
    while ((bounds.outWidth.toLong() / (sample * 2)) *
        (bounds.outHeight.toLong() / (sample * 2)) > MAX_INPUT_PIXELS) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: error("Couldn't decode this image.")
}

private fun createUpscaledBitmap(source: Bitmap, requestedScale: Int): UpscaleOutput {
    val sourcePixels = source.width.toLong() * source.height.toLong()
    val pixelLimitScale = sqrt(MAX_OUTPUT_PIXELS.toDouble() / sourcePixels).toFloat()
    val dimensionLimitScale = MAX_OUTPUT_DIMENSION.toFloat() /
        maxOf(source.width, source.height).toFloat()
    val actualScale = min(requestedScale.toFloat(), min(pixelLimitScale, dimensionLimitScale))
        .coerceAtLeast(1.01f)
    val width = (source.width * actualScale).roundToInt().coerceAtLeast(source.width + 1)
    val height = (source.height * actualScale).roundToInt().coerceAtLeast(source.height + 1)
    return UpscaleOutput(
        bitmap = Bitmap.createScaledBitmap(source, width, height, true),
        actualScale = min(width.toFloat() / source.width, height.toFloat() / source.height)
    )
}

private const val MAX_INPUT_PIXELS = 4_000_000L
private const val MAX_OUTPUT_PIXELS = 12_000_000L
private const val MAX_OUTPUT_DIMENSION = 6144
