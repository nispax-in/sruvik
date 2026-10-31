package `in`.nispax.sruvik.ui

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.nispax.sruvik.srugen.*
import `in`.nispax.sruvik.ui.theme.SruvikColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private data class SruGenResourceStats(
    val cpu: Float = 0f,
    val pss: Int = 0,
    val heap: Int = 0,
    val available: Int = 0
)

@Composable
fun SruGenPage(memoryEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var chats by remember { mutableStateOf<List<SruGenChat>>(emptyList()) }
    var activeId by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SruGenMode.TEXT_TO_IMAGE) }
    var selectedImage by remember { mutableStateOf<String?>(null) }
    var thumbnails by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var generating by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var progressLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var modelReady by remember { mutableStateOf(false) }
    var downloadId by remember { mutableLongStateOf(-1L) }
    var downloadPercent by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf(false) }
    var installMessage by remember { mutableStateOf("") }
    var showInstallDialog by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SruGenChat?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SruGenChat?>(null) }
    var monitorExpanded by remember { mutableStateOf(false) }
    var resourceStats by remember { mutableStateOf(SruGenResourceStats()) }

    val activeChat = chats.firstOrNull { it.id == activeId }

    fun persist(chat: SruGenChat) {
        val updated = (listOf(chat) + chats.filterNot { it.id == chat.id })
            .sortedByDescending { it.updatedAt }
        chats = updated
        scope.launch(Dispatchers.IO) {
            runCatching { SruGenChatStore.saveChats(context, updated) }
                .onFailure { error = "Could not save encrypted chat history: ${it.message}" }
        }
    }

    fun newChat() {
        val newItem = SruGenChat(title = "New chat")
        val updated = listOf(newItem) + chats
        chats = updated
        activeId = newItem.id
        prompt = ""
        selectedImage = null
        error = ""
        info = ""
        scope.launch(Dispatchers.IO) { runCatching { SruGenChatStore.saveChats(context, updated) } }
    }

    fun activateInstalledModel() {
        (SruGenEngineRegistry.engine as? AutoCloseable)?.close()
        SruGenEngineRegistry.engine = MediaPipeSruGenEngine(
            context.applicationContext,
            File(SruGenModelInstaller.modelDirectory(context), "sd15_bins").absolutePath
        )
        modelReady = true
    }

    suspend fun installFrom(uri: Uri) {
        installing = true
        installMessage = "Verifying model archive"
        try {
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Unable to open the model archive.")
                SruGenModelInstaller.importArchive(context, input) { installMessage = it }
            }
            activateInstalledModel()
            info = "Stable Diffusion 1.5 installed. SruGen is ready for offline image generation."
            error = ""
        } finally {
            installing = false
            installMessage = ""
        }
    }

    suspend fun pollDownload(id: Long) {
        if (id < 0L || installing) return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0L)
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            downloadPercent = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
            when (status) {
                DownloadManager.STATUS_FAILED -> {
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    error = "Model download failed (reason $reason). Check your connection and free storage, then retry."
                    SruGenModelInstaller.clearDownloadId(context)
                    downloadId = -1L
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    installing = true
                    installMessage = "Verifying model archive"
                    try {
                        val uri = manager.getUriForDownloadedFile(id) ?: error("Downloaded model file could not be opened.")
                        withContext(Dispatchers.IO) {
                            val input = context.contentResolver.openInputStream(uri) ?: error("Unable to read the model archive.")
                            SruGenModelInstaller.importArchive(context, input) { installMessage = it }
                        }
                        activateInstalledModel()
                        info = "Model installed. Generation runs locally and is available offline."
                        error = ""
                    } catch (failure: Exception) {
                        error = failure.message ?: "Model installation failed."
                    } finally {
                        installing = false
                        installMessage = ""
                        SruGenModelInstaller.clearDownloadId(context)
                        downloadId = -1L
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        chats = runCatching { withContext(Dispatchers.IO) { SruGenChatStore.loadChats(context) } }
            .getOrElse {
                error = "Local SruGen history could not be decrypted: ${it.message}. The app has not discarded the encrypted file."
                emptyList()
            }
        if (chats.isEmpty()) {
            val fresh = SruGenChat(title = "New chat")
            chats = listOf(fresh)
            activeId = fresh.id
            withContext(Dispatchers.IO) { SruGenChatStore.saveChats(context, listOf(fresh)) }
        } else {
            activeId = chats.first().id
        }
        modelReady = SruGenModelInstaller.isInstalled(context)
        if (modelReady) activateInstalledModel()
        downloadId = SruGenModelInstaller.savedDownloadId(context)
        downloadId = SruGenModelInstaller.savedDownloadId(context)
    }

    LaunchedEffect(downloadId) {
        while (downloadId >= 0L) {
            pollDownload(downloadId)
            delay(1000)
        }
    }

    LaunchedEffect(activeId, chats) {
        val chat = chats.firstOrNull { it.id == activeId } ?: return@LaunchedEffect
        val ids = chat.messages.mapNotNull { it.attachmentId }.distinct().takeLast(8)
        val loaded = mutableMapOf<String, Bitmap>()
        withContext(Dispatchers.IO) {
            ids.forEach { id ->
                runCatching {
                    val bytes = SruGenChatStore.readAttachment(context, id)
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                            inSampleSize = sample
                        })
                    } finally {
                        bytes.fill(0)
                    }
                }.getOrNull()?.let { loaded[id] = it }
            }
        }
        thumbnails.values.forEach { if (!it.isRecycled) it.recycle() }
        thumbnails = loaded
    }

    LaunchedEffect(Unit) {
        var priorCpu = Process.getElapsedCpuTime()
        var priorWall = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val nowCpu = Process.getElapsedCpuTime()
            val nowWall = SystemClock.elapsedRealtime()
            val wallDelta = (nowWall - priorWall).coerceAtLeast(1)
            val cpuDelta = (nowCpu - priorCpu).coerceAtLeast(0)
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val cpu = (cpuDelta.toFloat() / (wallDelta * cores) * 100f).coerceIn(0f, 100f)
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            resourceStats = SruGenResourceStats(
                cpu = cpu,
                pss = Debug.getPss() / 1024,
                heap = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)).toInt(),
                available = (memory.availMem / (1024 * 1024)).toInt()
            )
            priorCpu = nowCpu
            priorWall = nowWall
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            thumbnails.values.forEach { if (!it.isRecycled) it.recycle() }
            (SruGenEngineRegistry.engine as? AutoCloseable)?.close()
            SruGenEngineRegistry.engine = null
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var countTotal = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            countTotal += count
                            require(countTotal <= 24 * 1024 * 1024) { "Choose an image smaller than 24 MB." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("SruGen couldn't read that image.")
                }
                try {
                    val bitmap = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("This image format cannot be previewed.")
                    }
                    val id = withContext(Dispatchers.IO) {
                        SruGenChatStore.saveAttachment(
                            context,
                            bytes,
                            context.contentResolver.getType(uri) ?: "image/jpeg"
                        )
                    }
                    selectedImage?.let { old ->
                        SruGenChatStore.deleteAttachment(context, old)
                        thumbnails[old]?.takeIf { !it.isRecycled }?.recycle()
                        thumbnails = thumbnails - old
                    }
                    thumbnails = thumbnails + (id to bitmap)
                    selectedImage = id
                    info = "Image attached and encrypted in local chat storage."
                    error = ""
                } finally {
                    bytes.fill(0)
                }
            } catch (failure: Exception) {
                error = failure.message ?: "Unable to attach image."
            }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                installFrom(uri)
            } catch (failure: Exception) {
                error = failure.message ?: "Model import failed."
            }
        }
    }

    fun sendMessage() {
        val chat = chats.firstOrNull { it.id == activeId } ?: return
        val text = prompt.trim()
        val attachment = selectedImage
        if (text.isBlank() && attachment == null || generating) return
        val userMessage = SruGenMessage(role = "user", text = text, attachmentId = attachment,
            attachmentMime = if (attachment == null) null else "image/*")
        val title = if (chat.messages.isEmpty()) text.take(42).ifBlank { "Image conversation" } else chat.title
        val updatedChat = chat.copy(title = title, updatedAt = System.currentTimeMillis(),
            messages = chat.messages + userMessage)
        persist(updatedChat)
        prompt = ""
        selectedImage = null
        error = ""
        info = ""

        if (mode != SruGenMode.TEXT_TO_IMAGE) {
            val unavailable = when (mode) {
                SruGenMode.PROMPT_EDIT -> "The installed Stable Diffusion 1.5 base model currently supports text-to-image. Reference-image editing requires a separate conditioning model."
                SruGenMode.REMOVE_BACKGROUND -> "Background removal requires a separate on-device segmentation model. It is not installed yet."
                SruGenMode.REMOVE_OBJECT -> "Object removal requires an inpainting model and selection mask. It is not enabled yet."
                else -> "This workflow is unavailable."
            }
            persist(updatedChat.copy(messages = updatedChat.messages + SruGenMessage(role = "assistant", text = unavailable)))
            return
        }
        if (!modelReady || SruGenEngineRegistry.engine == null) {
            persist(updatedChat.copy(messages = updatedChat.messages + SruGenMessage(
                role = "assistant",
                text = "Your prompt and image are safely stored in this encrypted chat. Install the local Stable Diffusion model to generate images on this device."
            )))
            return
        }

        val remembered = if (memoryEnabled) SruGenChatStore.memoryContext(chats, chat.id) else ""
        val generationPrompt = if (remembered.isBlank()) text else
            "Use the recent creative context only for style continuity. Follow the current prompt first.\nRecent creative context:\n$remembered\n\nCurrent prompt: $text"

        generating = true
        progress = 0
        progressLabel = "Preparing local model"
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val engine = SruGenEngineRegistry.engine ?: error("Local SruGen engine is unavailable.")
                    engine.generate(
                        SruGenRequest(
                            mode = SruGenMode.TEXT_TO_IMAGE,
                            prompt = generationPrompt,
                            style = "Cinematic",
                            aspectRatio = "1:1",
                            sourceImage = null
                        )
                    ) { value, label ->
                        progress = value.coerceIn(0, 100)
                        progressLabel = label
                    }
                }
                val imageId = withContext(Dispatchers.IO) {
                    SruGenChatStore.saveAttachment(context, result.imageBytes, result.mimeType)
                }
                val previewBytes = result.imageBytes.copyOf()
                result.imageBytes.fill(0)
                val bitmap = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
                }
                previewBytes.fill(0)
                if (bitmap != null) thumbnails = thumbnails + (imageId to bitmap)
                val newest = chats.firstOrNull { it.id == activeId } ?: updatedChat
                persist(newest.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = newest.messages + SruGenMessage(
                        role = "assistant",
                        text = "Generated locally on this device. No prompt or image was uploaded.",
                        attachmentId = imageId,
                        attachmentMime = result.mimeType,
                        modelName = result.modelName
                    )
                ))
            } catch (failure: Exception) {
                val newest = chats.firstOrNull { it.id == activeId } ?: updatedChat
                persist(newest.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = newest.messages + SruGenMessage(
                        role = "assistant",
                        text = failure.message ?: "Local image generation failed."
                    )
                ))
            } finally {
                generating = false
                progress = 0
                progressLabel = ""
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 15.dp, bottom = 105.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("SRUVIK CREATIVE LAB", color = SruvikColors.LimeMuted,
                        style = MaterialTheme.typography.labelSmall, letterSpacing = 1.8.sp)
                    Text("SruGen", color = SruvikColors.TextPrimary,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { historyOpen = true }) {
                    Icon(Icons.Rounded.History, "Chat history", tint = SruvikColors.TextSecondary)
                }
                IconButton(onClick = { newChat() }) {
                    Icon(Icons.Rounded.Edit, "New chat", tint = SruvikColors.Lime)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(30.dp),
                    color = if (modelReady) Color(0xFF62C69B).copy(alpha = .13f) else Color(0xFFFFBE79).copy(alpha = .13f)) {
                    Text(
                        if (modelReady) "SD 1.5 · ON DEVICE" else "LOCAL ENGINE NOT INSTALLED",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = if (modelReady) SruvikColors.Lime else Color(0xFFFFC98F),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("ENCRYPTED CHAT HISTORY", color = SruvikColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall)
            }

            if (!modelReady) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                    .background(Color(0xAA272343)).border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(15.dp))
                    .padding(horizontal = 11.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DownloadForOffline, null, tint = SruvikColors.LimeMuted)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Install your local image model", color = SruvikColors.TextPrimary,
                            style = MaterialTheme.typography.labelLarge)
                        Text("1.91 GB · 5 GB free storage · 8 GB RAM recommended",
                            color = SruvikColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { showInstallDialog = true }, enabled = !installing && downloadId < 0L) {
                        Text("SET UP", color = SruvikColors.Lime)
                    }
                }
            }

            if (downloadId >= 0L || installing) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                    .background(Color(0xAA191D30)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (installing) installMessage else "Downloading model · $downloadPercent%",
                        color = SruvikColors.TextPrimary, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (installing) 0.5f else downloadPercent / 100f },
                        modifier = Modifier.fillMaxWidth())
                    if (downloadId >= 0L) {
                        TextButton(onClick = {
                            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
                            SruGenModelInstaller.clearDownloadId(context)
                            downloadId = -1L
                            info = "Model download cancelled."
                        }) { Text("Cancel", color = SruvikColors.TextSecondary) }
                    }
                }
            }

            currentChat?.let { chat ->
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (chat.messages.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(60.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Color(0xFF8069D3), Color(0xFF36B9D6)))),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                                Text("What shall we create?", color = SruvikColors.TextPrimary,
                                    style = MaterialTheme.typography.titleLarge)
                                Text("Describe an image or attach a reference. Your chats stay encrypted on this phone.",
                                    color = SruvikColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    AssistChip(onClick = {
                                        prompt = "A moonlit glass greenhouse floating above a quiet ocean, cinematic atmosphere"
                                    }, label = { Text("Dreamscape") })
                                    AssistChip(onClick = {
                                        prompt = "A retro-futurist travel poster for a city on Mars"
                                    }, label = { Text("Poster") })
                                }
                            }
                        }
                    }
                    items(chat.messages, key = { it.id }) { message ->
                        SruGenMessageBubble(message, message.attachmentId?.let { thumbnails[it] })
                    }
                    if (generating) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = SruvikColors.Lime)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("SruGen is running locally", color = SruvikColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall)
                                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                                    Text(progressLabel, color = SruvikColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            if (error.isNotBlank()) Text(error, color = Color(0xFFFFA2A2), style = MaterialTheme.typography.bodySmall)
            if (info.isNotBlank()) Text(info, color = SruvikColors.TextSecondary, style = MaterialTheme.typography.bodySmall)

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF282545), Color(0xFF1A2239))))
                .border(1.dp, Color.White.copy(alpha = .1f), RoundedCornerShape(23.dp))
                .padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeChip("Create", mode == SruGenMode.TEXT_TO_IMAGE) { mode = SruGenMode.TEXT_TO_IMAGE }
                    ModeChip("Edit", mode == SruGenMode.PROMPT_EDIT) { mode = SruGenMode.PROMPT_EDIT }
                    ModeChip("Remove BG", mode == SruGenMode.REMOVE_BACKGROUND) { mode = SruGenMode.REMOVE_BACKGROUND }
                    ModeChip("Object", mode == SruGenMode.REMOVE_OBJECT) { mode = SruGenMode.REMOVE_OBJECT }
                }
                selectedImage?.let { id ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = .06f)).padding(7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        thumbnails[id]?.let { bitmap ->
                            Image(bitmap.asImageBitmap(), null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Encrypted image attached", color = SruvikColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            selectedImage = null
                            SruGenChatStore.deleteAttachment(context, id)
                            thumbnails[id]?.takeIf { !it.isRecycled }?.recycle()
                            thumbnails = thumbnails - id
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, "Remove image", tint = SruvikColors.TextSecondary)
                        }
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(1800) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp, max = 138.dp),
                    placeholder = { Text("Message SruGen…", color = SruvikColors.TextMuted) },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SruvikColors.TextPrimary,
                        unfocusedTextColor = SruvikColors.TextPrimary,
                        focusedBorderColor = SruvikColors.LimeMuted,
                        unfocusedBorderColor = Color.White.copy(alpha = .1f),
                        cursorColor = SruvikColors.Lime
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Rounded.AddPhotoAlternate, "Upload image", tint = SruvikColors.TextSecondary)
                    }
                    Text("Attach image", color = SruvikColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(if (memoryEnabled) "MEMORY ON" else "MEMORY OFF",
                        color = SruvikColors.LimeMuted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(7.dp))
                    FilledIconButton(
                        onClick = { sendMessage() },
                        enabled = !generating && (prompt.isNotBlank() || selectedImage != null)
                    ) {
                        Icon(if (generating) Icons.Rounded.HourglassTop else Icons.Rounded.ArrowUpward, "Send")
                    }
                }
            }
        }

        ResourceMonitor(resourceStats, monitorExpanded, { monitorExpanded = !monitorExpanded },
            Modifier.align(Alignment.CenterEnd).padding(end = 3.dp))
    }

    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text("Install SruGen's local image engine", color = SruvikColors.TextPrimary) },
            text = {
                Text(
                    "Stable Diffusion 1.5 · MediaPipe\n\nThe model archive is about 1.91 GB. Allow at least 5 GB free internal storage. Google recommends a device with at least 8 GB RAM. After installation, image generation runs locally with no service rate limits.\n\nThe model is under CreativeML OpenRAIL-M; please review its use restrictions. Nothing downloads until you confirm.",
                    color = SruvikColors.TextSecondary, style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showInstallDialog = false
                    scope.launch {
                        try {
                            downloadId = SruGenModelInstaller.startDownload(context)
                            error = ""
                            info = "Model download started. You can cancel it at any time."
                        } catch (failure: Exception) {
                            error = failure.message ?: "Unable to start model download."
                        }
                    }
                }) { Text("Download model", color = SruvikColors.Lime) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showInstallDialog = false
                    modelPicker.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                }) { Text("Import ZIP", color = SruvikColors.TextSecondary) }
            },
            containerColor = Color(0xFF19192D),
            shape = RoundedCornerShape(23.dp)
        )
    }

    if (historyOpen) {
        AlertDialog(
            onDismissRequest = { historyOpen = false },
            title = { Text("Your conversations", color = SruvikColors.TextPrimary) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(chats.sortedByDescending { it.updatedAt }, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                                .background(if (item.id == activeId) Color(0xFF37305D) else Color.Transparent)
                                .clickable { activeId = item.id; selectedImage = null; historyOpen = false }
                                .padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.title, color = SruvikColors.TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(item.messages.lastOrNull()?.text?.take(38) ?: "No messages yet",
                                    color = SruvikColors.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            IconButton(onClick = { renameTarget = item; renameText = item.title; historyOpen = false }) {
                                Icon(Icons.Rounded.Edit, "Rename", tint = SruvikColors.TextSecondary)
                            }
                            IconButton(onClick = { deleteTarget = item; historyOpen = false }) {
                                Icon(Icons.Rounded.DeleteOutline, "Delete", tint = Color(0xFFFF9999))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("Close", color = SruvikColors.Lime) } },
            dismissButton = { TextButton(onClick = { historyOpen = false; newChat() }) { Text("New chat", color = SruvikColors.TextSecondary) } },
            containerColor = Color(0xFF19192D)
        )
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation", color = SruvikColors.TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(64) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Conversation name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = renameTarget!!
                    persist(target.copy(title = renameText.trim().ifBlank { "New chat" }, updatedAt = System.currentTimeMillis()))
                    renameTarget = null
                }) { Text("Save", color = SruvikColors.Lime) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = SruvikColors.TextSecondary) } },
            containerColor = Color(0xFF19192D)
        )
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this conversation?", color = SruvikColors.TextPrimary) },
            text = { Text("The encrypted messages and their encrypted images will be deleted from this device.", color = SruvikColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    val target = deleteTarget!!
                    deleteTarget = null
                    scope.launch(Dispatchers.IO) {
                        SruGenChatStore.deleteChat(context, target)
                        withContext(Dispatchers.Main) {
                            chats = chats.filterNot { it.id == target.id }
                            val ids = target.messages.mapNotNull { it.attachmentId }.toSet()
                            ids.forEach { id -> thumbnails[id]?.takeIf { !it.isRecycled }?.recycle() }
                            thumbnails = thumbnails - ids
                            if (activeId == target.id) newChat()
                        }
                    }
                }) { Text("Delete", color = Color(0xFFFF9999)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = SruvikColors.TextSecondary) } },
            containerColor = Color(0xFF19192D)
        )
    }
}

