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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.nispax.sruvik.R
import `in`.nispax.sruvik.srugen.*
import `in`.nispax.sruvik.ui.theme.SruvikColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Serialize encrypted-history commits and always write the newest snapshot.
    val historySaveMutex = remember { Mutex() }

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
    val animatedGenerationProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0, 100) / 100f,
        animationSpec = tween(220),
        label = "srugen-generation-progress"
    )
    val animatedDownloadProgress by animateFloatAsState(
        targetValue = if (installing) 0.5f else downloadPercent.coerceIn(0, 100) / 100f,
        animationSpec = tween(220),
        label = "srugen-download-progress"
    )

    val activeChat = chats.firstOrNull { it.id == activeId } ?: draftChat.takeIf { it.id == activeId }

    fun persist(chat: SruGenChat) {
        // Empty drafts are UI state, never history. Attachments count once the user sends them.
        if (chat.messages.none { it.role == "user" && (it.text.isNotBlank() || it.attachmentId != null) }) return
        val updated = (listOf(chat) + chats.filterNot { it.id == chat.id })
            .sortedByDescending { it.updatedAt }
        chats = updated
        scope.launch {
            runCatching {
                historySaveMutex.withLock {
                    // Read the latest UI snapshot only after acquiring the lock.
                    // A delayed older save can therefore never overwrite newer messages.
                    val latestSnapshot = chats
                    withContext(Dispatchers.IO) {
                        SruGenChatStore.saveChats(context, latestSnapshot)
                    }
                }
            }.onFailure { error = "Could not save encrypted chat history: ${it.message}" }
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
        SruGenEngineRegistry.getOrCreate {
            MediaPipeSruGenEngine(
                context.applicationContext,
                File(SruGenModelInstaller.modelDirectory(context), "sd15_bins").absolutePath
            )
        }
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
                    runCatching { manager.remove(id) } // Delete partial archive and return its storage.
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
                        // The model is now unpacked in private storage. Do not keep
                        // the original ~2 GB ZIP occupying the user's phone.
                        runCatching { manager.remove(id) }
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

    val generationInFlight = rememberUpdatedState(generating)
    DisposableEffect(Unit) {
        onDispose {
            thumbnails.values.forEach { if (!it.isRecycled) it.recycle() }
            // Keep an active inference alive if navigation disposes this screen.
            // Otherwise detach before async close so a later visit cannot reuse
            // an engine instance whose native task has already been released.
            val engineToClose = if (generationInFlight.value) null else SruGenEngineRegistry.engine
            if (engineToClose != null) {
                engineToClose.requestStop()
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
                ).launch {
                    runCatching { SruGenEngineRegistry.closeAndDetach(engineToClose) }
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

        val normalizedText = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        val quickReply = if (attachment == null) {
            when (normalizedText) {
                "hi", "hii", "hello", "hey", "heyy", "yo", "sup",
                "good morning", "good afternoon", "good evening" ->
                    "Hey! I'm here. SruGen is currently your on-device image studio. Describe an image you want to create, or attach a photo and choose an editing mode. Stable Diffusion 1.5 creates images; general conversation needs a separate local language model."
                "help", "what can you do", "what can you do here" ->
                    "I can create images from visual prompts using the installed local Stable Diffusion model. You can also attach an image to a chat. Photo editing, background removal and object erasing still need their separate on-device models."
                else -> null
            }
        } else null
        if (quickReply != null) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(role = "assistant", text = quickReply)
            ))
            return
        }

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
        if (!modelReady) {
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
                // Do not allocate the diffusion model until the user's prompt and
                // any attachment references have been durably committed in encrypted
                // app-private storage.
                historySaveMutex.withLock {
                    val latestSnapshot = chats
                    withContext(Dispatchers.IO) {
                        SruGenChatStore.saveChats(context, latestSnapshot)
                    }
                }
                val result = withContext(Dispatchers.Default) {
                    // Recreate a released engine lazily. Android may have asked
                    // Sruvik to free native resources while the app was backgrounded.
                    val engine = SruGenEngineRegistry.getOrCreate {
                        MediaPipeSruGenEngine(
                            context.applicationContext,
                            File(SruGenModelInstaller.modelDirectory(context), "sd15_bins").absolutePath
                        )
                    }
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
            } catch (failure: LinkageError) {
                android.util.Log.e(
                    "SruGen",
                    "MediaPipe could not link a runtime dependency during image generation.",
                    failure
                )
                val failedEngine = SruGenEngineRegistry.engine
                if (failedEngine != null) {
                    failedEngine.requestStop()
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
                    ).launch { runCatching { SruGenEngineRegistry.closeAndDetach(failedEngine) } }
                }
                val newest = chats.firstOrNull { it.id == activeId } ?: updatedChat
                persist(newest.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = newest.messages + SruGenMessage(
                        role = "assistant",
                        text = "SruGen's image engine could not load a compatible Android runtime library. The failure was recorded locally instead of closing the app. Please update Sruvik; no prompt or image was uploaded."
                    )
                ))
            } catch (failure: OutOfMemoryError) {
                val failedEngine = SruGenEngineRegistry.engine
                if (failedEngine != null) {
                    failedEngine.requestStop()
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
                    ).launch { runCatching { SruGenEngineRegistry.closeAndDetach(failedEngine) } }
                }
                System.gc()
                val newest = chats.firstOrNull { it.id == activeId } ?: updatedChat
                persist(newest.copy(
                    updatedAt = System.currentTimeMillis(),
                    messages = newest.messages + SruGenMessage(
                        role = "assistant",
                        text = "Android ran out of memory while loading Stable Diffusion. SruGen released the failed engine. Close other apps and retry; if this keeps happening, this phone may not have enough RAM for this model."
                    )
                ))
                modelReady = SruGenModelInstaller.isInstalled(context)
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

    Box(Modifier.fillMaxSize().background(SruvikColors.Background)) {
        Box(
            Modifier.fillMaxWidth().height(430.dp).align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF29234F).copy(alpha = .68f), Color(0xFF171B35).copy(alpha = .34f), Color.Transparent)
                    )
                )
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 17.dp).padding(top = 13.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "SRUVIK  /  CREATIVE STUDIO",
                        color = SruvikColors.LimeMuted,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.8.sp
                    )
                    Text(
                        "SruGen",
                        color = SruvikColors.TextPrimary,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 42.sp,
                            lineHeight = 46.sp,
                            letterSpacing = (-1.4).sp
                        )
                    )
                }
                Surface(
                    onClick = { historyOpen = true },
                    color = Color.White.copy(alpha = .07f),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .13f))
                ) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.History, null, tint = SruvikColors.TextSecondary, modifier = Modifier.size(17.dp))
                        Text("HISTORY", color = SruvikColors.TextSecondary, letterSpacing = .8.sp, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.width(7.dp))
                Surface(
                    onClick = { newChat() },
                    color = SruvikColors.Lime.copy(alpha = .16f),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SruvikColors.Lime.copy(alpha = .32f))
                ) {
                    Text("+ NEW", Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                        color = SruvikColors.Lime, letterSpacing = .8.sp, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (modelReady) Color(0xFF62C69B).copy(alpha = .15f) else Color(0xFFFFBE79).copy(alpha = .13f),
                    shape = RoundedCornerShape(30.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .10f))
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape)
                            .background(if (modelReady) Color(0xFF7DE0BC) else Color(0xFFFFC98F)))
                        Text(
                            if (modelReady) "SD 1.5  /  ON DEVICE" else "LOCAL ENGINE  /  NOT INSTALLED",
                            color = if (modelReady) Color(0xFF9AE9CC) else Color(0xFFFFC98F),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = .8.sp
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "PROCESS  ${resourceStats.pss} MB",
                    Modifier.clickable { monitorExpanded = !monitorExpanded }
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = .06f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    color = SruvikColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = .4.sp
                )
            }

            AnimatedVisibility(
                visible = monitorExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(130)) + shrinkVertically(tween(130))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("CPU ${String.format("%.1f", resourceStats.cpu)}%", color = Color(0xFFB7BDA8), style = MaterialTheme.typography.labelSmall)
                    Text("HEAP ${resourceStats.heap} MB", color = Color(0xFFB7BDA8), style = MaterialTheme.typography.labelSmall)
                    Text("FREE ${resourceStats.available} MB", color = Color(0xFFB7BDA8), style = MaterialTheme.typography.labelSmall)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(SruvikColors.Border))
            SruGenModelRoadmap(modelReady)
            AnimatedVisibility(
                visible = !modelReady,
                enter = fadeIn(tween(220)) + expandVertically(tween(220)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(160))
            ) {
                SruGenGlassPanel(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    fill = Brush.linearGradient(listOf(Color(0xB9342D69), Color(0xB51D294A), Color(0xCC171D35)))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.sruvik_glass_art),
                            contentDescription = "Sruvik abstract artwork",
                            modifier = Modifier.size(width = 74.dp, height = 72.dp).clip(RoundedCornerShape(15.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("YOUR LOCAL IMAGE ENGINE", color = SruvikColors.LimeMuted,
                                style = MaterialTheme.typography.labelSmall, letterSpacing = .9.sp)
                            Text("Bring SruGen to life", color = SruvikColors.TextPrimary,
                                style = MaterialTheme.typography.titleMedium)
                            Text("SD 1.5 · 1.9 GiB · guarded local inference",
                                color = SruvikColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { showInstallDialog = true },
                            enabled = !installing && downloadId < 0L,
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SruvikColors.Lime,
                                contentColor = Color(0xFF17152B)
                            ),
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 8.dp)
                        ) {
                            Text("INSTALL", fontSize = 10.sp, letterSpacing = .7.sp)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = downloadId >= 0L || installing,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(130)) + shrinkVertically(tween(130))
            ) {
                SruGenGlassPanel(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    fill = Brush.linearGradient(listOf(Color(0xAA2B2954), Color(0xAA18233C)))
                ) {
                    Column(Modifier.fillMaxWidth().padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            if (installing) installMessage else "DOWNLOADING MODEL  /  $downloadPercent%",
                            color = SruvikColors.Lime,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = .9.sp
                        )
                        LinearProgressIndicator(
                            progress = { animatedDownloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = SruvikColors.Lime,
                            trackColor = Color.White.copy(alpha = .12f)
                        )
                        if (downloadId >= 0L) {
                            TextButton(onClick = {
                                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
                                SruGenModelInstaller.clearDownloadId(context)
                                downloadId = -1L
                                info = "Model download cancelled."
                            }) { Text("CANCEL DOWNLOAD", color = SruvikColors.TextSecondary, fontSize = 10.sp) }
                        }
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
                                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(13.dp)
                            ) {
                                SruGenHeroArtwork(
                                    artRes = R.drawable.sruvik_city_art,
                                    eyebrow = "01  /  LOCAL IMAGE STUDIO",
                                    title = "Imagine\nbeyond the ordinary.",
                                    subtitle = "Describe a world. SruGen builds it privately on this device."
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("EXPLORE A STARTING POINT", color = SruvikColors.LimeMuted,
                                        style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text("TAP TO USE", color = SruvikColors.TextMuted,
                                        style = MaterialTheme.typography.labelSmall, letterSpacing = .7.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    SruGenArtworkSuggestion(
                                        Modifier.weight(1f),
                                        R.drawable.sruvik_city_art,
                                        "Dreamscape",
                                        "A quiet city after monsoon",
                                        onClick = {
                                            prompt = "A quiet city after the monsoon, wet laterite streets, warm windows, cinematic natural light"
                                        }
                                    )
                                    SruGenArtworkSuggestion(
                                        Modifier.weight(1f),
                                        R.drawable.sruvik_glass_art,
                                        "Fluid forms",
                                        "A machine grown like a plant",
                                        onClick = {
                                            prompt = "An intricate machine grown like a plant, organic metal, botanical forms, tactile editorial photography"
                                        }
                                    )
                                    SruGenArtworkSuggestion(
                                        Modifier.weight(1f),
                                        R.drawable.sruvik_alpine_art,
                                        "Elsewhere",
                                        "A place that never existed",
                                        onClick = {
                                            prompt = "A nostalgic photograph of a place that never existed, imperfect film grain, soft afternoon light"
                                        }
                                    )
                                }
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
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SruvikColors.Lime)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("RENDERING LOCALLY  /  $progress%", color = SruvikColors.Lime, style = MaterialTheme.typography.labelSmall, letterSpacing = .8.sp)
                                    LinearProgressIndicator(
                                        progress = { animatedGenerationProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = SruvikColors.Lime,
                                        trackColor = SruvikColors.Border
                                    )
                                    Text(progressLabel, color = SruvikColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = error.isNotBlank(),
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(130)) + shrinkVertically(tween(130))
            ) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(3.dp).height(24.dp).background(Color(0xFFD47C64)))
                    Spacer(Modifier.width(8.dp))
                    Text(error, color = Color(0xFFF0A28E), style = MaterialTheme.typography.bodySmall)
                }
            }
            AnimatedVisibility(
                visible = info.isNotBlank(),
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(130)) + shrinkVertically(tween(130))
            ) {
                if (info.isNotBlank()) {
                    Text(info, Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Color(0xFFADB99A), style = MaterialTheme.typography.bodySmall)
                }
            }

            SruGenGlassPanel(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(25.dp),
                fill = Brush.linearGradient(listOf(Color(0xE52B2851), Color(0xE51B2540), Color(0xEC151A31)))
            ) {
            Column(
                Modifier.fillMaxWidth().animateContentSize()
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            Text("REFERENCE / ENCRYPTED", color = SruvikColors.Lime, style = MaterialTheme.typography.labelSmall, letterSpacing = .7.sp)
                            Text("Stored locally with this draft until sent", color = SruvikColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            selectedImage = null
                            SruGenChatStore.deleteAttachment(context, id)
                            thumbnails[id]?.takeIf { !it.isRecycled }?.recycle()
                            thumbnails = thumbnails - id
                        }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.Close, "Remove image", tint = SruvikColors.TextSecondary)
                        }
                    }
                }
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(1800) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp, max = 126.dp).padding(vertical = 5.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = SruvikColors.TextPrimary, lineHeight = 23.sp),
                    cursorBrush = SolidColor(SruvikColors.Lime),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (prompt.isBlank()) {
                                Text(
                                    "Describe what you can see in your head…",
                                    color = SruvikColors.TextMuted,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(SruvikColors.Border))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, tint = SruvikColors.TextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ADD IMAGE", color = SruvikColors.TextSecondary, fontSize = 10.sp, letterSpacing = .8.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (memoryEnabled) "MEMORY / ON" else "MEMORY / OFF",
                        color = SruvikColors.LimeMuted,
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
                            containerColor = SruvikColors.Lime,
                            contentColor = Color(0xFF182015),
                            disabledContainerColor = SruvikColors.SurfaceSoft,
                            disabledContentColor = SruvikColors.TextMuted
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
    }

    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text("Install SruGen's local image engine", color = SruvikColors.TextPrimary) },
            text = {
                Text(
                    "Stable Diffusion 1.5 · MediaPipe\n\nThe model archive is about 1.91 GB. SruGen requires at least 8 GB free internal storage to stage it without filling your phone. Generation is blocked unless the device has at least 8 GB physical RAM, 4 GB currently available RAM, 1 GB free storage, acceptable thermal status, and Battery Saver off. Memory and temperature are checked again while generating. This is a conservative risk-reduction system, not a guarantee against Android system-level process kills.\n\nThe model is under CreativeML OpenRAIL-M; please review its use restrictions. Nothing downloads until you confirm.",
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
            containerColor = Color(0xFF191A31),
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
                                .background(if (item.id == activeId) Color(0xFF3A3568) else Color.Transparent)
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
            containerColor = Color(0xFF191A31)
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
            containerColor = Color(0xFF191A31)
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
            containerColor = Color(0xFF191A31)
        )
    }
}


