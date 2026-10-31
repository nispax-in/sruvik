package `in`.nispax.sruvik.ui

import `in`.nispax.sruvik.ui.theme.SruvikColors

import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `in`.nispax.sruvik.security.SrukryptEntryPreview
import `in`.nispax.sruvik.security.SrukryptUnlockChallenge
import `in`.nispax.sruvik.security.SrukryptVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Arrays

@Composable
fun SrukryptPage(autoLockMinutes: Int) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var sessionKey by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportKey by remember { mutableStateOf<ByteArray?>(null) }
    var pendingExportKey by remember { mutableStateOf<ByteArray?>(null) }
    var entries by remember { mutableStateOf<List<SrukryptEntryPreview>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var infoText by remember { mutableStateOf("") }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var confirmDelete by remember { mutableStateOf<SrukryptEntryPreview?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }

    fun lockVault() {
        sessionKey?.let { Arrays.fill(it, 0) }
        sessionKey = null
        entries.forEach { if (!it.thumbnail.isRecycled) it.thumbnail.recycle() }
        entries = emptyList()
        confirmDelete = null
        errorText = ""
        infoText = "Vault locked. Your files remain encrypted."
    }

    DisposableEffect(activity, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) lockVault()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lockVault()
            pendingImportKey?.let { Arrays.fill(it, 0) }
            pendingExportKey?.let { Arrays.fill(it, 0) }
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(sessionKey, lastInteraction, autoLockMinutes) {
        if (sessionKey != null && autoLockMinutes > 0) {
            val timeout = autoLockMinutes * 60_000L
            delay(timeout)
            if (SystemClock.elapsedRealtime() - lastInteraction >= timeout && sessionKey != null) {
                lockVault()
            }
        }
    }

    suspend fun refreshEntries(key: ByteArray) {
        val previews = withContext(Dispatchers.IO) { SrukryptVault.loadPreviews(context, key) }
        entries.forEach { if (!it.thumbnail.isRecycled) it.thumbnail.recycle() }
        entries = previews
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val activeKey = pendingImportKey
        pendingImportKey = null
        if (uri == null) {
            activeKey?.let { Arrays.fill(it, 0) }
        } else if (activeKey == null) {
            errorText = "Unlock Srukrypt before importing."
        } else {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= SrukryptVault.MAX_IMAGE_BYTES) {
                                    "Choose an image smaller than 24 MB."
                                }
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        } ?: error("Sruvik couldn't read that image.")
                        try {
                            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                            SrukryptVault.storeImage(context, activeKey, "Private image", mime, bytes)
                        } finally {
                            Arrays.fill(bytes, 0)
                        }
                    }
                    refreshEntries(activeKey)
                    sessionKey = activeKey.copyOf()
                    lastInteraction = SystemClock.elapsedRealtime()
                    infoText = "Encrypted image added to your private vault."
                } catch (error: Exception) {
                    errorText = error.message ?: "Unable to encrypt this image."
                } finally {
                    Arrays.fill(activeKey, 0)
                    busy = false
                }
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val id = exportId
        val activeKey = pendingExportKey
        exportId = null
        pendingExportKey = null
        if (uri != null && id != null && activeKey != null) {
            busy = true
            scope.launch {
                var image: ByteArray? = null
                try {
                    image = withContext(Dispatchers.IO) { SrukryptVault.readImage(context, activeKey, id).second }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(image) }
                            ?: error("Unable to write the exported file.")
                    }
                    infoText = "Export complete. This external copy is no longer protected by Srukrypt."
                } catch (error: Exception) {
                    errorText = error.message ?: "Export failed."
                } finally {
                    image?.let { Arrays.fill(it, 0) }
                    Arrays.fill(activeKey, 0)
                    busy = false
                }
            }
        } else {
            activeKey?.let { Arrays.fill(it, 0) }
        }
    }

    fun requestUnlock() {
        if (activity == null) {
            errorText = "Biometric unlock needs the Sruvik activity."
            return
        }
        val availability = BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            errorText = when (availability) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "Enroll a strong fingerprint or face unlock in Android Settings to use Srukrypt."
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                    "This device has no compatible strong biometric hardware."
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                    "Biometric hardware is temporarily unavailable."
                else -> "A strong biometric unlock is not available on this device."
            }
            return
        }
        if (busy) return
        busy = true
        errorText = ""
        scope.launch {
            var challenge: SrukryptUnlockChallenge? = null
            try {
                val authChallenge = withContext(Dispatchers.IO) { SrukryptVault.prepareUnlock(context) }
                challenge = authChallenge
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Srukrypt")
                    .setSubtitle("Authenticate to access your encrypted private files.")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Cancel")
                    .build()
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            val authenticatedCipher = result.cryptoObject?.cipher ?: authChallenge.cipher
                            scope.launch {
                                try {
                                    val newKey = withContext(Dispatchers.IO) {
                                        SrukryptVault.finishUnlock(context, authChallenge, authenticatedCipher)
                                    }
                                    SrukryptVault.discardChallenge(authChallenge)
                                    val previews = withContext(Dispatchers.IO) {
                                        SrukryptVault.loadPreviews(context, newKey)
                                    }
                                    sessionKey = newKey
                                    entries = previews
                                    lastInteraction = SystemClock.elapsedRealtime()
                                    infoText = if (previews.isEmpty()) {
                                        "Your private vault is ready. Add an image to encrypt it."
                                    } else {
                                        "Vault unlocked. " + previews.size + " encrypted item(s) available."
                                    }
                                    errorText = ""
                                } catch (error: Exception) {
                                    SrukryptVault.discardChallenge(authChallenge)
                                    errorText = error.message ?: "Unable to unlock this vault."
                                } finally {
                                    busy = false
                                }
                            }
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            SrukryptVault.discardChallenge(authChallenge)
                            busy = false
                            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                            ) errorText = errString.toString()
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            errorText = "That biometric wasn't recognised. Try again."
                        }
                    }
                )
                prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(challenge.cipher))
            } catch (error: Exception) {
                challenge?.let { SrukryptVault.discardChallenge(it) }
                busy = false
                errorText = error.message ?: "Unable to prepare the secure vault."
            }
        }
    }

    fun beginImport() {
        val key = sessionKey?.copyOf()
        if (key == null) {
            errorText = "Unlock Srukrypt before importing."
        } else {
            pendingImportKey?.let { Arrays.fill(it, 0) }
            pendingImportKey = key
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    fun export(entry: SrukryptEntryPreview) {
        val key = sessionKey?.copyOf()
        if (key == null) {
            errorText = "Unlock Srukrypt before exporting."
        } else {
            pendingExportKey?.let { Arrays.fill(it, 0) }
            pendingExportKey = key
            exportId = entry.id
            val extension = when (entry.mimeType.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            exporter.launch("Srukrypt-export.$extension")
        }
    }

    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastInteraction = SystemClock.elapsedRealtime()
                    }
                }
            }
            .padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 106.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("PRIVATE BY DESIGN", style = MaterialTheme.typography.labelLarge,
                color = SruvikColors.LimeMuted, letterSpacing = 1.8.sp)
            Text("Srukrypt", style = MaterialTheme.typography.displayLarge, color = SruvikColors.TextPrimary)
            Text("Your images. Your keys. Your device.",
                style = MaterialTheme.typography.bodyLarge, color = SruvikColors.TextSecondary)
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                .background(Color(0xFF282544).copy(alpha = .7f))
                .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(17.dp))
                .padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.Security, null, tint = SruvikColors.Lime, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("AES-256-GCM · Android Keystore", style = MaterialTheme.typography.labelMedium,
                    color = SruvikColors.TextPrimary)
                Text("Encrypted image contents and metadata · no cloud sync",
                    style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
            }
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF5BC9A0).copy(alpha = .15f)) {
                Text("OFFLINE", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = SruvikColors.Lime, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (sessionKey == null) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF352D62), Color(0xFF1D2746), Color(0xFF17182C))))
                    .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(28.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Box(Modifier.size(76.dp).clip(CircleShape)
                    .background(Color(0xFF8876D9).copy(alpha = .18f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, null, tint = SruvikColors.Lime, modifier = Modifier.size(36.dp))
                }
                Text(
                    if (SrukryptVault.hasVault(context)) "Your vault is locked" else "Create your private vault",
                    style = MaterialTheme.typography.headlineSmall, color = SruvikColors.TextPrimary
                )
                Text(
                    "A strong biometric is required to unlock the Keystore key. Your private data key is only held in memory while the vault is open.",
                    style = MaterialTheme.typography.bodyMedium, color = SruvikColors.TextSecondary
                )
                Button(
                    onClick = { requestUnlock() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7865C8))
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Preparing secure session..." else if (SrukryptVault.hasVault(context)) "Unlock vault" else "Set up Srukrypt")
                }
                Text("Enrolling a new biometric may invalidate the Keystore key. Device reset or uninstall also destroys access. Export important files before changing device security or moving to another phone.",
                    style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextMuted)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("VAULT UNLOCKED", style = MaterialTheme.typography.labelLarge,
                        color = SruvikColors.Lime, letterSpacing = 1.2.sp)
                    Text(entries.size.toString() + " private item(s)", style = MaterialTheme.typography.bodySmall,
                        color = SruvikColors.TextSecondary)
                }
                OutlinedButton(onClick = { lockVault() }, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lock now")
                }
                Spacer(Modifier.width(7.dp))
                Button(
                    onClick = { beginImport() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7865C8))
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Text("Add")
                }
            }

            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))
                        .background(Color(0x991B1D32))
                        .border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(24.dp))
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.Inventory2, null, tint = SruvikColors.LimeMuted, modifier = Modifier.size(37.dp))
                    Text("A clean slate", style = MaterialTheme.typography.titleLarge,
                        color = SruvikColors.TextPrimary)
                    Text("Add an image. It is encrypted before it is written to the vault.",
                        style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Column(
                            Modifier.clip(RoundedCornerShape(19.dp))
                                .background(Color(0xAA20213A))
                                .border(1.dp, Color.White.copy(alpha = .10f), RoundedCornerShape(19.dp))
                                .padding(9.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Image(
                                entry.thumbnail.asImageBitmap(),
                                contentDescription = entry.title,
                                modifier = Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(13.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                            Text(entry.title, color = SruvikColors.TextPrimary,
                                style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(String.format("%.1f MB", entry.byteCount / (1024f * 1024f)),
                                color = SruvikColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                TextButton(onClick = { export(entry) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(16.dp))
                                    Text("Export")
                                }
                                IconButton(onClick = { confirmDelete = entry }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.DeleteOutline, "Delete " + entry.title,
                                        tint = Color(0xFFFF9B9B), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (errorText.isNotBlank()) Text(errorText, color = Color(0xFFFFA6A6), style = MaterialTheme.typography.bodySmall)
        if (infoText.isNotBlank()) Text(infoText, color = SruvikColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }

    if (confirmDelete != null) {
        val target = confirmDelete!!
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this vault item?", color = SruvikColors.TextPrimary) },
            text = { Text("This removes the encrypted entry from Srukrypt. Flash storage may retain old physical blocks; this is not a certified secure-erase operation.",
                color = SruvikColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch(Dispatchers.IO) {
                        val success = SrukryptVault.deleteEntry(context, target.id)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                if (!target.thumbnail.isRecycled) target.thumbnail.recycle()
                                entries = entries.filterNot { it.id == target.id }
                                infoText = "Vault item removed."
                            } else errorText = "Unable to delete this item."
                        }
                    }
                }) { Text("Delete", color = Color(0xFFFF9B9B)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            containerColor = Color(0xFF19192D),
            shape = RoundedCornerShape(23.dp)
        )
    }
}
