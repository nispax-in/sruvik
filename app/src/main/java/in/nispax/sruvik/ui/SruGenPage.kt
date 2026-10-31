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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.SolidColor
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
    var draftChat by remember { mutableStateOf(SruGenChat(title = "New chat")) }
    var activeId by remember { mutableStateOf(draftChat.id) }
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

    val activeChat = chats.firstOrNull { it.id == activeId } ?: draftChat.takeIf { it.id == activeId }

    fun persist(chat: SruGenChat) {
        // Empty drafts are UI state, never history. Attachments count once the user sends them.
        if (chat.messages.none { it.role == "user" && (it.text.isNotBlank() || it.attachmentId != null) }) return
        val updated = (listOf(chat) + chats.filterNot { it.id == chat.id })
            .sortedByDescending { it.updatedAt }
        chats = updated
        scope.launch(Dispatchers.IO) {
            runCatching { SruGenChatStore.saveChats(context, updated) }
                .onFailure { error = "Could not save encrypted chat history: ${it.message}" }
        }
    }

    fun newChat() {
        // Start a volatile draft. It only enters encrypted history after the first user message.
        val draft = SruGenChat(title = "New chat")
        draftChat = draft
        activeId = draft.id
        prompt = ""
        selectedImage = null
        error = ""
        info = ""
    }

    fun activateInstalledModel() {
        if (SruGenEngineRegistry.engine is MediaPipeSruGenEngine) {
            // Reuse the process-local engine. A screen-exit close may be waiting
            // for an in-flight native inference step; the engine lock serializes it.
            modelReady = true
            return
        }
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
        chats = chats.filter { chat ->
            chat.messages.any { it.role == "user" && (it.text.isNotBlank() || it.attachmentId != null) }
        }
        activeId = chats.firstOrNull()?.id ?: draftChat.id
        modelReady = SruGenModelInstaller.isInstalled(context)
        if (modelReady) activateInstalledModel()
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
            val cpu = (cpuDelta.toFloat() / (wallDelta.toFloat() * cores.toFloat()) * 100f).coerceIn(0f, 100f)
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            resourceStats = SruGenResourceStats(
                cpu = cpu,
                pss = (Debug.getPss() / 1024L).toInt(),
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
            val engineToClose = SruGenEngineRegistry.engine as? AutoCloseable
            if (engineToClose != null) {
                // Don't block Compose or close the native task during an execute() call.
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
                ).launch {
                    runCatching { engineToClose.close() }
                }
            }
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
        val chat = chats.firstOrNull { it.id == activeId }
            ?: draftChat.takeIf { it.id == activeId }
            ?: return
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

        if (text.isBlank() && attachment != null) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(
                    role = "assistant",
                    text = "Image attached and encrypted in this conversation. Add an instruction and choose Edit, Remove BG or Object to continue. The corresponding editing model is not installed in this version yet."
                )
            ))
            return
        }

        if (attachment != null && mode == SruGenMode.TEXT_TO_IMAGE) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(
                    role = "assistant",
                    text = "Your reference image is saved in this encrypted chat, but the installed Stable Diffusion text-to-image model cannot condition on it. I did not use the image for this generation. Choose Edit to see the current image-editing availability."
                )
            ))
            return
        }

        if (mode != SruGenMode.TEXT_TO_IMAGE && attachment == null) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(
                    role = "assistant",
                    text = "Attach an image first, then describe the change you want."
                )
            ))
            return
        }

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
                val newest = chats.firstOrNull { it.id == updatedChat.id } ?: updatedChat
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

    Box(Modifier.fillMaxSize().background(Color(0xFF121710))) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 13.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        "SRUVIK  /  IMAGE WORKSHOP",
                        color = Color(0xFFB8C49A),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 2.2.sp
                    )
                    Text(
                        "SruGen",
                        color = Color(0xFFE8E5D8),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 42.sp,
                            lineHeight = 46.sp,
                            letterSpacing = (-1.4).sp
                        )
                    )
                }
                TextButton(onClick = { historyOpen = true }) {
                    Icon(Icons.Rounded.History, null, tint = Color(0xFFC3C7B4), modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("HISTORY", color = Color(0xFFC3C7B4), letterSpacing = 1.sp, fontSize = 10.sp)
                }
                TextButton(onClick = { newChat() }) {
                    Text("+ NEW", color = Color(0xFFD5E58B), letterSpacing = 1.sp, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(17.dp).height(2.dp).background(Color(0xFFC77B5D)))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (modelReady) "LOCAL ENGINE  /  READY" else "LOCAL ENGINE  /  NOT INSTALLED",
                    color = if (modelReady) Color(0xFFD5E58B) else Color(0xFFE0A16F),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "PROCESS  ${resourceStats.pss} MB",
                    Modifier.clickable { monitorExpanded = !monitorExpanded }.padding(vertical = 4.dp),
                    color = Color(0xFF8C967D),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = .5.sp
                )
            }

            if (monitorExpanded) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("CPU ${String.format("%.1f", resourceStats.cpu)}%", color = Color(0xFFB7BDA8), style = MaterialTheme.typography.labelSmall)
                    Text("HEAP ${resourceStats.heap} MB", color = Color(0xFFB7BDA8), style = MaterialTheme.typography.labelSmall)
                    Text("FREE ${resourceStats.available} MB", color = Color(0xFFB7BDA8), style = MaterialTheme.typography.labelSmall)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30392C)))
            if (!modelReady) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("No local model loaded", color = Color(0xFFE7E1D1), style = MaterialTheme.typography.titleSmall)
                        Text("SD 1.5 · about 1.91 GB · runs on this device", color = Color(0xFF9AA18B), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { showInstallDialog = true }, enabled = !installing && downloadId < 0L) {
                        Text("INSTALL  ↗", color = Color(0xFFD5E58B), fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30392C)))
            }

            if (downloadId >= 0L || installing) {
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (installing) installMessage else "DOWNLOADING MODEL  /  $downloadPercent%",
                        color = Color(0xFFD5E58B),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp
                    )
                    LinearProgressIndicator(
                        progress = { if (installing) 0.5f else downloadPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFD5E58B),
                        trackColor = Color(0xFF30392C)
                    )
                    if (downloadId >= 0L) {
                        TextButton(onClick = {
                            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
                            SruGenModelInstaller.clearDownloadId(context)
                            downloadId = -1L
                            info = "Model download cancelled."
                        }) { Text("CANCEL DOWNLOAD", color = Color(0xFF9AA18B), fontSize = 10.sp) }
                    }
                }
            }

            activeChat?.let { chat ->
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 15.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    if (chat.messages.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(13.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(Modifier.width(24.dp).height(2.dp).background(Color(0xFFC77B5D)))
                                        Text("FIELD NOTE  /  001", color = Color(0xFF9BA58B), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text("NO OUTPUT YET", color = Color(0xFF77816D), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                                }
                                Text(
                                    "Make a world\nthat isn't here.",
                                    color = Color(0xFFE8E5D8),
                                    style = MaterialTheme.typography.displayLarge.copy(
                                        fontSize = 38.sp,
                                        lineHeight = 41.sp,
                                        letterSpacing = (-1.2).sp
                                    )
                                )
                                Text(
                                    "Start with a scene, a feeling, or a strange detail. SruGen builds the image on this device.",
                                    color = Color(0xFFAEB49F),
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 21.sp
                                )
                                Spacer(Modifier.height(5.dp))
                                Text("STARTING POINTS", color = Color(0xFF839078), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                                StudioSuggestion("01", "A quiet city after the monsoon", onClick = {
                                    prompt = "A quiet city after the monsoon, wet laterite streets, warm windows, cinematic natural light"
                                })
                                StudioSuggestion("02", "A machine grown like a plant", onClick = {
                                    prompt = "An intricate machine grown like a plant, organic metal, botanical forms, tactile editorial photography"
                                })
                                StudioSuggestion("03", "A memory from a place that never existed", onClick = {
                                    prompt = "A nostalgic photograph of a place that never existed, imperfect film grain, soft afternoon light"
                                })
                            }
                        }
                    }
                    items(chat.messages, key = { it.id }) { message ->
                        SruGenMessageBubble(message, message.attachmentId?.let { thumbnails[it] })
                    }
                    if (generating) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFFD5E58B))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("RENDERING LOCALLY  /  $progress%", color = Color(0xFFD5E58B), style = MaterialTheme.typography.labelSmall, letterSpacing = .8.sp)
                                    LinearProgressIndicator(
                                        progress = { progress / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFFD5E58B),
                                        trackColor = Color(0xFF30392C)
                                    )
                                    Text(progressLabel, color = Color(0xFF9AA18B), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            if (error.isNotBlank()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(3.dp).height(24.dp).background(Color(0xFFD47C64)))
                    Spacer(Modifier.width(8.dp))
                    Text(error, color = Color(0xFFF0A28E), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (info.isNotBlank()) {
                Text(info, Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Color(0xFFADB99A), style = MaterialTheme.typography.bodySmall)
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF45503A)))
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF1B2219)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StudioModeControl("CREATE", mode == SruGenMode.TEXT_TO_IMAGE) { mode = SruGenMode.TEXT_TO_IMAGE }
                    StudioModeControl("EDIT", mode == SruGenMode.PROMPT_EDIT) { mode = SruGenMode.PROMPT_EDIT }
                    StudioModeControl("CUT OUT", mode == SruGenMode.REMOVE_BACKGROUND) { mode = SruGenMode.REMOVE_BACKGROUND }
                    StudioModeControl("ERASE", mode == SruGenMode.REMOVE_OBJECT) { mode = SruGenMode.REMOVE_OBJECT }
                }
                selectedImage?.let { id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        thumbnails[id]?.let { bitmap ->
                            Image(
                                bitmap.asImageBitmap(),
                                "Attached reference image",
                                Modifier.width(54.dp).height(54.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("REFERENCE / ENCRYPTED", color = Color(0xFFD5E58B), style = MaterialTheme.typography.labelSmall, letterSpacing = .7.sp)
                            Text("Stored locally with this draft until sent", color = Color(0xFF9AA18B), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            selectedImage = null
                            SruGenChatStore.deleteAttachment(context, id)
                            thumbnails[id]?.takeIf { !it.isRecycled }?.recycle()
                            thumbnails = thumbnails - id
                        }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.Close, "Remove image", tint = Color(0xFFB5BDA7))
                        }
                    }
                }
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(1800) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp, max = 126.dp).padding(vertical = 5.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFE8E5D8), lineHeight = 23.sp),
                    cursorBrush = SolidColor(Color(0xFFD5E58B)),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (prompt.isBlank()) {
                                Text(
                                    "Describe what you can see in your head…",
                                    color = Color(0xFF8F9981),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30392C)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, tint = Color(0xFFCAD2B7), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ADD IMAGE", color = Color(0xFFCAD2B7), fontSize = 10.sp, letterSpacing = .8.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (memoryEnabled) "MEMORY / ON" else "MEMORY / OFF",
                        color = Color(0xFF96A181),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = .6.sp
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { sendMessage() },
                        enabled = !generating && (prompt.isNotBlank() || selectedImage != null),
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD5E58B),
                            contentColor = Color(0xFF182015),
                            disabledContainerColor = Color(0xFF46503D),
                            disabledContentColor = Color(0xFF8A927E)
                        ),
                        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 8.dp)
                    ) {
                        Text("RENDER", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Rounded.ArrowUpward, null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier.widthIn(max = 324.dp)
                .background(if (isUser) Color(0xFF273329) else Color(0xFF1A2119))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                if (isUser) "YOU  /  PROMPT" else "SRUGEN  /  RESPONSE",
                color = if (isUser) Color(0xFFD5E58B) else Color(0xFFBC8C70),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.1.sp
            )
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    color = Color(0xFFE8E5D8),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp
                )
            }
            image?.let {
                Image(
                    it.asImageBitmap(),
                    "Encrypted chat image",
                    Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
            message.modelName?.let {
                Text(it, color = Color(0xFFD5E58B), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StudioModeControl(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            label,
            color = if (selected) Color(0xFFD5E58B) else Color(0xFF919B83),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp
        )
        Box(
            Modifier.width(31.dp).height(2.dp)
                .background(if (selected) Color(0xFFD5E58B) else Color.Transparent)
        )
    }
}

@Composable
private fun StudioSuggestion(number: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(number, color = Color(0xFFC77B5D), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
        Spacer(Modifier.width(13.dp))
        Text(label, color = Color(0xFFD7D8C9), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text("↗", color = Color(0xFF8F9981), style = MaterialTheme.typography.bodyMedium)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30392C)))
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
