package `in`.nispax.sruvik.ui

import android.app.ActivityManager
import android.app.DownloadManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.ParcelFileDescriptor
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.ui.draw.clipToBounds
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

private data class SruGenResourceStats(
    val cpu: Float = 0f,
    val pss: Int = 0,
    val heap: Int = 0,
    val available: Int = 0
)

private data class PendingSruGenRequest(
    val chat: SruGenChat,
    val generationPrompt: String,
    val modelId: String,
    val mode: SruGenMode,
    val sourceAttachmentId: String?
)

@Composable
fun SruGenPage(
    memoryEnabled: Boolean,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenVault: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<SruGenChat>>(emptyList()) }
    var draftChat by remember { mutableStateOf(SruGenChat(title = "New chat")) }
    var activeId by remember { mutableStateOf(draftChat.id) }
    var prompt by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SruGenMode.TEXT_TO_IMAGE) }
    var selectedImage by remember { mutableStateOf<String?>(null) }
    var importingImage by remember { mutableStateOf(false) }
    var attachMenuExpanded by remember { mutableStateOf(false) }
    var thumbnails by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var generating by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var progressLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var viewingImage by remember { mutableStateOf<Pair<Bitmap, String>?>(null) }
    var loadingViewerImage by remember { mutableStateOf(false) }
    var pendingGeneration by remember { mutableStateOf<PendingSruGenRequest?>(null) }
    var pendingGallerySave by remember { mutableStateOf<Pair<String, String>?>(null) }
    var modelReady by remember { mutableStateOf(false) }
    var activeModelId by remember { mutableStateOf(SruGenActiveModelStore.activeModelId(context)) }
    val activeModel = remember(activeModelId) { SruGenGalleryCatalog.find(activeModelId) }
    var downloadId by remember { mutableLongStateOf(-1L) }
    var downloadPercent by remember { mutableIntStateOf(0) }
    var installing by remember { mutableStateOf(false) }
    var installMessage by remember { mutableStateOf("") }
    var showInstallDialog by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    // Reopening this destination after a tab switch should follow an in-flight
    // generation until its chat is restored. Explicit history/new-chat navigation
    // opts out so the user is never forcibly pulled away from another conversation.
    var followGenerationChat by remember { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<SruGenChat?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SruGenChat?>(null) }
    var monitorExpanded by remember { mutableStateOf(false) }
    var headerMenuExpanded by remember { mutableStateOf(false) }
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
        followGenerationChat = false
        // Start a volatile draft. It only enters encrypted history after the first user message.
        val draft = SruGenChat(title = "New chat")
        draftChat = draft
        activeId = draft.id
        prompt = ""
        selectedImage = null
        mode = SruGenMode.TEXT_TO_IMAGE
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
        // Restore the conversation that owns the last generation even when it
        // finished while this destination was disposed. Otherwise returning from
        // another tab silently opens a different chat and makes the generated image
        // look as if it vanished.
        activeId = generationState.chatId
            ?.takeIf { id ->
                generationState.status in setOf(
                    SruGenGenerationStatus.RUNNING,
                    SruGenGenerationStatus.COMPLETED,
                    SruGenGenerationStatus.CANCELLED,
                    SruGenGenerationStatus.FAILED
                ) && chats.any { it.id == id }
            }
            ?: chats.firstOrNull()?.id ?: draftChat.id
        activeModelId = SruGenActiveModelStore.activeModelId(context)
        modelReady = withContext(Dispatchers.IO) {
            SruGenEngineFactory.isInstalled(context, activeModelId)
        }
        if (modelReady && generationState.status != SruGenGenerationStatus.RUNNING) activateInstalledModel()
        downloadId = SruGenModelInstaller.savedDownloadId(context)
    }

    LaunchedEffect(
        generationState.status,
        generationState.chatId,
        generationState.updatedAt,
        activeId,
        followGenerationChat
    ) {
        val generationChatId = generationState.chatId
        generating = generationState.status == SruGenGenerationStatus.RUNNING &&
            generationChatId == activeId
        if (generating) {
            progress = generationState.progress
            progressLabel = generationState.progressLabel
        }

        // The session writes the prompt asynchronously before model loading. If
        // the user returns to SruGen during that small window, the first history
        // read can legitimately be empty. Re-read when the app-scoped session
        // advances and attach the restored screen to the owning conversation.
        val shouldRefreshRunningChat =
            generationState.status == SruGenGenerationStatus.RUNNING &&
                followGenerationChat &&
                !generationChatId.isNullOrBlank() &&
                activeId != generationChatId
        val generationFinished =
            generationState.status == SruGenGenerationStatus.COMPLETED ||
                generationState.status == SruGenGenerationStatus.CANCELLED ||
                generationState.status == SruGenGenerationStatus.FAILED
        if (shouldRefreshRunningChat || generationFinished) {
            val refreshed = withContext(Dispatchers.IO) { SruGenChatStore.loadChats(context) }
            chats = refreshed
            if (followGenerationChat && !generationChatId.isNullOrBlank() &&
                refreshed.any { it.id == generationChatId }
            ) {
                activeId = generationChatId
            }
            if (generationFinished) {
                progress = 0
                progressLabel = ""
                if (generationChatId == activeId) info = generationState.message
                generating = false
            }
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
    val latestThumbnails = rememberUpdatedState(thumbnails)
    val latestViewerImage = rememberUpdatedState(viewingImage)
    DisposableEffect(Unit) {
        onDispose {
            latestViewerImage.value?.first?.let { if (!it.isRecycled) it.recycle() }
            latestThumbnails.value.values.forEach { if (!it.isRecycled) it.recycle() }
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

    fun openAttachmentImage(attachmentId: String) {
        if (loadingViewerImage || viewingImage != null) return
        loadingViewerImage = true
        scope.launch {
            try {
                val fullBitmap = withContext(Dispatchers.IO) {
                    val bytes = SruGenChatStore.readAttachment(context, attachmentId)
                    try {
                        decodeSruvikViewerBitmap(bytes)
                    } finally {
                        bytes.fill(0)
                    }
                }
                if (fullBitmap == null) error = "This private image could not be decoded."
                else viewingImage = fullBitmap to "SruGen image"
            } catch (failure: Exception) {
                error = failure.message ?: "Unable to open this private image."
            } finally {
                loadingViewerImage = false
            }
        }
    }

    fun importReferenceImage(uri: Uri) {
        if (importingImage) return
        importingImage = true
        attachMenuExpanded = false
        error = ""
        info = "Reading and encrypting selected image…"
        // Photo Picker and OpenDocument provide item-scoped grants. Keep the
        // grant when the provider allows it, but never request whole-library access.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        scope.launch {
            var bytes: ByteArray? = null
            var preview: Bitmap? = null
            try {
                val imported = withContext(Dispatchers.IO) { readSruGenImage(context, uri) }
                bytes = imported.first
                val mimeType = imported.second
                preview = withContext(Dispatchers.Default) {
                    decodeSruGenImagePreview(context, uri, imported.first)
                }
                val id = withContext(Dispatchers.IO) {
                    SruGenChatStore.saveAttachment(context, imported.first, mimeType)
                }
                selectedImage?.let { old ->
                    SruGenChatStore.deleteAttachment(context, old)
                    thumbnails[old]?.takeIf { !it.isRecycled }?.recycle()
                    thumbnails = thumbnails - old
                }
                thumbnails = thumbnails + (id to requireNotNull(preview))
                preview = null // Ownership moved into Compose state.
                selectedImage = id
                if (mode == SruGenMode.TEXT_TO_IMAGE) {
                    mode = SruGenMode.PROMPT_EDIT
                }
                info = if (mode == SruGenMode.PROMPT_EDIT) {
                    "Image attached. Edit clothes mode selected; choose Create to use text-to-image instead."
                } else {
                    "Image attached and encrypted in this local conversation."
                }
                error = ""
            } catch (failure: OutOfMemoryError) {
                error = "This image is too large to preview safely. Choose a smaller photo."
                info = ""
            } catch (failure: Exception) {
                android.util.Log.e("SruGen", "Reference image import failed for $uri.", failure)
                error = failure.message ?: "Unable to attach image. Try Browse files or save a copy on this device."
                info = ""
            } finally {
                bytes?.fill(0)
                preview?.takeUnless { it.isRecycled }?.recycle()
                importingImage = false
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) importReferenceImage(uri)
        else if (!importingImage) info = "No photo selected."
    }
    val imageFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importReferenceImage(uri)
        else if (!importingImage) info = "No file selected."
    }
    fun launchImagePicker() {
        if (importingImage || generating) return
        try {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (pickerError: Exception) {
            try {
                imageFilePicker.launch(arrayOf("image/*"))
            } catch (fallbackError: Exception) {
                error = "Unable to open Photo library or Files: ${fallbackError.message ?: pickerError.message ?: "unknown error"}"
            }
        }
    }
    fun launchImageFilePicker() {
        if (importingImage || generating) return
        try {
            imageFilePicker.launch(arrayOf("image/*"))
        } catch (failure: Exception) {
            error = "Unable to open Files: ${failure.message ?: "unknown error"}"
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


    fun startGeneration(request: PendingSruGenRequest) {
        try {
            SruGenGenerationService.start(
                context,
                request.chat,
                request.generationPrompt,
                request.modelId,
                request.mode,
                request.sourceAttachmentId
            )
            generating = true
            progress = 0
            progressLabel = if (request.mode == SruGenMode.PROMPT_EDIT) "Preparing automatic image edit"
                else if (request.mode == SruGenMode.REMOVE_BACKGROUND) "Preparing subject mask"
                else "Preparing local model"
            error = ""
            info = "Processing is running on this device. Stop it here or from its notification."
        } catch (failure: Exception) {
            generating = false
            error = failure.message ?: "Unable to start SruGen."
            info = ""
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingGeneration
        pendingGeneration = null
        if (request != null) {
            startGeneration(request)
            if (!granted) {
                info = "Notifications are disabled. Processing continues in Sruvik; the in-app Stop control remains available."
            }
        }
    }

    fun startGenerationWithNotificationPermission(
        chat: SruGenChat,
        generationPrompt: String,
        modelId: String,
        requestMode: SruGenMode = SruGenMode.TEXT_TO_IMAGE,
        sourceAttachmentId: String? = null
    ) {
        val request = PendingSruGenRequest(chat, generationPrompt, modelId, requestMode, sourceAttachmentId)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingGeneration = request
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startGeneration(request)
        }
    }

    fun saveAttachmentToDeviceNow(attachmentId: String, mimeType: String) {
        scope.launch {
            var imageBytes: ByteArray? = null
            error = ""
            info = "Saving to Pictures/Sruvik…"
            try {
                val savedUri = withContext(Dispatchers.IO) {
                    imageBytes = SruGenChatStore.readAttachment(context, attachmentId)
                    SruGenImageGallery.saveToPictures(
                        context,
                        requireNotNull(imageBytes),
                        mimeType
                    )
                }
                info = "Saved to Pictures/Sruvik. Open Gallery or Photos to view it."
                android.util.Log.i("SruGen", "Image exported to Gallery: $savedUri")
            } catch (failure: Exception) {
                android.util.Log.e("SruGen", "Gallery export failed for $attachmentId.", failure)
                error = failure.message ?: "Unable to save this image to your device."
                info = ""
            } finally {
                imageBytes?.fill(0)
            }
        }
    }

    val legacyGalleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingGallerySave
        pendingGallerySave = null
        if (pending != null) {
            if (granted) {
                saveAttachmentToDeviceNow(pending.first, pending.second)
            } else {
                error = "Storage permission was denied. Allow it to save a copy into Pictures/Sruvik."
            }
        }
    }

    fun saveAttachmentToDevice(attachmentId: String, mimeType: String) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingGallerySave = attachmentId to mimeType
            legacyGalleryPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveAttachmentToDeviceNow(attachmentId, mimeType)
        }
    }

    fun saveAttachmentToSrukrypt(attachmentId: String, mimeType: String) {
        SruGenVaultImportQueue.enqueue(
            SruGenVaultImportRequest(attachmentId, mimeType, "SruGen image")
        )
        error = ""
        info = "Ready to import. Unlock Srukrypt to save this image into your private vault."
        onOpenVault()
    }

    fun sendMessage() {
        val chat = chats.firstOrNull { it.id == activeId }
            ?: draftChat.takeIf { it.id == activeId }
            ?: return
        val selectedMode = mode
        val rawText = prompt.trim()
        val text = if (selectedMode == SruGenMode.REMOVE_BACKGROUND && rawText.isBlank()) {
            "Remove the background and keep the visible subject."
        } else rawText
        val attachment = selectedImage
        val requestMode = if (
            selectedMode == SruGenMode.TEXT_TO_IMAGE &&
            attachment != null &&
            isSruGenClothingEditInstruction(text)
        ) SruGenMode.PROMPT_EDIT else selectedMode
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
                    "I can create images locally, automatically edit clothing in a compatible model, and remove photo backgrounds. Object-specific removal is not enabled yet."
                else -> null
            }
        } else null
        if (quickReply != null) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(role = "assistant", text = quickReply)
            ))
            return
        }

        if (text.isBlank() && attachment != null && requestMode == SruGenMode.TEXT_TO_IMAGE) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(
                    role = "assistant",
                    text = "Image attached and encrypted in this conversation. Add an instruction and choose Edit, Remove BG or Object to continue. The corresponding editing model is not installed in this version yet."
                )
            ))
            return
        }

        if (attachment != null && requestMode == SruGenMode.TEXT_TO_IMAGE) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(
                    role = "assistant",
                    text = "Your reference image is saved in this encrypted chat, but ${activeModel?.name ?: "the selected model"} currently supports text-to-image only. I did not use the attachment for this generation."
                )
            ))
            return
        }

        if (requestMode != SruGenMode.TEXT_TO_IMAGE && attachment == null) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(
                    role = "assistant",
                    text = "Attach an image first, then describe the change you want."
                )
            ))
            return
        }

        if (
            requestMode == SruGenMode.PROMPT_EDIT &&
            SruGenColorEditParser.requiresMultiGarmentEdit(text)
        ) {
            persist(updatedChat.copy(messages = updatedChat.messages + SruGenMessage(
                role = "assistant",
                text = "I didn't generate this edit because it asks for separate garment layers (${SruGenColorEditParser.garmentTargets(text).joinToString(" + ")}). This build's on-device segmenter only returns one broad clothes mask, and its diffusion engine does not accept an inpainting mask. It cannot independently edit an outer blazer and an inner shirt reliably. A true layered edit needs garment-specific masks plus a mask-conditioned inpainting model. For now, try one visible garment at a time; your original photo is unchanged."
            )))
            return
        }

        if (requestMode == SruGenMode.PROMPT_EDIT && text.isBlank()) {
            persist(updatedChat.copy(messages = updatedChat.messages + SruGenMessage(
                role = "assistant",
                text = "Describe the clothing change you want, for example: change the shirt to black. Your original image remains attached in this encrypted chat."
            )))
            return
        }
        if (requestMode == SruGenMode.REMOVE_OBJECT) {
            persist(updatedChat.copy(messages = updatedChat.messages + SruGenMessage(
                role = "assistant",
                text = "Automatic object removal is not enabled yet. This build has no reliable object-specific segmentation or dedicated inpainting model, so it will not guess at an object or modify your photo."
            )))
            return
        }
        var generationModelId = activeModelId
        if (requestMode == SruGenMode.PROMPT_EDIT) {
            val editModel = SruGenEngineFactory.installedImageEditingModel(context, activeModelId)
            if (editModel == null) {
                persist(updatedChat.copy(messages = updatedChat.messages + SruGenMessage(
                    role = "assistant",
                    text = "Your photo is safely saved, but ${activeModel?.name ?: "the active model"} does not have the VAE encoder required for image editing. Install majicMIX Realistic v6 from Models (about 1.24 GB). After installation, return here, reattach the photo and choose Edit clothes. Nothing was generated from text alone."
                )))
                return
            }
            generationModelId = editModel.id
        }
        if (requestMode == SruGenMode.TEXT_TO_IMAGE && !modelReady) {
            persist(updatedChat.copy(messages = updatedChat.messages + SruGenMessage(
                role = "assistant",
                text = "Your prompt and image remain saved in this encrypted chat. The selected model is not installed yet. Open Models to install a compatible model before generating."
            )))
            return
        }

        val remembered = if (memoryEnabled) SruGenChatStore.memoryContext(chats, chat.id) else ""
        val generationPrompt = if (remembered.isBlank()) text else
            "Use the recent creative context only for style continuity. Follow the current prompt first.\nRecent creative context:\n$remembered\n\nCurrent prompt: $text"

        if (pendingGeneration != null || generationState.status == SruGenGenerationStatus.RUNNING) {
            persist(updatedChat.copy(
                messages = updatedChat.messages + SruGenMessage(
                    role = "assistant",
                    text = "Another image is already being generated. Please wait for it to finish."
                )
            ))
            return
        }
        if (generationModelId != activeModelId) {
            val oldEngine = SruGenEngineRegistry.engine
            activeModelId = generationModelId
            SruGenActiveModelStore.setActiveModel(context, generationModelId)
            modelReady = false
            scope.launch {
                try {
                    oldEngine?.requestStop()
                    withContext(Dispatchers.IO) {
                        SruGenEngineRegistry.closeAndDetach(oldEngine)
                    }
                    activateInstalledModel()
                    startGenerationWithNotificationPermission(
                        updatedChat,
                        generationPrompt,
                        generationModelId,
                        requestMode,
                        attachment
                    )
                } catch (failure: Exception) {
                    modelReady = false
                    error = failure.message ?: "Unable to switch to the image-editing model."
                    info = ""
                }
            }
        } else {
            startGenerationWithNotificationPermission(
                updatedChat,
                generationPrompt,
                generationModelId,
                requestMode,
                attachment
            )
        }
    }
    val studioInk = SruvikColors.Background
    val studioPanel = SruvikColors.Surface
    val studioRaised = SruvikColors.SurfaceSoft
    val studioAccent = SruvikColors.Primary
    val studioText = SruvikColors.TextPrimary
    val studioSecondary = SruvikColors.TextSecondary

    Box(
        Modifier.fillMaxSize().background(studioInk),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            Modifier.widthIn(max = 920.dp).fillMaxWidth().fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.ArrowBack, "Back to Sruvik", tint = studioText)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("SruGen", color = studioText, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                        Text(
                            activeModel?.name ?: "Choose a local model",
                            color = if (modelReady) studioSecondary else studioAccent,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = onOpenModels, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Rounded.Memory,
                            "Active model: ${activeModel?.name ?: "Choose a model"}. Open model library",
                            tint = studioAccent
                        )
                    }
                    IconButton(onClick = { historyOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.History, "Conversation history", tint = studioText)
                    }
                    if (maxWidth >= 390.dp) {
                        IconButton(onClick = { monitorExpanded = !monitorExpanded }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Tune, "Device performance details", tint = studioSecondary)
                        }
                        IconButton(onClick = { newChat() }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Add, "New conversation", tint = studioAccent)
                        }
                    } else {
                        Box {
                            IconButton(onClick = { headerMenuExpanded = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.MoreVert, "More studio actions", tint = studioSecondary)
                            }
                            DropdownMenu(expanded = headerMenuExpanded, onDismissRequest = { headerMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Device performance") },
                                    leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                                    onClick = {
                                        headerMenuExpanded = false
                                        monitorExpanded = !monitorExpanded
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New conversation") },
                                    leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                    onClick = {
                                        headerMenuExpanded = false
                                        newChat()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = monitorExpanded) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .background(studioPanel, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("CPU  ${String.format("%.1f", resourceStats.cpu)}%", color = studioSecondary, style = MaterialTheme.typography.labelMedium)
                    Text("RAM  ${resourceStats.pss} MB", color = studioSecondary, style = MaterialTheme.typography.labelMedium)
                    Text("FREE  ${resourceStats.available} MB", color = studioSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }

            AnimatedVisibility(visible = !modelReady && !installing && downloadId < 0L) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .background(studioPanel, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Info, null, tint = studioAccent, modifier = Modifier.size(18.dp))
                    Text(
                        "No compatible model is ready. Choose or install one to create images.",
                        Modifier.weight(1f),
                        color = studioSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onOpenModels, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("Models")
                    }
                }
            }

            AnimatedVisibility(visible = downloadId >= 0L || installing) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .background(studioPanel, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (installing) installMessage else "Downloading model · $downloadPercent%",
                            Modifier.weight(1f),
                            color = studioText,
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (downloadId >= 0L) {
                            TextButton(
                                onClick = {
                                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
                                    SruGenModelInstaller.clearDownloadId(context)
                                    downloadId = -1L
                                    info = "Model download cancelled."
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("Cancel") }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { animatedDownloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = studioAccent,
                        trackColor = studioRaised
                    )
                }
            }

            activeChat?.let { chat ->
                LazyColumn(
                    state = messageListState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    if (chat.messages.isEmpty()) {
                        item(key = "srugen-empty") {
                            Column(
                                Modifier.fillParentMaxSize().padding(horizontal = 12.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text("01 / CREATE", color = studioAccent, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.6.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("Make an image.", color = studioText, style = MaterialTheme.typography.displaySmall)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Describe a scene, or attach a photo to edit. Generation runs locally; this conversation is stored encrypted on this device.",
                                    color = studioSecondary,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (!modelReady) {
                                    Spacer(Modifier.height(18.dp))
                                    OutlinedButton(onClick = onOpenModels) {
                                        Icon(Icons.Rounded.Memory, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Choose a model")
                                    }
                                }
                            }
                        }
                    }
                    items(chat.messages, key = { it.id }) { message ->
                        SruGenMessageBubble(
                            message = message,
                            image = message.attachmentId?.let { thumbnails[it] },
                            onSaveToDevice = ::saveAttachmentToDevice,
                            onSaveToVault = ::saveAttachmentToSrukrypt,
                            onViewImage = ::openAttachmentImage
                        )
                    }
                    if (generating) {
                        item(key = "srugen-generation-progress") {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = studioAccent)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Creating your image · $progress%", Modifier.weight(1f), color = studioText, style = MaterialTheme.typography.titleSmall)
                                    TextButton(onClick = { SruGenGenerationService.stop(context) }) {
                                        Icon(Icons.Rounded.Stop, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Stop")
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { animatedGenerationProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = studioAccent,
                                    trackColor = studioRaised
                                )
                                Text(progressLabel, color = studioSecondary, style = MaterialTheme.typography.bodySmall)
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

            AnimatedVisibility(visible = error.isNotBlank() || info.isNotBlank()) {
                val isError = error.isNotBlank()
                Text(
                    if (isError) error else info,
                    Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 7.dp),
                    color = if (isError) SruvikColors.Danger else SruvikColors.Mint,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Column(
                Modifier.fillMaxWidth().background(studioInk).padding(top = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider(color = SruvikColors.Border.copy(alpha = .75f))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StudioModeControl("Create", mode == SruGenMode.TEXT_TO_IMAGE) { mode = SruGenMode.TEXT_TO_IMAGE }
                    StudioModeControl("Edit clothes", mode == SruGenMode.PROMPT_EDIT) { mode = SruGenMode.PROMPT_EDIT }
                    StudioModeControl("Remove background", mode == SruGenMode.REMOVE_BACKGROUND) { mode = SruGenMode.REMOVE_BACKGROUND }
                }

                selectedImage?.let { id ->
                    Row(
                        Modifier.fillMaxWidth().background(studioPanel, RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        thumbnails[id]?.let { bitmap ->
                            Image(bitmap.asImageBitmap(), "Attached reference image", Modifier.size(44.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
                        }
                        Text("Reference image attached", Modifier.weight(1f), color = studioText, style = MaterialTheme.typography.bodySmall)
                        IconButton(
                            onClick = {
                                selectedImage = null
                                if (mode == SruGenMode.PROMPT_EDIT) mode = SruGenMode.TEXT_TO_IMAGE
                                SruGenChatStore.deleteAttachment(context, id)
                                thumbnails[id]?.takeIf { !it.isRecycled }?.recycle()
                                thumbnails = thumbnails - id
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Rounded.Close, "Remove attached image", tint = studioSecondary)
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().background(studioPanel, RoundedCornerShape(10.dp))
                        .padding(start = 13.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BasicTextField(
                        value = prompt,
                        onValueChange = { prompt = it.take(1800) },
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp, max = 116.dp)
                            .semantics { contentDescription = "Image prompt" }
                            .padding(vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = studioText),
                        cursorBrush = SolidColor(studioAccent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!generating && (prompt.isNotBlank() || selectedImage != null)) sendMessage() }),
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth()) {
                                if (prompt.isBlank()) {
                                    Text(
                                        when (mode) {
                                            SruGenMode.TEXT_TO_IMAGE -> "Describe an image…"
                                            SruGenMode.PROMPT_EDIT -> "Describe the clothing change…"
                                            SruGenMode.REMOVE_BACKGROUND -> "Describe the subject to keep…"
                                            SruGenMode.REMOVE_OBJECT -> "Describe what to remove…"
                                        },
                                        color = SruvikColors.TextMuted,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    Box {
                        IconButton(
                            onClick = { attachMenuExpanded = true },
                            enabled = !importingImage && !generating,
                            modifier = Modifier.size(48.dp)
                        ) {
                            if (importingImage) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = studioAccent)
                            else Icon(Icons.Rounded.AddPhotoAlternate, "Attach image", tint = studioSecondary)
                        }
                        DropdownMenu(expanded = attachMenuExpanded, onDismissRequest = { attachMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Photo library") }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) }, onClick = { attachMenuExpanded = false; launchImagePicker() })
                            DropdownMenuItem(text = { Text("Browse files") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) }, onClick = { attachMenuExpanded = false; launchImageFilePicker() })
                        }
                    }
                    FilledIconButton(
                        onClick = { sendMessage() },
                        enabled = !generating && (prompt.isNotBlank() || selectedImage != null),
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = studioAccent,
                            contentColor = SruvikColors.OnPrimary,
                            disabledContainerColor = SruvikColors.SurfaceElevated,
                            disabledContentColor = SruvikColors.TextMuted
                        )
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, "Send prompt and create image")
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
            containerColor = SruvikColors.Surface,
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
                                .background(if (item.id == activeId) SruvikColors.PrimaryContainer else Color.Transparent)
                                .clickable {
                                    followGenerationChat = false
                                    activeId = item.id
                                    selectedImage = null
                                    mode = SruGenMode.TEXT_TO_IMAGE
                                    historyOpen = false
                                }
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
            containerColor = SruvikColors.Surface
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
            containerColor = SruvikColors.Surface
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
            containerColor = SruvikColors.Surface
        )
    }

    viewingImage?.let { (bitmap, title) ->
        SruvikImageViewer(bitmap = bitmap, title = title) {
            if (!bitmap.isRecycled) bitmap.recycle()
            viewingImage = null
        }
    }
}



internal fun isSruGenClothingEditInstruction(prompt: String): Boolean {
    val normalized = prompt.lowercase()
    val hasEditVerb = Regex("\\b(change|edit|recolor|recolour|turn|make|replace|switch)\\b")
        .containsMatchIn(normalized)
    val hasClothingTarget = Regex("\\b(clothes|clothing|outfit|shirt|t-shirt|tee|jacket|coat|hoodie|sweater|dress|trousers|pants|jeans)\\b")
        .containsMatchIn(normalized)
    return hasEditVerb && hasClothingTarget
}

private const val MAX_SRUGEN_IMAGE_BYTES = 24 * 1024 * 1024
private const val MAX_SRUGEN_PREVIEW_PIXELS = 3_000_000L
private const val MAX_SRUGEN_PREVIEW_DIMENSION = 2048

/**
 * Providers do not all expose content URIs through the same resolver method.
 * Try the standard stream, typed asset, asset descriptor and file descriptor
 * paths before reporting a read failure.
 */
private fun openSruGenImageStream(context: Context, uri: Uri): InputStream {
    val resolver = context.contentResolver
    var lastError: Exception? = null

    fun attempt(open: () -> InputStream?): InputStream? = try {
        open()
    } catch (failure: Exception) {
        lastError = failure
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

    val message = if (lastError is SecurityException) {
        "Android denied access to the selected image. Re-select it or use Browse files."
    } else {
        "The selected provider did not return a readable image. Try Browse files or save a copy on this device."
    }
    throw IOException(message, lastError)
}

private fun readSruGenImage(context: Context, uri: Uri): Pair<ByteArray, String> {
    val output = ByteArrayOutputStream()
    openSruGenImageStream(context, uri).use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_SRUGEN_IMAGE_BYTES) {
                throw IOException("Choose an image smaller than 24 MB.")
            }
            output.write(buffer, 0, count)
        }
    }
    val bytes = output.toByteArray()
    if (bytes.isEmpty()) throw IOException("The selected image is empty.")
    val mimeType = context.contentResolver.getType(uri)
        ?.takeIf { it.startsWith("image/", ignoreCase = true) }
        ?: "image/jpeg"
    return bytes to mimeType
}

/** Uses ImageDecoder for modern formats and a bounded BitmapFactory fallback. */
private fun decodeSruGenImagePreview(context: Context, uri: Uri, bytes: ByteArray): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val dimensionScale = min(
                    MAX_SRUGEN_PREVIEW_DIMENSION.toFloat() / width,
                    MAX_SRUGEN_PREVIEW_DIMENSION.toFloat() / height
                )
                val pixelScale = sqrt(
                    MAX_SRUGEN_PREVIEW_PIXELS.toDouble() /
                        (width.toLong() * height.toLong()).toDouble()
                ).toFloat()
                val scale = min(1f, min(dimensionScale, pixelScale))
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1)
                )
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            }
        } catch (failure: Exception) {
            android.util.Log.w("SruGen", "ImageDecoder could not preview the selected URI; trying BitmapFactory.", failure)
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IOException("This image format cannot be previewed on this Android version.")
    }
    var sample = 1
    while (
        bounds.outWidth / sample > MAX_SRUGEN_PREVIEW_DIMENSION ||
        bounds.outHeight / sample > MAX_SRUGEN_PREVIEW_DIMENSION ||
        (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) >
            MAX_SRUGEN_PREVIEW_PIXELS
    ) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    ) ?: throw IOException("This image format cannot be previewed. Choose JPG, PNG or WebP.")
}

@Composable
private fun SruGenCreativeSpark() {
    val motion = rememberInfiniteTransition(label = "srugen-creative-spark")
    val rotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "srugen-spark-rotation"
    )
    val breathing by motion.animateFloat(
        initialValue = .82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "srugen-spark-breathing"
    )
    androidx.compose.foundation.Canvas(Modifier.size(116.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val unit = size.minDimension
        val coral = SruvikColors.Primary
        drawCircle(
            color = coral.copy(alpha = .055f),
            radius = unit * .49f * breathing
        )
        drawCircle(
            color = Color.White.copy(alpha = .11f),
            radius = unit * .43f,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = coral.copy(alpha = .28f),
            radius = unit * .31f,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )
        for (index in 0 until 12) {
            val radians = Math.toRadians(index * 30.0 + rotation.toDouble())
            val inner = unit * .12f
            val outer = unit * if (index % 3 == 0) .43f else .34f
            val startPoint = Offset(
                center.x + cos(radians).toFloat() * inner,
                center.y + sin(radians).toFloat() * inner
            )
            val endPoint = Offset(
                center.x + cos(radians).toFloat() * outer,
                center.y + sin(radians).toFloat() * outer
            )
            drawLine(
                color = coral.copy(alpha = if (index % 3 == 0) 1f else .78f),
                start = startPoint,
                end = endPoint,
                strokeWidth = unit * .048f * breathing,
                cap = StrokeCap.Round
            )
        }
        val orbitAngle = Math.toRadians(-rotation.toDouble() * .63)
        val orbit = Offset(
            center.x + cos(orbitAngle).toFloat() * unit * .39f,
            center.y + sin(orbitAngle).toFloat() * unit * .39f
        )
        drawCircle(SruvikColors.SecondaryAccent, radius = unit * .035f, center = orbit)
        drawCircle(coral, radius = unit * .062f * breathing, center = center)
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
private fun SruGenMessageBubble(
    message: SruGenMessage,
    image: Bitmap?,
    onSaveToDevice: (String, String) -> Unit,
    onSaveToVault: (String, String) -> Unit,
    onViewImage: (String) -> Unit
) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier.widthIn(max = if (isUser) 560.dp else 680.dp)
                .then(
                    if (isUser) Modifier.clip(RoundedCornerShape(16.dp))
                        .background(SruvikColors.SurfaceElevated)
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                    else Modifier.padding(vertical = 2.dp)
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (isUser) "YOU" else "SRUGEN · LOCAL",
                color = if (isUser) SruvikColors.TextSecondary else SruvikColors.Primary,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.1.sp
            )
            if (message.text.isNotBlank()) {
                Text(message.text, color = SruvikColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            image?.let { bitmap ->
                Image(
                    bitmap.asImageBitmap(),
                    "Open image in full-screen viewer",
                    Modifier.fillMaxWidth().heightIn(max = 440.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = message.attachmentId != null) {
                            message.attachmentId?.let(onViewImage)
                        },
                    contentScale = ContentScale.Fit
                )
                if (message.attachmentId != null) {
                    Text("Tap image to view and zoom", color = SruvikColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (!isUser && message.attachmentId != null) {
                Text("Encrypted in this conversation", color = SruvikColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onSaveToDevice(message.attachmentId, message.attachmentMime ?: "image/png") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Rounded.SaveAlt, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save to device")
                    }
                    TextButton(
                        onClick = { onSaveToVault(message.attachmentId, message.attachmentMime ?: "image/png") },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Rounded.Security, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import to Srukrypt")
                    }
                }
            }
            message.modelName?.let {
                Text(it, color = SruvikColors.TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
@Composable
private fun StudioModeControl(label: String, selected: Boolean, onClick: () -> Unit) {
    val isSelected = selected
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(180),
        label = "studio-mode-selection"
    )
    Row(
        Modifier.heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SruvikColors.Lime.copy(alpha = .04f + .12f * selectionProgress))
            .border(
                1.dp,
                SruvikColors.Lime.copy(alpha = .08f + .22f * selectionProgress),
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$label mode"
                stateDescription = if (isSelected) "Selected" else "Not selected"
            }
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (selected) SruvikColors.Lime else SruvikColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium
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