@Composable
private fun SruGenModelRoadmap(modelReady: Boolean) {
    val models = SruGenModelCatalog.models
    SruGenGlassPanel(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        fill = Brush.linearGradient(listOf(Color(0xAA27284D), Color(0xAA19243D)))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                "MODEL CATALOG  /  VERIFIED PACKS ONLY",
                color = SruvikColors.LimeMuted,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = .9.sp
            )
            models.forEach { model ->
                val ready = model.availability == SruGenModelAvailability.READY_IN_APP
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${model.displayName} · ${model.runtime}",
                            color = SruvikColors.TextPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            model.detail,
                            color = SruvikColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 15.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (ready) { if (modelReady) "ACTIVE" else "READY" } else "LOCKED",
                        color = if (ready) Color(0xFF9AE9CC) else Color(0xFFFFC98F),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = .5.sp
                    )
                }
            }
            Text(
                "DreamShaper 8 now has a community MNN conversion and Sruvik includes the pinned Nezumi Kiln arm64 runtime, but the model archive hash and device validation are still pending. SD 3.5, Sana and Wan need separate native pipelines. Nothing is enabled until its exact model files, memory behavior and device tests are verified.",
                color = SruvikColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun SruGenGlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    fill: Brush = Brush.linearGradient(listOf(Color(0xCC29264D), Color(0xCC1B2440))),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier.clip(shape)
            .background(fill)
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = .23f),
                        SruvikColors.GlassBorder.copy(alpha = .70f),
                        Color.White.copy(alpha = .06f)
                    )
                ),
                shape
            ),
        content = content
    )
}

