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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
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
    var passphraseDialog by remember { mutableStateOf(false) }
    var passphraseMode by remember { mutableStateOf("unlock") }
    var passphraseText by remember { mutableStateOf("") }
    var passphraseConfirm by remember { mutableStateOf("") }

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
            if (event == Lifecycle.Event.ON_STOP) {
                lockVault()
                pendingImportKey?.let { Arrays.fill(it, 0) }
                pendingImportKey = null
                pendingExportKey?.let { Arrays.fill(it, 0) }
                pendingExportKey = null
                exportId = null
            }
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
        } else if (activeKey == null || sessionKey == null) {
            activeKey?.let { Arrays.fill(it, 0) }
            errorText = "The vault locked while the image picker was open. Unlock Srukrypt and select the image again."
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
                    if (sessionKey == null) {
                        errorText = "The vault was locked before import finished. Unlock to continue."
                    } else {
                        refreshEntries(activeKey)
                        lastInteraction = SystemClock.elapsedRealtime()
                        infoText = "Encrypted image added to your private vault."
                    }
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
            errorText = "The secure unlock screen is unavailable in this activity."
            return
        }
        if (busy) return
        busy = true
        errorText = ""
        scope.launch {
            var challenge: SrukryptUnlockChallenge? = null
            try {
                val authChallenge = withContext(Dispatchers.IO) {
                    SrukryptVault.prepareUnlock(context)
                }
                challenge = authChallenge
                val allowed = authChallenge.allowedAuthenticators
                val availability = BiometricManager.from(activity).canAuthenticate(allowed)
                if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
                    SrukryptVault.discardChallenge(authChallenge)
                    val hasPassphrase = SrukryptVault.hasPassphraseFallback(context)
                    errorText = when (availability) {
                        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                            if (hasPassphrase) "No supported screen lock is enrolled. Use your Srukrypt passphrase instead."
                            else "Set up a strong fingerprint/face or a device PIN, pattern or password. On older Android versions, use the Srukrypt passphrase option."
                        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                            if (hasPassphrase) "No compatible biometric hardware. Use your Srukrypt passphrase."
                            else "This phone has no compatible biometric hardware. Create a Srukrypt passphrase vault instead."
                        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                            "Android authentication hardware is temporarily unavailable. Try again or use your passphrase."
                        else ->
                            "Android cannot use the configured screen lock for this vault (status $availability). Use your passphrase fallback if configured."
                    }
                    busy = false
                    return@launch
                }

                val builder = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Srukrypt")
                    .setSubtitle("Use your fingerprint, face or device screen lock.")
                    .setAllowedAuthenticators(allowed)
                if ((allowed and BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 0) {
                    builder.setNegativeButtonText("Cancel")
                }
                val promptInfo = builder.build()
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            val authenticatedCipher = result.cryptoObject?.cipher
                            if (authenticatedCipher == null) {
                                SrukryptVault.discardChallenge(authChallenge)
                                busy = false
                                errorText = "Android completed authentication but did not return the Keystore cipher. No vault data was opened. Please retry or use your passphrase."
                                return
                            }
                            scope.launch {
                                var newKey: ByteArray? = null
                                try {
                                    newKey = withContext(Dispatchers.IO) {
                                        SrukryptVault.finishUnlock(context, authChallenge, authenticatedCipher)
                                    }
                                    SrukryptVault.discardChallenge(authChallenge)
                                    val previews = withContext(Dispatchers.IO) {
                                        SrukryptVault.loadPreviews(context, newKey)
                                    }
                                    sessionKey?.let { Arrays.fill(it, 0) }
                                    entries.forEach { if (!it.thumbnail.isRecycled) it.thumbnail.recycle() }
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
                                    android.util.Log.e("Srukrypt", "Authenticated vault unwrap failed.", error)
                                    newKey?.let { Arrays.fill(it, 0) }
                                    SrukryptVault.discardChallenge(authChallenge)
                                    errorText = when (error) {
                                        is java.security.KeyStoreException -> "Android Keystore could not access the Srukrypt key: ${error.message}"
                                        is java.security.InvalidKeyException -> "The Srukrypt key is unavailable or invalidated. Try the passphrase fallback if you created one. Details: ${error.message}"
                                        is javax.crypto.AEADBadTagException -> "The encrypted vault key failed authentication. This may indicate a damaged key envelope."
                                        else -> error.message ?: "Unable to unlock the vault."
                                    }
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
                            errorText = "Authentication was not recognised. Try again or use your device screen lock."
                        }
                    }
                )
                prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(authChallenge.cipher))
            } catch (error: Exception) {
                android.util.Log.e("Srukrypt", "Could not prepare the Android Keystore unlock challenge.", error)
                challenge?.let { SrukryptVault.discardChallenge(it) }
                busy = false
                errorText = when (error) {
                    is java.security.KeyStoreException -> "Android Keystore error: ${error.message}"
                    is java.security.InvalidKeyException -> "The Srukrypt key is not available. If it was invalidated by Android, only a previously configured passphrase fallback can recover the data. ${error.message}"
                    else -> error.message ?: "Unable to prepare the secure vault."
                }
            }
        }
    }

    fun openPassphrase(mode: String) {
        passphraseMode = mode
        passphraseText = ""
        passphraseConfirm = ""
        passphraseDialog = true
    }

    fun submitPassphrase() {
        val operation = passphraseMode
        val typed = passphraseText.toCharArray()
        val confirm = passphraseConfirm.toCharArray()
        if (operation != "unlock" && passphraseText.length < 10) {
            errorText = "Use at least 10 characters for a Srukrypt passphrase."
            Arrays.fill(typed, '\u0000')
            Arrays.fill(confirm, '\u0000')
            return
        }
        if (operation != "unlock" && passphraseText != passphraseConfirm) {
            errorText = "The passphrases do not match."
            Arrays.fill(typed, '\u0000')
            Arrays.fill(confirm, '\u0000')
            return
        }
        val currentKey = sessionKey?.copyOf()
        passphraseDialog = false
        busy = true
        errorText = ""
        infoText = when (operation) {
            "create" -> "Creating your encrypted Srukrypt vault…"
            "add" -> "Saving your passphrase fallback…"
            else -> "Unlocking your encrypted vault…"
        }
        scope.launch {
            var unlocked: ByteArray? = null
            try {
                unlocked = withContext(Dispatchers.IO) {
                    when (operation) {
                        "create" -> SrukryptVault.createVaultWithPassphrase(context, typed)
                        "add" -> {
                            val key = currentKey ?: error("Unlock the vault first.")
                            SrukryptVault.enablePassphraseFallback(context, key, typed)
                            key.copyOf()
                        }
                        else -> SrukryptVault.unlockWithPassphrase(context, typed)
                    }
                }
                if (operation == "unlock" || operation == "create") {
                    val previews = withContext(Dispatchers.IO) {
                        SrukryptVault.loadPreviews(context, unlocked)
                    }
                    sessionKey?.let { Arrays.fill(it, 0) }
                    entries.forEach { if (!it.thumbnail.isRecycled) it.thumbnail.recycle() }
                    sessionKey = unlocked
                    entries = previews
                    infoText = if (previews.isEmpty()) "Your private vault is ready. Add an image to encrypt it."
                    else "Vault unlocked with passphrase. ${previews.size} encrypted item(s) available."
                    errorText = ""
                } else {
                    infoText = "Passphrase fallback saved. Keep it somewhere safe; it cannot be reset without an unlocked vault."
                    unlocked?.let { Arrays.fill(it, 0) }
                }
                lastInteraction = SystemClock.elapsedRealtime()
            } catch (error: Exception) {
                android.util.Log.e("Srukrypt", "Passphrase operation '$operation' failed.", error)
                unlocked?.let { Arrays.fill(it, 0) }
                infoText = ""
                errorText = error.message ?: "Passphrase operation failed."
            } finally {
                Arrays.fill(typed, '\u0000')
                Arrays.fill(confirm, '\u0000')
                currentKey?.let { Arrays.fill(it, 0) }
                passphraseText = ""
                passphraseConfirm = ""
                busy = false
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
                .background(Color(0xFF202020))
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
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFE98261).copy(alpha = .15f)) {
                Text("OFFLINE", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = SruvikColors.Lime, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (busy || errorText.isNotBlank() || infoText.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (errorText.isNotBlank()) Color(0xFF3A2020) else Color(0xFF242424),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .12f))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = SruvikColors.Lime)
                    else Icon(
                        if (errorText.isNotBlank()) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                        null, tint = if (errorText.isNotBlank()) Color(0xFFFFA6A6) else SruvikColors.Lime,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        when { busy && infoText.isNotBlank() -> infoText; errorText.isNotBlank() -> errorText; else -> infoText },
                        color = if (errorText.isNotBlank()) Color(0xFFFFB0A8) else SruvikColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        if (sessionKey == null) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF242424), Color(0xFF1B1B1B), Color(0xFF111111))))
                    .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(28.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Box(Modifier.size(76.dp).clip(CircleShape)
                    .background(Color(0xFFE98261).copy(alpha = .16f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, null, tint = SruvikColors.Lime, modifier = Modifier.size(36.dp))
                }
                Text(
                    if (SrukryptVault.hasVault(context)) "Your vault is locked" else "Create your private vault",
                    style = MaterialTheme.typography.headlineSmall, color = SruvikColors.TextPrimary
                )
                Text(
                    "Use fingerprint/face or your Android PIN, pattern or password. On unsupported devices, Srukrypt can use a private passphrase instead.",
                    style = MaterialTheme.typography.bodyMedium, color = SruvikColors.TextSecondary
                )
                Button(
                    onClick = { requestUnlock() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SruvikColors.Lime)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Preparing secure session..." else if (SrukryptVault.hasVault(context)) "Unlock vault" else "Set up Srukrypt")
                }
                if (SrukryptVault.hasPassphraseFallback(context)) {
                    OutlinedButton(
                        onClick = { openPassphrase("unlock") },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SruvikColors.Lime)
                    ) {
                        Icon(Icons.Rounded.Key, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Use passphrase instead", fontWeight = FontWeight.SemiBold)
                    }
                } else if (!SrukryptVault.hasVault(context)) {
                    OutlinedButton(
                        onClick = { openPassphrase("create") },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SruvikColors.Lime)
                    ) {
                        Icon(Icons.Rounded.Key, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Set up with a passphrase", fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("On Android 11+, the device screen lock can authorize the key. On older versions, the passphrase fallback is available. Enrolling biometrics may invalidate older biometric-only keys. Device reset or uninstall destroys access; export important files first.",
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
                OutlinedButton(
                    onClick = { openPassphrase("add") },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SruvikColors.Lime),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.Rounded.Key, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Add passphrase", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { beginImport() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SruvikColors.Lime)
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Text("Add")
                }
            }

            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1B1B1B))
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
                                .background(Color(0xFF242424))
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
            containerColor = Color(0xFF1B1B1B),
            shape = RoundedCornerShape(23.dp)
        )
    }    
    if (passphraseDialog) {
        val creating = passphraseMode == "create" || passphraseMode == "add"
        AlertDialog(
            onDismissRequest = { passphraseDialog = false },
            title = {
                Text(
                    when (passphraseMode) {
                        "create" -> "Create a passphrase vault"
                        "add" -> "Add passphrase fallback"
                        else -> "Unlock with passphrase"
                    },
                    color = SruvikColors.TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (creating)
                            "Use at least 10 characters. Your passphrase wraps the AES data key using PBKDF2-HMAC-SHA256. Sruvik cannot recover it if you forget it."
                        else
                            "Enter the passphrase you set for this vault. It is processed locally and never uploaded.",
                        color = SruvikColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = passphraseText,
                        onValueChange = { passphraseText = it.take(128) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    if (creating) {
                        OutlinedTextField(
                            value = passphraseConfirm,
                            onValueChange = { passphraseConfirm = it.take(128) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Confirm passphrase") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphraseText.isNotBlank() &&
                        (!creating || passphraseText.length >= 10 && passphraseText == passphraseConfirm),
                    onClick = { submitPassphrase() }
                ) { Text(if (passphraseMode == "unlock") "Unlock" else "Save passphrase", color = SruvikColors.Lime) }
            },
            dismissButton = {
                TextButton(onClick = { passphraseDialog = false }) {
                    Text("Cancel", color = SruvikColors.TextSecondary)
                }
            },
            containerColor = Color(0xFF19192D),
            shape = RoundedCornerShape(23.dp)
        )
    }

}
