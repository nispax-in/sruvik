package `in`.nispax.sruvik.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.nispax.sruvik.srugen.SruGenModelInstaller
import `in`.nispax.sruvik.ui.theme.SruvikColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ModelsPage(onOpenSruGen: () -> Unit) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(true) }
    var installed by remember { mutableStateOf(false) }
    var modelBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            val ready = SruGenModelInstaller.isInstalled(context)
            val directory = SruGenModelInstaller.modelDirectory(context)
            val bytes = if (ready) {
                directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                0L
            }
            ready to bytes
        }
        installed = result.first
        modelBytes = result.second
        checking = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(top = 28.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            "YOUR DEVICE",
            style = MaterialTheme.typography.labelLarge,
            color = SruvikColors.LimeMuted,
            letterSpacing = 2.sp
        )
        Text(
            "Model library",
            style = MaterialTheme.typography.displayLarge,
            color = SruvikColors.TextPrimary
        )
        Text(
            "Models stored on this device for private, offline creative work.",
            style = MaterialTheme.typography.bodyLarge,
            color = SruvikColors.TextSecondary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xAA17231D)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(15.dp),
                        color = Color.White.copy(alpha = 0.08f)
                    ) {
                        Icon(
                            Icons.Rounded.Memory,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                            tint = SruvikColors.LimeMuted
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Stable Diffusion 1.5",
                            style = MaterialTheme.typography.titleLarge,
                            color = SruvikColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "MediaPipe · Image generation",
                            style = MaterialTheme.typography.bodySmall,
                            color = SruvikColors.TextSecondary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        checking -> {
                            CircularProgressIndicator(
                                modifier = Modifier.width(17.dp).height(17.dp),
                                strokeWidth = 2.dp,
                                color = SruvikColors.LimeMuted
                            )
                            Spacer(Modifier.width(9.dp))
                            Text("Checking local model files…", color = SruvikColors.TextSecondary)
                        }
                        installed -> {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = SruvikColors.LimeMuted
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Installed on this device",
                                color = SruvikColors.LimeMuted,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Rounded.Storage,
                                contentDescription = null,
                                tint = SruvikColors.TextMuted
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Not installed",
                                color = SruvikColors.TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (installed && !checking) {
                    Text(
                        "Storage used: ${formatModelSize(modelBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SruvikColors.TextSecondary
                    )
                    Text(
                        "Available offline in SruGen. Your model files stay on this device when you update the app without uninstalling it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SruvikColors.TextSecondary
                    )
                } else if (!checking) {
                    Text(
                        "Open SruGen Studio to download and install this model. Once installed, it will be listed here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SruvikColors.TextSecondary
                    )
                }

                Button(
                    onClick = onOpenSruGen,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF657A54),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (installed) "Open SruGen Studio" else "Get model in SruGen")
                }
            }
        }

        Text(
            "More model integrations will appear here as they are added to Sruvik.",
            style = MaterialTheme.typography.bodySmall,
            color = SruvikColors.TextMuted
        )
    }
}

private fun formatModelSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        String.format(Locale.getDefault(), "%.2f GB", gib)
    } else {
        String.format(Locale.getDefault(), "%.0f MB", bytes / (1024.0 * 1024.0))
    }
}
