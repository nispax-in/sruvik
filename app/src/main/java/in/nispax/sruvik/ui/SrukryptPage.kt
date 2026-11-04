package `in`.nispax.sruvik.ui

import `in`.nispax.sruvik.ui.theme.SruvikColors

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
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
import `in`.nispax.sruvik.srugen.SruGenChatStore
import `in`.nispax.sruvik.srugen.SruGenVaultImportQueue
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
    var vaultSettingsDialog by remember { mutableStateOf(false) }
    var confirmVaultReset by remember { mutableStateOf(false) }
    var viewingEntryImage by remember { mutableStateOf<Pair<Bitmap, String>?>(null) }
    var loadingEntryImage by remember { mutableStateOf(false) }
    var pendingUnlockChallenge by remember { mutableStateOf<SrukryptUnlockChallenge?>(null) }

    fun lockVault() {
        sessionKey?.let { Arrays.fill(it, 0) }
        sessionKey = null
        entries.forEach { if (!it.thumbnail.isRecycled) it.thumbnail.recycle() }
        entries = emptyList()
        viewingEntryImage?.first?.let { if (!it.isRecycled) it.recycle() }
        viewingEntryImage = null
        confirmDelete = null
        errorText = ""
        infoText = "Vault locked. Your files remain encrypted."
    }

    fun completeUnlock(authChallenge: SrukryptUnlockChallenge) {
        scope.launch {
            var newKey: ByteArray? = null
            var installed = false
            try {
                newKey = withContext(Dispatchers.IO) {
                    SrukryptVault.finishUnlock(context, authChallenge)
                }
                val loadResult = withContext(Dispatchers.IO) {
                    SrukryptVault.loadPreviewsWithReport(context, requireNotNull(newKey))
                }
                sessionKey?.let { Arrays.fill(it, 0) }
                entries.forEach { if (!it.thumbnail.isRecycled) it.thumbnail.recycle() }
                sessionKey = newKey
                entries = loadResult.entries
                installed = true
                lastInteraction = SystemClock.elapsedRealtime()
                infoText = if (loadResult.unreadableEntries > 0) {
                    "Vault unlocked. ${loadResult.entries.size} readable item(s); ${loadResult.unreadableEntries} encrypted item(s) could not be verified and were left untouched."
                } else if (loadResult.entries.isEmpty()) {
                    "Your private vault is ready. Add an image to encrypt it."
                } else {
                    "Vault unlocked. ${loadResult.entries.size} encrypted item(s) available."
                }
                errorText = ""
            } catch (failure: Exception) {
                android.util.Log.e("Srukrypt", "System-authenticated vault unwrap failed.", failure)
                if (installed) {
                    newKey?.let { Arrays.fill(it, 0) }
                    sessionKey = null
                    installed = false
                }
                errorText = when (failure) {
                    is android.security.keystore.UserNotAuthenticatedException ->
                        "Android accepted the device lock, but the Keystore authorization expired before the key opened. Tap Unlock and authenticate again."
                    is java.security.KeyStoreException ->
                        "Android Keystore could not access the Srukrypt key: ${failure.message}"
                    is android.security.keystore.KeyPermanentlyInvalidatedException ->
                        "Android invalidated the previous system-authentication key. A reset passphrase can only erase the vault; it cannot recover these items."
                    is java.security.InvalidKeyException ->
                        "Android could not use the Srukrypt key after device authentication: ${failure.message}"
                    is javax.crypto.AEADBadTagException ->
                        "The encrypted vault key failed authentication. The encrypted entries were left untouched."
                    else -> failure.message ?: "Unable to unlock the vault after Android device authentication."
                }
                infoText = ""
            } finally {
                if (!installed) newKey?.let { Arrays.fill(it, 0) }
                SrukryptVault.discardChallenge(authChallenge)
                busy = false
            }
        }
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
        val loadResult = withContext(Dispatchers.IO) {
            SrukryptVault.loadPreviewsWithReport(context, key)
        }
        entries.forEach { if (!it.thumbnail.isRecycled) it.thumbnail.recycle() }
        entries = loadResult.entries
        if (loadResult.unreadableEntries > 0) {
            errorText = "Warning: ${loadResult.unreadableEntries} encrypted item(s) could not be opened. Their original encrypted files remain on this device and have not been deleted."
        }
    }

    /**
     * SruGen can queue an image for the user's private vault. This effect
     * consumes the one-shot request only after Srukrypt has a live, unlocked
     * data key; it then creates a normal authenticated Srukrypt entry.
     */
    LaunchedEffect(sessionKey) {
        val unlockedKey = sessionKey?.copyOf() ?: return@LaunchedEffect
        val request = SruGenVaultImportQueue.consume()
        if (request == null) {
            Arrays.fill(unlockedKey, 0)
            return@LaunchedEffect
        }

        busy = true
        errorText = ""
        infoText = "Importing the encrypted SruGen image into Srukrypt…"
        scope.launch {
            var imageBytes: ByteArray? = null
            try {
                imageBytes = withContext(Dispatchers.IO) {
                    SruGenChatStore.readAttachment(context, request.attachmentId)
                }
                withContext(Dispatchers.IO) {
                    SrukryptVault.storeImage(
                        context,
                        unlockedKey,
                        request.title,
                        request.mimeType,
                        requireNotNull(imageBytes)
                    )
                }
                if (sessionKey != null) {
                    refreshEntries(unlockedKey)
                    lastInteraction = SystemClock.elapsedRealtime()
                    infoText = "Imported into Srukrypt. The encrypted SruGen chat copy remains unchanged."
                } else {
                    errorText = "The vault locked while the image was importing. Unlock it and try again."
                    infoText = ""
                }
            } catch (failure: Exception) {
                android.util.Log.e("Srukrypt", "SruGen-to-vault import failed.", failure)
                errorText = failure.message ?: "Unable to import this image into Srukrypt."
                infoText = ""
            } finally {
                imageBytes?.fill(0)
                Arrays.fill(unlockedKey, 0)
                busy = false
            }
        }
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

    val unlockAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val authChallenge = pendingUnlockChallenge
        pendingUnlockChallenge = null
        if (result.resultCode == Activity.RESULT_OK && authChallenge != null) {
            completeUnlock(authChallenge)
        } else {
            authChallenge?.let { SrukryptVault.discardChallenge(it) }
            busy = false
            if (result.resultCode != Activity.RESULT_CANCELED) {
                errorText = "Android device authentication was not completed. The vault remains locked."
            }
        }
    }

    val resetAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            confirmVaultReset = true
        } else if (result.resultCode != Activity.RESULT_CANCELED) {
            errorText = "Device authentication was not completed. The vault has not been reset."
        }
    }

    fun requestVaultReset() {
        if (busy) return
        if (SrukryptVault.hasResetPassphrase(context)) {
            passphraseMode = "reset"
            passphraseText = ""
            passphraseConfirm = ""
            passphraseDialog = true
            return
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isDeviceSecure) {
            val credentialIntent = keyguard.createConfirmDeviceCredentialIntent(
                "Confirm Srukrypt reset",
                "Authenticate with your device PIN, pattern or password to continue."
            )
            if (credentialIntent == null) {
                errorText = "Android could not open device authentication. The vault has not been reset."
            } else {
                resetAuthLauncher.launch(credentialIntent)
            }
        } else {
            // No secure lock is configured, so proceed directly to the explicit
            // in-app destructive confirmation.
            confirmVaultReset = true
        }
    }

    fun performVaultReset() {
        if (busy) return
        confirmVaultReset = false
        busy = true
        errorText = ""
        infoText = "Permanently removing encrypted vault contents and keys…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SrukryptVault.resetVault(context) }
                lockVault()
                infoText = "Vault reset complete. All encrypted items and vault keys were removed."
                errorText = ""
            } catch (failure: Exception) {
                errorText = failure.message ?: "The vault reset could not be completed."
                infoText = ""
            } finally {
                busy = false
            }
        }
    }

    fun openVaultImage(entry: SrukryptEntryPreview) {
        if (loadingEntryImage || viewingEntryImage != null) return
        val key = sessionKey?.copyOf()
        if (key == null) {
            errorText = "Unlock Srukrypt before viewing this image."
            return
        }
        loadingEntryImage = true
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val (_, bytes) = SrukryptVault.readImage(context, key, entry.id)
                    try {
                        decodeSruvikViewerBitmap(bytes)
                    } finally {
                        Arrays.fill(bytes, 0)
                    }
                }
                if (bitmap == null) errorText = "This encrypted image could not be decoded."
                else {
                    viewingEntryImage = bitmap to entry.title
                    lastInteraction = SystemClock.elapsedRealtime()
                }
            } catch (failure: Exception) {
                errorText = failure.message ?: "Unable to open this encrypted image."
            } finally {
                Arrays.fill(key, 0)
                loadingEntryImage = false
            }
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

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    // Android 10 and lower do not support BIOMETRIC_STRONG |
                    // DEVICE_CREDENTIAL in BiometricPrompt. The system keyguard
                    // credential activity authorizes this time-based Keystore key.
                    val keyguard = context.getSystemService(KeyguardManager::class.java)
                    if (keyguard?.isDeviceSecure != true) {
                        SrukryptVault.discardChallenge(authChallenge)
                        errorText = "Set a secure Android screen lock (PIN, pattern or password) in Settings before creating or unlocking Srukrypt."
                        busy = false
                        return@launch
                    }
                    val credentialIntent = keyguard.createConfirmDeviceCredentialIntent(
                        "Unlock Srukrypt",
                        "Confirm your Android device PIN, pattern or password."
                    )
                    if (credentialIntent == null) {
                        SrukryptVault.discardChallenge(authChallenge)
                        errorText = "Android could not open the system device-lock prompt. The vault has not been opened."
                        busy = false
                        return@launch
                    }
                    pendingUnlockChallenge = authChallenge
                    unlockAuthLauncher.launch(credentialIntent)
                    return@launch
                }

                val allowed = authChallenge.allowedAuthenticators
                val availability = BiometricManager.from(activity)
                    .canAuthenticate(allowed)
                if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
                    SrukryptVault.discardChallenge(authChallenge)
                    errorText = when (availability) {
                        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                            "No supported Android device authentication is configured. Set a screen lock and, if desired, enrol a strong biometric in Android Settings."
                        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                            "Android reports no compatible system authenticator. Set a secure screen lock in Settings."
                        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                            "Android system authentication is temporarily unavailable. Try again."
                        else ->
                            "Android cannot use the configured device authentication for Srukrypt (status $availability)."
                    }
                    busy = false
                    return@launch
                }

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Srukrypt")
                    .setSubtitle("Use your Android fingerprint, face or device PIN, pattern or password.")
                    .setAllowedAuthenticators(allowed)
                    .build()
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            // Important: no CryptoObject here. Android documents that
                            // DEVICE_CREDENTIAL fallback requires a time-based key and
                            // authenticate(promptInfo) without a CryptoObject.
                            completeUnlock(authChallenge)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            SrukryptVault.discardChallenge(authChallenge)
                            busy = false
                            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                            ) {
                                errorText = errString.toString()
                            }
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            errorText = "Android did not recognise the device authentication. Try again or use your PIN, pattern or password."
                        }
                    }
                )
                // Do not pass BiometricPrompt.CryptoObject when DEVICE_CREDENTIAL
                // is allowed; the Keystore key authorizes Cipher.init after success.
                prompt.authenticate(promptInfo)
            } catch (failure: Exception) {
                android.util.Log.e("Srukrypt", "Could not prepare the Android system-authentication challenge.", failure)
                challenge?.let { SrukryptVault.discardChallenge(it) }
                pendingUnlockChallenge = null
                busy = false
                errorText = when (failure) {
                    is java.security.KeyStoreException ->
                        "Android Keystore error: ${failure.message}"
                    is android.security.keystore.KeyPermanentlyInvalidatedException ->
                        "Android invalidated the old vault key. A reset passphrase can only erase the vault; it cannot recover these items."
                    is java.security.InvalidKeyException ->
                        "Android could not prepare the Srukrypt key: ${failure.message}"
                    else -> failure.message ?: "Unable to prepare Srukrypt for Android device authentication."
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
        val setting = operation == "set"
        if (setting && typed.size < 10) {
            errorText = "Use at least 10 characters for a reset passphrase."
            Arrays.fill(typed, '\u0000')
            Arrays.fill(confirm, '\u0000')
            return
        }
        if (setting && passphraseText != passphraseConfirm) {
            errorText = "The reset passphrases do not match."
            Arrays.fill(typed, '\u0000')
            Arrays.fill(confirm, '\u0000')
            return
        }
        val currentKey = if (setting) sessionKey?.copyOf() else null
        if (setting && currentKey == null) {
            Arrays.fill(typed, '\u0000')
            Arrays.fill(confirm, '\u0000')
            errorText = "Unlock Srukrypt with your Android device lock before setting a reset passphrase."
            return
        }

        passphraseDialog = false
        busy = true
        errorText = ""
        infoText = if (setting) "Saving a reset-only passphrase…" else "Verifying the reset passphrase…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (setting) {
                        SrukryptVault.setResetPassphrase(context, requireNotNull(currentKey), typed)
                    } else {
                        SrukryptVault.verifyResetPassphrase(context, typed)
                    }
                }
                if (setting) {
                    infoText = "Reset passphrase saved. It cannot unlock or decrypt the vault; it only authorizes a destructive reset."
                    errorText = ""
                    vaultSettingsDialog = false
                } else {
                    infoText = "Reset passphrase verified. Confirm below to permanently erase the vault."
                    errorText = ""
                    confirmVaultReset = true
                }
                lastInteraction = SystemClock.elapsedRealtime()
            } catch (failure: Exception) {
                infoText = ""
                errorText = failure.message ?: "The reset passphrase could not be verified."
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
            .padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("PRIVATE BY DESIGN", style = MaterialTheme.typography.labelLarge,
                color = SruvikColors.LimeMuted, letterSpacing = 1.8.sp)
            Text("Srukrypt", style = MaterialTheme.typography.displayLarge, color = SruvikColors.TextPrimary)
            Text("Your images. Your keys. Your device.",
                style = MaterialTheme.typography.bodyLarge, color = SruvikColors.TextSecondary)
        }

        ObsidianGlassBox(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(17.dp),
            accent = SruvikColors.Primary
        ) {
            Row(
                Modifier.fillMaxWidth().padding(13.dp),
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
            Surface(shape = RoundedCornerShape(20.dp), color = SruvikColors.Primary.copy(alpha = .15f)) {
                Text("OFFLINE", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = SruvikColors.Lime, style = MaterialTheme.typography.labelSmall)
            }
            }
        }

        if (busy || errorText.isNotBlank() || infoText.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (errorText.isNotBlank()) Color(0xFF42282E) else SruvikColors.SurfaceElevated.copy(alpha = .58f),
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
            ObsidianGlassBox(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                accent = SruvikColors.SecondaryAccent
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                Box(Modifier.size(76.dp).clip(CircleShape)
                    .background(SruvikColors.Primary.copy(alpha = .16f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, null, tint = SruvikColors.Lime, modifier = Modifier.size(36.dp))
                }
                Text(
                    if (SrukryptVault.hasVault(context)) "Your vault is locked" else "Create your private vault",
                    style = MaterialTheme.typography.headlineSmall, color = SruvikColors.TextPrimary
                )
                Text(
                    "Unlock only with Android's system device authentication: your enrolled fingerprint/face or device PIN, pattern or password. A reset passphrase can never unlock this vault.",
                    style = MaterialTheme.typography.bodyMedium, color = SruvikColors.TextSecondary
                )
                ObsidianPrimaryButton(
                    onClick = { requestUnlock() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Preparing secure session..." else if (SrukryptVault.hasVault(context)) "Unlock vault" else "Set up Srukrypt")
                }
                Text(
                    "Srukrypt never collects fingerprints. Android handles all device authentication. The reset passphrase is optional and only permits erasing the vault after a second confirmation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SruvikColors.TextMuted
                )
                if (SrukryptVault.hasVault(context)) {
                    TextButton(
                        onClick = { requestVaultReset() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.DeleteForever, null, tint = Color(0xFFFF9B9B))
                        Spacer(Modifier.width(7.dp))
                        Text(if (SrukryptVault.hasResetPassphrase(context)) "Forgot passphrase? Reset vault" else "Reset vault", color = Color(0xFFFF9B9B))
                    }
                }
            }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("VAULT UNLOCKED", style = MaterialTheme.typography.labelLarge,
                        color = SruvikColors.Lime, letterSpacing = 1.2.sp)
                    Text(entries.size.toString() + " private item(s)", style = MaterialTheme.typography.bodySmall,
                        color = SruvikColors.TextSecondary)
                }
                IconButton(
                    onClick = { vaultSettingsDialog = true },
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(Icons.Rounded.Settings, "Srukrypt settings", tint = SruvikColors.TextPrimary)
                }
                ObsidianSecondaryButton(onClick = { lockVault() }) {
                    Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lock now")
                }
                Spacer(Modifier.width(7.dp))
                ObsidianPrimaryButton(
                    onClick = { beginImport() }
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Text("Add")
                }
            }

            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))
                        .background(SruvikColors.Surface)
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
                        ObsidianGlassPanel(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(19.dp),
                            contentPadding = PaddingValues(9.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Image(
                                entry.thumbnail.asImageBitmap(),
                                contentDescription = "Open ${entry.title} in full-screen viewer",
                                modifier = Modifier.fillMaxWidth()
                                    .height(148.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .clickable { openVaultImage(entry) },
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                            Text(
                                "Tap image to view and zoom",
                                color = SruvikColors.TextMuted,
                                style = MaterialTheme.typography.labelSmall
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
            containerColor = SruvikColors.Surface,
            shape = RoundedCornerShape(23.dp)
        )
    }    
    if (vaultSettingsDialog) {
        val hasResetPassphrase = SrukryptVault.hasResetPassphrase(context)
        val hasDeviceUnlock = SrukryptVault.hasDeviceUnlock(context)
        val deviceSecure = activity?.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

        AlertDialog(
            onDismissRequest = { vaultSettingsDialog = false },
            title = { Text("Srukrypt settings", color = SruvikColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("DEVICE AUTHENTICATION", color = SruvikColors.LimeMuted,
                        style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = SruvikColors.Lime)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Android device lock", color = SruvikColors.TextPrimary)
                            Text(
                                when {
                                    hasDeviceUnlock -> "Keystore-protected · system authentication"
                                    deviceSecure -> "Screen lock configured · unlock key needs recovery"
                                    else -> "Set a PIN, pattern, password or supported biometric in Android settings"
                                },
                                color = SruvikColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (hasDeviceUnlock) Icon(Icons.Rounded.CheckCircle, null, tint = SruvikColors.Lime)
                    }
                    Divider(color = SruvikColors.Border)
                    Text("RESET PASSPHRASE", color = SruvikColors.LimeMuted,
                        style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
                    Text(
                        if (hasResetPassphrase)
                            "Configured. This phrase can only authorize a destructive vault reset; it cannot unlock or decrypt any item."
                        else
                            "Optional. Set a phrase so you can reset the vault if you can no longer use your Android device lock. Reset permanently erases every encrypted item.",
                        color = SruvikColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = {
                            vaultSettingsDialog = false
                            openPassphrase("set")
                        },
                        enabled = sessionKey != null && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Key, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (hasResetPassphrase) "Change reset passphrase" else "Set reset passphrase")
                    }
                    Divider(color = SruvikColors.Border)
                    Text("DANGER ZONE", color = Color(0xFFFFA0A0),
                        style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
                    Text(
                        "Resetting permanently removes every encrypted item, vault metadata and key. It cannot be undone.",
                        color = SruvikColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = {
                            vaultSettingsDialog = false
                            requestVaultReset()
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9B9B))
                    ) {
                        Icon(Icons.Rounded.DeleteForever, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Reset and erase vault")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vaultSettingsDialog = false }) {
                    Text("Done", color = SruvikColors.Lime)
                }
            },
            containerColor = SruvikColors.Surface,
            shape = RoundedCornerShape(23.dp)
        )
    }

    if (confirmVaultReset) {
        AlertDialog(
            onDismissRequest = { confirmVaultReset = false },
            title = { Text("Permanently reset Srukrypt?", color = Color(0xFFFFB0A8)) },
            text = {
                Text(
                    "This permanently deletes ALL encrypted vault images, metadata, passphrase data and vault keys from this app. There is no recovery after confirmation. This is not a certified secure erase of old flash-storage blocks.",
                    color = SruvikColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { performVaultReset() }, enabled = !busy) {
                    Text("Erase everything", color = Color(0xFFFF9B9B))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmVaultReset = false }) {
                    Text("Cancel", color = SruvikColors.TextSecondary)
                }
            },
            containerColor = SruvikColors.Surface,
            shape = RoundedCornerShape(23.dp)
        )
    }

    viewingEntryImage?.let { (bitmap, title) ->
        SruvikImageViewer(bitmap = bitmap, title = title) {
            if (!bitmap.isRecycled) bitmap.recycle()
            viewingEntryImage = null
        }
    }

    if (passphraseDialog) {
        val creating = passphraseMode == "set"
        AlertDialog(
            onDismissRequest = { passphraseDialog = false },
            title = {
                Text(
                    when (passphraseMode) {
                        "set" -> "Set reset passphrase"
                        else -> "Reset vault with passphrase"
                    },
                    color = SruvikColors.TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (creating)
                            "Use at least 10 characters. This phrase can only authorize a destructive reset. It never unlocks or decrypts vault contents."
                        else
                            "Enter your reset passphrase. If correct, Srukrypt will ask you to confirm permanently erasing every encrypted item and key.",
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
                ) { Text(if (passphraseMode == "set") "Save reset passphrase" else "Verify passphrase", color = SruvikColors.Lime) }
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
