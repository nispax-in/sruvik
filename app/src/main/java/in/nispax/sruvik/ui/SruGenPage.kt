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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import java.io.ByteArrayOutputStream
import java.io.File

private data class SruGenResourceStats(
    val cpu: Float = 0f,
    val pss: Int = 0,
    val heap: Int = 0,
    val available: Int = 0
)

@Composable
fun SruGenPage(memoryEnabled: Boolean, onOpenModels: () -> Unit) {
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
    var activeModelId by remember { mutableStateOf(SruGenActiveModelStore.activeModelId(context)) }
    val activeModel = remember(activeModelId) { SruGenGalleryCatalog.find(activeModelId) }
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
    val generationState by SruGenGenerationSession.state.collectAsState()
    val messageListState = rememberLazyListState()
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

    fun stage(chat: SruGenChat) {
        if (chat.messages.none { it.role == "user" && (it.text.isNotBlank() || it.attachmentId != null) }) return
        chats = (listOf(chat) + chats.filterNot { it.id == chat.id })
            .sortedByDescending { it.updatedAt }
    }

    fun persist(chat: SruGenChat) {
        stage(chat)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { SruGenChatStore.upsertChat(context, chat) }
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
            SruGenEngineFactory.create(context.applicationContext, activeModelId)
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
            SruGenEngineRegistry.engine?.let { old ->
                old.requestStop()
                withContext(Dispatchers.IO) { SruGenEngineRegistry.closeAndDetach(old) }
            }
            SruGenActiveModelStore.setActiveModel(context, "sd15-mediapipe")
            activeModelId = "sd15-mediapipe"
            activateInstalledModel()
            info = "Stable Diffusion 1.5 installed and selected. SruGen is ready for offline image generation."
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
                        SruGenEngineRegistry.engine?.let { old ->
                            old.requestStop()
                            withContext(Dispatchers.IO) { SruGenEngineRegistry.closeAndDetach(old) }
                        }
                        SruGenActiveModelStore.setActiveModel(context, "sd15-mediapipe")
                        activeModelId = "sd15-mediapipe"
                        activateInstalledModel()
                        info = "Stable Diffusion 1.5 installed and selected. Generation runs locally."
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
        activeId = generationState.chatId
            ?.takeIf { id -> generationState.status == SruGenGenerationStatus.RUNNING && chats.any { it.id == id } }
            ?: chats.firstOrNull()?.id ?: draftChat.id
        activeModelId = SruGenActiveModelStore.activeModelId(context)
        modelReady = withContext(Dispatchers.IO) {
            SruGenEngineFactory.isInstalled(context, activeModelId)
        }
        if (modelReady && generationState.status != SruGenGenerationStatus.RUNNING) activateInstalledModel()
        downloadId = SruGenModelInstaller.savedDownloadId(context)
    }

    LaunchedEffect(generationState.status, generationState.chatId, generationState.updatedAt, activeId) {
        generating = generationState.status == SruGenGenerationStatus.RUNNING && generationState.chatId == activeId
        if (generating) {
            progress = generationState.progress
            progressLabel = generationState.progressLabel
        }
        if (generationState.status == SruGenGenerationStatus.COMPLETED ||
            generationState.status == SruGenGenerationStatus.FAILED
        ) {
            progress = 0
            progressLabel = ""
            chats = withContext(Dispatchers.IO) { SruGenChatStore.loadChats(context) }
            if (generationState.chatId == activeId) info = generationState.message
            generating = false
        }
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
            val taskStillRunning = SruGenGenerationSession.state.value.status == SruGenGenerationStatus.RUNNING
            val engineToClose = if (generationInFlight.value || taskStillRunning) null else SruGenEngineRegistry.engine
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

    fun importReferenceImage(uri: Uri) {
        scope.launch {
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
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format cannot be previewed." }
                        var sample = 1
                        while (
                            bounds.outWidth / sample > 2048 ||
                            bounds.outHeight / sample > 2048 ||
                            (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 3000000L
                        ) sample *= 2
                        BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.size,
                            BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                        ) ?: error("This image format cannot be previewed.")
                    }
                    val id = withContext(Dispatchers.IO) {
                        SruGenChatStore.saveAttachment(
                            context, bytes, context.contentResolver.getType(uri) ?: "image/jpeg"
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
            } catch (failure: OutOfMemoryError) {
                error = "This image is too large to preview safely. Choose a smaller photo."
            } catch (failure: Exception) {
                error = failure.message ?: "Unable to attach image."
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) importReferenceImage(uri)
    }
    val imageFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importReferenceImage(uri)
    }
    fun launchImagePicker() {
        try {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (pickerError: Exception) {
            runCatching { imageFilePicker.launch(arrayOf("image/*")) }
                .onFailure { fallbackError -> error = "Unable to open image picker: ${fallbackError.message ?: pickerError.message ?: "unknown error"}" }
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
        stage(updatedChat)
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
                    "Hey! I'm here. SruGen creates images locally with your selected model, ${activeModel?.name ?: "installed image model"}. General conversation needs a separate language model."
                "help", "what can you do", "what can you do here" ->
                    "I can create images locally with ${activeModel?.name ?: "your installed image model"}. Reference-image editing, background removal and object erasing are not available yet."
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
                    text = "Your reference image is saved in this encrypted chat, but ${activeModel?.name ?: "the selected model"} currently supports text-to-image only. I did not use the attachment for this generation."
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
                SruGenMode.PROMPT_EDIT -> "Reference-image editing is not available in this build. Your original image remains safely attached to this encrypted conversation."
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
                text = "Your prompt and image remain saved in this encrypted chat. The selected model is not installed yet. Open Models to install a compatible model before generating."
            )))
            return
        }

        val remembered = if (memoryEnabled) SruGenChatStore.memoryContext(chats, chat.id) else ""
        val generationPrompt = if (remembered.isBlank()) text else
            "Use the recent creative context only for style continuity. Follow the current prompt first.\nRecent creative context:\n$remembered\n\nCurrent prompt: $text"

        generating = SruGenGenerationSession.start(
            context = context,
            chatWithPrompt = updatedChat,
            generationPrompt = generationPrompt,
            modelId = activeModelId
        )
        progress = 0
        progressLabel = "Preparing local model"
        if (!generating) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(
                    role = "assistant",
                    text = "Another image is already being generated. Please wait for it to finish."
                )
            ))
        }
    }
    val studioInk = Color(0xFF0D0D0D)
    val studioPanel = Color(0xFF202020)
    val studioRaised = Color(0xFF2D2D2D)
    val studioAccent = Color(0xFFE47F5D)
    val studioText = Color(0xFFF4F2EF)
    val studioSecondary = Color(0xFFBDB9B5)

    Box(Modifier.fillMaxSize().background(studioInk)) {
        Column(
            Modifier.fillMaxSize()
                .padding(horizontal = 18.dp)
                .padding(top = 10.dp, bottom = 104.dp)
                .imePadding()
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                IconButton(
                    onClick = { historyOpen = true },
                    modifier = Modifier.size(50.dp).clip(CircleShape).background(studioRaised)
                ) {
                    Icon(Icons.Rounded.Menu, "Open conversation history", tint = studioText, modifier = Modifier.size(25.dp))
                }
                Text(
                    "SruGen",
                    modifier = Modifier.weight(1f),
                    color = studioText,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 36.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1.2).sp
                    )
                )
                IconButton(
                    onClick = { monitorExpanded = !monitorExpanded },
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(studioRaised)
                ) {
                    Icon(Icons.Rounded.Tune, "Device performance", tint = studioText, modifier = Modifier.size(23.dp))
                }
                Button(
                    onClick = { newChat() },
                    modifier = Modifier.height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = studioAccent,
                        contentColor = Color(0xFF1A100D)
                    ),
                    contentPadding = PaddingValues(horizontal = 15.dp)
                ) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            AnimatedVisibility(
                visible = monitorExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(130)) + shrinkVertically(tween(130))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(studioPanel)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("DEVICE PERFORMANCE", color = studioText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "CPU  ${String.format("%.1f", resourceStats.cpu)}%     MEMORY  ${resourceStats.pss} MB     FREE RAM  ${resourceStats.available} MB",
                        color = studioSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            AnimatedVisibility(
                visible = downloadId >= 0L || installing,
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(130)) + shrinkVertically(tween(130))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(studioPanel)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text(
                        if (installing) installMessage else "Downloading model · $downloadPercent%",
                        color = studioText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    LinearProgressIndicator(
                        progress = { animatedDownloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = studioAccent,
                        trackColor = Color.White.copy(alpha = .14f)
                    )
                    if (downloadId >= 0L) {
                        OutlinedButton(
                            onClick = {
                                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
                                SruGenModelInstaller.clearDownloadId(context)
                                downloadId = -1L
                                info = "Model download cancelled."
                            },
                            modifier = Modifier.height(44.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = studioText)
                        ) { Text("Cancel download", fontSize = 14.sp) }
                    }
                }
            }

            activeChat?.let { chat ->
                LazyColumn(
                    state = messageListState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (chat.messages.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillParentMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                SruGenCreativeSpark()
                                Spacer(Modifier.height(27.dp))
                                Text(
                                    "Let's noodle.",
                                    color = studioText,
                                    fontSize = 32.sp,
                                    lineHeight = 38.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(Modifier.height(9.dp))
                                Text(
                                    "Make something that exists only in your imagination.",
                                    color = studioSecondary,
                                    fontSize = 17.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                if (!modelReady) {
                                    Spacer(Modifier.height(22.dp))
                                    Text(
                                        "Choose a compatible model to get started.",
                                        color = studioSecondary,
                                        fontSize = 15.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(24.dp),
                                    strokeWidth = 3.dp,
                                    color = studioAccent
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text(
                                        "Creating your image · $progress%",
                                        color = studioText,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    LinearProgressIndicator(
                                        progress = { animatedGenerationProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = studioAccent,
                                        trackColor = studioRaised
                                    )
                                    Text(progressLabel, color = studioSecondary, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }

            LaunchedEffect(activeChat?.id, activeChat?.messages?.size, generating) {
                kotlinx.coroutines.yield()
                val lastItem = messageListState.layoutInfo.totalItemsCount - 1
                if (lastItem >= 0) messageListState.animateScrollToItem(lastItem)
            }
            AnimatedVisibility(
                visible = error.isNotBlank(),
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(130)) + shrinkVertically(tween(130))
            ) {
                Text(
                    error,
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    color = Color(0xFFFFA38E),
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            }
            AnimatedVisibility(
                visible = info.isNotBlank(),
                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                exit = fadeOut(tween(130)) + shrinkVertically(tween(130))
            ) {
                Text(
                    info,
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    color = Color(0xFFBFD7C8),
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            }

            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(25.dp))
                    .background(studioPanel)
                    .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(25.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                selectedImage?.let { id ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        thumbnails[id]?.let { bitmap ->
                            Image(
                                bitmap.asImageBitmap(),
                                "Attached reference image",
                                Modifier.size(60.dp).clip(RoundedCornerShape(13.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Text(
                            "Image attached",
                            Modifier.weight(1f),
                            color = studioText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        IconButton(
                            onClick = {
                                selectedImage = null
                                SruGenChatStore.deleteAttachment(context, id)
                                thumbnails[id]?.takeIf { !it.isRecycled }?.recycle()
                                thumbnails = thumbnails - id
                            },
                            modifier = Modifier.size(46.dp).clip(CircleShape).background(studioRaised)
                        ) {
                            Icon(Icons.Rounded.Close, "Remove image", tint = studioText, modifier = Modifier.size(22.dp))
                        }
                    }
                }

                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(1800) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp, max = 116.dp)
                        .padding(vertical = 4.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = studioText,
                        fontSize = 18.sp,
                        lineHeight = 25.sp
                    ),
                    cursorBrush = SolidColor(studioAccent),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (prompt.isBlank()) {
                                Text(
                                    when (mode) {
                                        SruGenMode.TEXT_TO_IMAGE -> "Describe what you want to create…"
                                        SruGenMode.PROMPT_EDIT -> "Describe how to change the image…"
                                        SruGenMode.REMOVE_BACKGROUND -> "Describe the subject to keep…"
                                        SruGenMode.REMOVE_OBJECT -> "Describe what to remove…"
                                    },
                                    color = Color(0xFFAAA6A2),
                                    fontSize = 18.sp,
                                    lineHeight = 25.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    IconButton(
                        onClick = { launchImagePicker() },
                        modifier = Modifier.size(52.dp).clip(CircleShape).background(studioRaised)
                    ) {
                        Icon(Icons.Rounded.Add, "Attach a reference image", tint = studioText, modifier = Modifier.size(25.dp))
                    }
                    OutlinedButton(
                        onClick = onOpenModels,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .16f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = studioText),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = studioAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(activeModel?.name ?: "Choose model", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.ExpandMore, null, tint = studioSecondary, modifier = Modifier.size(18.dp))
                    }
                    Button(
                        onClick = { sendMessage() },
                        enabled = !generating && (prompt.isNotBlank() || selectedImage != null),
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = studioAccent,
                            contentColor = Color(0xFF1A100D),
                            disabledContainerColor = Color(0xFF45413F),
                            disabledContentColor = Color(0xFF96918D)
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, "Create image", modifier = Modifier.size(23.dp))
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
                            val model = requireNotNull(SruGenGalleryCatalog.find("sd15-mediapipe"))
                            val deviceCheck = withContext(Dispatchers.IO) {
                                SruGenModelCompatibility.assess(context, model)
                            }
                            require(deviceCheck.canDownload) { deviceCheck.reasons.joinToString("\n") }
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
private fun SruGenCreativeSpark() {
    val motion = rememberInfiniteTransition(label = "srugen-creative-spark")
    val rotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "srugen-spark-rotation"
    )
    androidx.compose.foundation.Canvas(Modifier.size(96.dp)) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val inner = size.minDimension * .13f
        for (index in 0 until 12) {
            val radians = Math.toRadians(index * 30.0 + rotation.toDouble())
            val outer = size.minDimension * if (index % 3 == 0) .46f else .37f
            val start = Offset(
                centerX + cos(radians).toFloat() * inner,
                centerY + sin(radians).toFloat() * inner
            )
            val end = Offset(
                centerX + cos(radians).toFloat() * outer,
                centerY + sin(radians).toFloat() * outer
            )
            drawLine(
                color = Color(0xFFE47F5D).copy(alpha = if (index % 3 == 0) 1f else .82f),
                start = start,
                end = end,
                strokeWidth = size.minDimension * .055f,
                cap = StrokeCap.Round
            )
        }
        drawCircle(Color(0xFFE47F5D), radius = size.minDimension * .065f)
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
        Column(
            Modifier.widthIn(max = 324.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (isUser) Color(0xFF303030) else Color(0xFF1B1B1B))
                .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(20.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (isUser) "You" else "SruGen",
                color = if (isUser) Color(0xFFEBA087) else Color(0xFFBDB9B5),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    color = Color(0xFFF4F2EF),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp)
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
                Text(it, color = Color(0xFFEBA087), fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