@Composable
private fun SruGenHeroArtwork(
    artRes: Int,
    eyebrow: String,
    title: String,
    subtitle: String
) {
    Box(
        Modifier.fillMaxWidth().height(190.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(24.dp))
    ) {
        Image(
            painter = painterResource(artRes),
            contentDescription = "SruGen creative studio artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0x11101428), Color(0x55201C40), Color(0xED111326))
                )
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(eyebrow, color = SruvikColors.Lime, style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp)
            Text(title, color = SruvikColors.TextPrimary,
                style = MaterialTheme.typography.headlineMedium.copy(lineHeight = 34.sp))
            Text(subtitle, color = Color(0xFFE0DFF0), style = MaterialTheme.typography.bodySmall,
                maxLines = 2)
        }
    }
}

@Composable
private fun SruGenArtworkSuggestion(
    modifier: Modifier,
    artRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Box(
        modifier.height(116.dp)
            .clip(RoundedCornerShape(17.dp))
            .border(1.dp, Color.White.copy(alpha = .17f), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(artRes),
            contentDescription = "$title inspiration",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0xE8101124)))
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(subtitle, color = Color(0xFFD8D7E7), style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}

@Composable
private fun SruGenMessageBubble(message: SruGenMessage, image: Bitmap?) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        SruGenGlassPanel(
            Modifier.widthIn(max = 324.dp),
            shape = RoundedCornerShape(22.dp),
            fill = if (isUser) {
                Brush.linearGradient(listOf(Color(0xCC463F78), Color(0xCC283653)))
            } else {
                Brush.linearGradient(listOf(Color(0xCC25294A), Color(0xCC17243B)))
            }
        ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                if (isUser) "YOU  /  PROMPT" else "SRUGEN  /  RESPONSE",
                color = if (isUser) SruvikColors.Lime else SruvikColors.Mint,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.1.sp
            )
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    color = SruvikColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp
                )
            }
            image?.let {
                Image(
                    it.asImageBitmap(),
                    "Encrypted chat image",
                    Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    contentScale = ContentScale.Fit
                )
            }
            message.modelName?.let {
                Text(it, color = SruvikColors.Lime, style = MaterialTheme.typography.labelSmall)
            }
        }
        }
    }
}

@Composable
private fun StudioModeControl(label: String, selected: Boolean, onClick: () -> Unit) {
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(180),
        label = "studio-mode-selection"
    )
    Row(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(SruvikColors.Lime.copy(alpha = .06f + .14f * selectionProgress))
            .border(
                1.dp,
                SruvikColors.Lime.copy(alpha = .08f + .22f * selectionProgress),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (selected) SruvikColors.Lime else SruvikColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = .7.sp
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
        Text("↗", color = SruvikColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(SruvikColors.Border))
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
                    .background(Color(0xEE211F3E))
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
                color = Color(0xEE211F3E),
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
