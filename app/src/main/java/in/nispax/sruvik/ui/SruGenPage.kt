package `in`.nispax.sruvik.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import `in`.nispax.sruvik.srugen.SruGenEngineRegistry
import `in`.nispax.sruvik.srugen.SruGenMode
import `in`.nispax.sruvik.srugen.SruGenRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Arrays
import java.util.UUID

private val sruGenStyles = listOf("Cinematic", "Dreamlike", "Illustration", "Photoreal", "3D render", "Minimal")

@Composable
fun SruGenPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(SruGenMode.TEXT_TO_IMAGE) }
    var prompt by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("Cinematic") }
    var ratio by remember { mutableStateOf("1:1") }
    var sourceBytes by remember { mutableStateOf<ByteArray?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var outputBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var progressText by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var resultMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 24 * 1024 * 1024) { "Choose an image smaller than 24 MB." }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("SruGen couldn't read that image.")
                    }
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("This image format could not be previewed.")
                    sourceBytes?.let { Arrays.fill(it, 0) }
                    sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
                    sourceBytes = bytes
                    sourceBitmap = decoded
                    resultMessage = "Source image ready. Your original remains on this device."
                } catch (error: Exception) {
                    resultMessage = error.message ?: "Unable to load this image."
                }
            }
        }
    }

    val engine = SruGenEngineRegistry.engine
    val needsSource = mode != SruGenMode.TEXT_TO_IMAGE
    val ready = prompt.isNotBlank() && (!needsSource || sourceBytes != null) && engine != null && !busy

    DisposableEffect(Unit) {
        onDispose {
            sourceBytes?.let { Arrays.fill(it, 0) }
            sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
            outputBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 106.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SRUVIK CREATIVE LAB", style = MaterialTheme.typography.labelLarge,
                    color = SruvikColors.LimeMuted, letterSpacing = 1.9.dp.value.sp)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(30.dp), color = Color(0xFF5BC9A0).copy(alpha = .12f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.CloudOff, null, tint = SruvikColors.Lime, modifier = Modifier.size(14.dp))
                        Text("OFFLINE-FIRST", color = SruvikColors.Lime, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text("SruGen", style = MaterialTheme.typography.displayLarge, color = SruvikColors.TextPrimary)
            Text("Imagine it. Refine it. Make it yours.",
                style = MaterialTheme.typography.bodyLarge, color = SruvikColors.TextSecondary)
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF39315F), Color(0xFF182642), Color(0xFF17182B))))
                .border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(25.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = SruvikColors.Lime, modifier = Modifier.size(21.dp))
                Text("WORKFLOW", color = SruvikColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge, letterSpacing = 1.1.dp.value.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ModeChip("Create", mode == SruGenMode.TEXT_TO_IMAGE) { mode = SruGenMode.TEXT_TO_IMAGE }
                ModeChip("Edit", mode == SruGenMode.PROMPT_EDIT) { mode = SruGenMode.PROMPT_EDIT }
                ModeChip("Remove BG", mode == SruGenMode.REMOVE_BACKGROUND) { mode = SruGenMode.REMOVE_BACKGROUND }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ModeChip("Remove object", mode == SruGenMode.REMOVE_OBJECT) { mode = SruGenMode.REMOVE_OBJECT }
            }

            if (needsSource) {
                OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (sourceBytes == null) "Choose source image" else "Change source image")
                }
                sourceBitmap?.let { bitmap ->
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = "Selected source image preview",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp).clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Text("Original selected · held in memory only",
                        style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextMuted)
                }
            }

            Text(
                when (mode) {
                    SruGenMode.TEXT_TO_IMAGE -> "Describe a scene, subject, atmosphere or visual idea."
                    SruGenMode.PROMPT_EDIT -> "Describe the change you want. Keep everything else as close to the source as possible."
                    SruGenMode.REMOVE_BACKGROUND -> "Create a transparent cutout. An on-device segmentation model is not bundled yet."
                    SruGenMode.REMOVE_OBJECT -> "Describe what should disappear and what should fill the area. This requires an inpainting model."
                },
                style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it.take(1800) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 125.dp),
                placeholder = {
                    Text(
                        when (mode) {
                            SruGenMode.TEXT_TO_IMAGE -> "A moonlit glass greenhouse floating above a quiet ocean..."
                            SruGenMode.PROMPT_EDIT -> "Change the background to a misty mountain valley..."
                            SruGenMode.REMOVE_BACKGROUND -> "Optional notes for the subject cutout..."
                            SruGenMode.REMOVE_OBJECT -> "Remove the red car and naturally rebuild the road..."
                        },
                        color = SruvikColors.TextMuted
                    )
                },
                shape = RoundedCornerShape(17.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SruvikColors.TextPrimary,
                    unfocusedTextColor = SruvikColors.TextPrimary,
                    focusedBorderColor = SruvikColors.LimeMuted,
                    unfocusedBorderColor = Color.White.copy(alpha = .13f),
                    cursorColor = SruvikColors.Lime
                )
            )

            if (mode == SruGenMode.TEXT_TO_IMAGE || mode == SruGenMode.PROMPT_EDIT) {
                Text("VISUAL DIRECTION", style = MaterialTheme.typography.labelSmall,
                    color = SruvikColors.TextMuted, letterSpacing = 1.2.dp.value.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    sruGenStyles.take(3).forEach { item -> ModeChip(item, style == item) { style = item } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    sruGenStyles.drop(3).forEach { item -> ModeChip(item, style == item) { style = item } }
                }
                Text("ASPECT RATIO", style = MaterialTheme.typography.labelSmall,
                    color = SruvikColors.TextMuted, letterSpacing = 1.2.dp.value.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("1:1", "3:2", "2:3", "16:9").forEach { item ->
                        ModeChip(item, ratio == item) { ratio = item }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Color(0xAA171A2D))
                .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Rounded.Memory, null, tint = SruvikColors.TextSecondary)
                Text("INFERENCE ENGINE", style = MaterialTheme.typography.labelLarge,
                    color = SruvikColors.TextPrimary, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(30.dp),
                    color = Color(0xFFDBA96B).copy(alpha = .15f)) {
                    Text(if (engine == null) "NOT CONNECTED" else "READY",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        color = if (engine == null) Color(0xFFFFD19A) else SruvikColors.Lime,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                if (engine == null)
                    "SruGen's workspace is ready, but no image model is installed or configured. No fake generation is shown."
                else
                    "Connected: " + engine.displayName + " · " + if (engine.isOffline) "offline inference" else "private inference service",
                style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary
            )
            Text(
                "Model profile under evaluation: FLUX.2 [klein] 4B. It supports text-to-image and reference-image editing under Apache 2.0, but published hardware guidance targets desktop GPUs rather than ordinary Android phones.",
                style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextMuted
            )
            if (busy) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text(progressText, style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
            } else {
                Button(
                    onClick = {
                        val selectedEngine = SruGenEngineRegistry.engine ?: return@Button
                        val request = SruGenRequest(mode, prompt.trim(), style, ratio, sourceBytes?.copyOf())
                        busy = true
                        resultMessage = ""
                        scope.launch {
                            try {
                                val result = selectedEngine.generate(request) { value, status ->
                                    scope.launch {
                                        progress = value.coerceIn(0, 100)
                                        progressText = status
                                    }
                                }
                                val output = withContext(Dispatchers.IO) {
                                    val folder = File(context.filesDir, "sruvik-library")
                                    if (!folder.exists() && !folder.mkdirs()) error("Unable to create the private Sruvik Library.")
                                    val extension = when (result.mimeType.lowercase()) {
                                        "image/jpeg" -> "jpg"
                                        "image/webp" -> "webp"
                                        else -> "png"
                                    }
                                    val file = File(folder, "srugen-" + UUID.randomUUID() + "." + extension)
                                    file.writeBytes(result.imageBytes)
                                    BitmapFactory.decodeByteArray(result.imageBytes, 0, result.imageBytes.size)
                                }
                                outputBitmap?.takeIf { !it.isRecycled }?.recycle()
                                outputBitmap = output ?: error("The inference engine returned an unreadable image.")
                                resultMessage = "Created with " + result.modelName + ". Saved to your private Sruvik Library."
                                Arrays.fill(result.imageBytes, 0)
                            } catch (error: Exception) {
                                resultMessage = error.message ?: "Generation failed."
                            } finally {
                                busy = false
                                progress = 0
                            }
                        }
                    },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7764C9))
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (engine == null) "Generation engine not connected" else "Generate with SruGen")
                }
            }
            if (resultMessage.isNotBlank()) {
                Text(resultMessage, style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
            }
        }

        outputBitmap?.let { bitmap ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RESULT", style = MaterialTheme.typography.labelLarge,
                    color = SruvikColors.LimeMuted, letterSpacing = 1.4.sp)
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = "SruGen result",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(21.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Text(
            "Your prompt and selected source image stay on this device in this build. A future engine will clearly state whether processing is local or remote before running.",
            style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextMuted
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        shape = RoundedCornerShape(30.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF6254A4),
            selectedLabelColor = Color.White,
            labelColor = SruvikColors.TextSecondary
        )
    )
}