@Composable
private fun SruGenMessageBubble(message: SruGenMessage, image: Bitmap?) {
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 315.dp).clip(
                RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp,
                    bottomStart = if (isUser) 20.dp else 6.dp,
                    bottomEnd = if (isUser) 6.dp else 20.dp
                )
            )
                .background(if (isUser) Color(0xFF37305D) else Color(0xAA20243A))
                .border(1.dp, Color.White.copy(alpha = .07f), RoundedCornerShape(20.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (message.text.isNotBlank()) Text(message.text, color = SruvikColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium)
            image?.let {
                Image(it.asImageBitmap(), "Encrypted chat image",
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            }
            message.modelName?.let { Text(it, color = SruvikColors.LimeMuted, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1) },
        shape = RoundedCornerShape(28.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF6254A4),
            selectedLabelColor = Color.White,
            labelColor = SruvikColors.TextSecondary
        ))
}

@Composable
private fun ResourceMonitor(
    stats: SruGenResourceStats,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (expanded) {
            Column(
                Modifier.width(188.dp).clip(RoundedCornerShape(18.dp))
                    .background(Color(0xEE19192D))
                    .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(18.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE RESOURCES", color = SruvikColors.LimeMuted,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onToggle, modifier = Modifier.size(25.dp)) {
                        Icon(Icons.Rounded.Close, "Minimise", tint = SruvikColors.TextSecondary)
                    }
                }
                ResourceLine("App CPU", String.format("%.1f%%", stats.cpu))
                ResourceLine("App RAM", "${stats.pss} MB")
                ResourceLine("Java heap", "${stats.heap} MB")
                ResourceLine("Free system RAM", "${stats.available} MB")
                Text("Approximate whole-app process sampling.",
                    color = SruvikColors.TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Surface(onClick = onToggle,
                shape = RoundedCornerShape(topStart = 15.dp, bottomStart = 15.dp),
                color = Color(0xEE19192D),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .12f))) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.Memory, null, tint = SruvikColors.LimeMuted, modifier = Modifier.size(15.dp))
                    Text("${stats.pss} MB", color = SruvikColors.TextPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ResourceLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SruvikColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        Text(value, color = SruvikColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
    }
}
