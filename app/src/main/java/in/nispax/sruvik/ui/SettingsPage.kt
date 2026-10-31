package `in`.nispax.sruvik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import `in`.nispax.sruvik.ui.theme.SruvikColors

@Composable
fun FirstRunProfileDialog(onContinue: (SruvikProfile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    val parsedAge = age.toIntOrNull()
    val valid = name.trim().isNotEmpty() && parsedAge != null && parsedAge in 1..120

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            shape = RoundedCornerShape(30.dp),
            color = Color(0xFF17172A),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .13f))
        ) {
            Column(Modifier.padding(23.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(Color(0xFF6255A5).copy(alpha = .26f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.WavingHand, null, tint = SruvikColors.Lime, modifier = Modifier.size(23.dp))
                }
                Text("A little about you", style = MaterialTheme.typography.headlineSmall,
                    color = SruvikColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SruvikAvatar(name.ifBlank { "Sruvik" }, Modifier.size(52.dp))
                    Text(
                        "Let's make Sruvik feel like yours. Your name creates your own Sruvik identity mark.",
                        style = MaterialTheme.typography.bodyMedium, color = SruvikColors.TextSecondary
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Your name") },
                    placeholder = { Text("What should we call you?") },
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    shape = RoundedCornerShape(17.dp),
                    colors = profileFieldColors()
                )
                OutlinedTextField(
                    value = age,
                    onValueChange = { value -> age = value.filter(Char::isDigit).take(3) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Your age") },
                    placeholder = { Text("Age in years") },
                    leadingIcon = { Icon(Icons.Rounded.Cake, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(17.dp),
                    colors = profileFieldColors()
                )
                Text(
                    "This is saved only on this device. Age is for personalisation, not age verification.",
                    style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextMuted
                )
                Button(
                    onClick = {
                        if (valid && parsedAge != null) onContinue(SruvikProfile(name.trim(), parsedAge))
                    },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7764C9),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF454258),
                        disabledContentColor = Color.White.copy(alpha = .45f)
                    )
                ) {
                    Text("Continue to Sruvik", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.ArrowForward, null, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
fun SettingsPage(
    profile: SruvikProfile,
    showDailyTip: Boolean,
    onShowDailyTipChange: (Boolean) -> Unit,
    onProfileSaved: (SruvikProfile) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editProfile by remember { mutableStateOf(false) }
    var autoLock by remember { mutableIntStateOf(SruvikPreferences.vaultAutoLockMinutes(context)) }
    var lockMenuExpanded by remember { mutableStateOf(false) }
    val q = query.trim()

    fun matches(vararg words: String) =
        q.isBlank() || words.any { it.contains(q, ignoreCase = true) }

    val profileVisible = matches("profile", "your profile", "name", "age", "edit profile", profile.name)
    val biometricVisible = matches("srukrypt", "vault", "biometric", "fingerprint", "face unlock", "privacy")
    val lockVisible = matches("srukrypt", "vault", "auto lock", "timeout", "lock duration")
    val storageVisible = matches("srukrypt", "vault", "device only", "storage", "backup", "uninstall")
    val tipsVisible = matches("home", "daily tip", "fun fact", "tip", "personalisation")
    val offlineVisible = matches("offline", "internet", "privacy", "on device", "local", "network")

    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 26.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text("PERSONALISE YOUR SPACE", style = MaterialTheme.typography.labelLarge,
            color = SruvikColors.LimeMuted, letterSpacing = 2.sp)
        Text("Settings", style = MaterialTheme.typography.displayLarge, color = SruvikColors.TextPrimary)
        Text("Your preferences, kept on this device.", style = MaterialTheme.typography.bodyMedium,
            color = SruvikColors.TextSecondary)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search settings", color = SruvikColors.TextMuted) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Search settings",
                tint = SruvikColors.TextSecondary) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search",
                            tint = SruvikColors.TextSecondary)
                    }
                }
            },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SruvikColors.TextPrimary,
                unfocusedTextColor = SruvikColors.TextPrimary,
                focusedBorderColor = SruvikColors.LimeMuted,
                unfocusedBorderColor = Color.White.copy(alpha = .13f),
                cursorColor = SruvikColors.Lime,
                focusedContainerColor = Color(0x66232842),
                unfocusedContainerColor = Color(0x55232842)
            )
        )

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            var hasResults = false
            if (profileVisible) {
                hasResults = true
                SettingsSection("YOUR PROFILE", "Your details are stored locally.") {
                    SettingsPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SruvikAvatar(profile.name, Modifier.size(64.dp))
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium,
                                    color = SruvikColors.TextPrimary)
                                Text("Age " + profile.age, style = MaterialTheme.typography.bodySmall,
                                    color = SruvikColors.TextSecondary)
                            }
                            TextButton(onClick = { editProfile = true }) {
                                Text("EDIT", color = SruvikColors.Lime, letterSpacing = .8.sp)
                            }
                        }
                    }
                }
            }

            if (biometricVisible || lockVisible || storageVisible) {
                hasResults = true
                SettingsSection("SRUKRYPT VAULT", "Security preferences for your private space.") {
                    SettingsPanel {
                        if (biometricVisible) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(11.dp)
                            ) {
                                Icon(Icons.Rounded.Fingerprint, null, tint = SruvikColors.Lime,
                                    modifier = Modifier.size(23.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Strong biometric authentication", style = MaterialTheme.typography.titleSmall,
                                        color = SruvikColors.TextPrimary)
                                    Text("Required to unwrap the vault data key on every unlock. This is a security control, not a cosmetic lock.",
                                        style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
                                }
                                Text("REQUIRED", style = MaterialTheme.typography.labelSmall, color = SruvikColors.Lime)
                            }
                        }
                        if (biometricVisible && lockVisible) SettingsThinDivider()
                        if (lockVisible) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.LockClock, null, tint = SruvikColors.TextSecondary,
                                    modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Vault auto-lock", style = MaterialTheme.typography.titleSmall,
                                        color = SruvikColors.TextPrimary)
                                    Text("Preferred idle time before the vault locks.",
                                        style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
                                }
                                Box {
                                    TextButton(onClick = { lockMenuExpanded = true }) {
                                        Text(autoLockLabel(autoLock), color = SruvikColors.Lime)
                                        Icon(Icons.Rounded.ExpandMore, null, tint = SruvikColors.Lime)
                                    }
                                    DropdownMenu(expanded = lockMenuExpanded,
                                        onDismissRequest = { lockMenuExpanded = false }) {
                                        listOf(0, 1, 5, 15, 30).forEach { minutes ->
                                            DropdownMenuItem(
                                                text = { Text(autoLockLabel(minutes)) },
                                                onClick = {
                                                    autoLock = minutes
                                                    SruvikPreferences.setVaultAutoLockMinutes(context, minutes)
                                                    lockMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (storageVisible) {
                            if (biometricVisible || lockVisible) SettingsThinDivider()
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                Icon(Icons.Rounded.PhoneAndroid, null, tint = SruvikColors.LimeMuted,
                                    modifier = Modifier.padding(top = 2.dp).size(20.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Device-only storage", style = MaterialTheme.typography.titleSmall,
                                        color = SruvikColors.TextPrimary)
                                    Text("Sruvik disables Android backup and reserves a private no-backup storage area for the future vault. Uninstalling the app removes app data; export anything you want to keep.",
                                        style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
                                }
                            }
                        }
                    }
                    Text(
                        "Srukrypt encrypts images and their metadata with AES-256-GCM. The data key is wrapped by a per-use biometric-authenticated Android Keystore key. Enrolling new biometrics may invalidate the key; export important items before changing device security.",
                        style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextMuted
                    )
                }
            }

            if (tipsVisible) {
                hasResults = true
                SettingsSection("HOME EXPERIENCE", "Small touches on your home screen.") {
                    SettingsPanel {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Lightbulb,
                            title = "Daily tip or fun fact",
                            description = "Show a short offline tip below your greeting.",
                            checked = showDailyTip,
                            onCheckedChange = onShowDailyTipChange
                        )
                    }
                }
            }

            if (offlineVisible) {
                hasResults = true
                SettingsSection("OFFLINE & PRIVACY", "What works without a connection.") {
                    SettingsPanel {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.CloudOff, null, tint = SruvikColors.Lime,
                                modifier = Modifier.size(23.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Offline-ready", style = MaterialTheme.typography.titleSmall,
                                    color = SruvikColors.TextPrimary)
                                Text("Current tools and on-device image upscaling do not need an internet connection. No account or cloud sync is used.",
                                    style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
                            }
                            Surface(shape = RoundedCornerShape(30.dp),
                                color = Color(0xFF7CB99B).copy(alpha = .15f)) {
                                Text("LOCAL", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    color = SruvikColors.Lime, style = MaterialTheme.typography.labelSmall,
                                    letterSpacing = .8.sp)
                            }
                        }
                    }
                }
            }

            if (!hasResults) {
                SettingsPanel {
                    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.SearchOff, null, tint = SruvikColors.TextMuted,
                            modifier = Modifier.size(27.dp))
                        Text("No matching settings", color = SruvikColors.TextPrimary,
                            style = MaterialTheme.typography.titleMedium)
                        Text("Try another word, such as “profile”, “vault” or “offline”.",
                            color = SruvikColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (editProfile) {
        EditProfileDialog(profile, onDismiss = { editProfile = false }) {
            onProfileSaved(it)
            editProfile = false
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge,
                color = SruvikColors.LimeMuted, letterSpacing = 1.5.sp)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = SruvikColors.TextMuted)
        }
        content()
    }
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
            .background(Brush.linearGradient(listOf(Color(0xAA30304B), Color(0xB8181A2B))))
            .border(1.dp, Color.White.copy(alpha = .11f), RoundedCornerShape(23.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Icon(icon, null, tint = SruvikColors.TextSecondary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = SruvikColors.TextPrimary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = SruvikColors.TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsThinDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = .09f))
}

@Composable
private fun EditProfileDialog(
    current: SruvikProfile,
    onDismiss: () -> Unit,
    onSave: (SruvikProfile) -> Unit
) {
    var name by remember(current) { mutableStateOf(current.name) }
    var age by remember(current) { mutableStateOf(current.age.toString()) }
    val parsedAge = age.toIntOrNull()
    val valid = name.trim().isNotEmpty() && parsedAge != null && parsedAge in 1..120

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your profile", color = SruvikColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SruvikAvatar(name.ifBlank { "Sruvik" }, Modifier.size(52.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Your identity mark", color = SruvikColors.TextPrimary,
                            style = MaterialTheme.typography.titleSmall)
                        Text("Updates as your name changes", color = SruvikColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(32) },
                    singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                    colors = profileFieldColors()
                )
                OutlinedTextField(
                    value = age, onValueChange = { age = it.filter(Char::isDigit).take(3) },
                    singleLine = true, label = { Text("Age") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = profileFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (parsedAge != null) onSave(SruvikProfile(name.trim(), parsedAge))
            }) { Text("Save", color = SruvikColors.Lime) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SruvikColors.TextSecondary) } },
        containerColor = Color(0xFF19192D),
        shape = RoundedCornerShape(25.dp)
    )
}

@Composable
private fun profileFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = SruvikColors.TextPrimary,
    unfocusedTextColor = SruvikColors.TextPrimary,
    focusedBorderColor = SruvikColors.LimeMuted,
    unfocusedBorderColor = Color.White.copy(alpha = .14f),
    cursorColor = SruvikColors.Lime,
    focusedLabelColor = SruvikColors.LimeMuted,
    unfocusedLabelColor = SruvikColors.TextMuted
)

private fun autoLockLabel(minutes: Int) = when (minutes) {
    0 -> "When app leaves"
    1 -> "1 minute"
    else -> "$minutes minutes"
}
