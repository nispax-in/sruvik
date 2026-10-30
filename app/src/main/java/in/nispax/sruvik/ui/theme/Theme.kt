package `in`.nispax.sruvik.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = SruvikColors.Lime,
    onPrimary = SruvikColors.Background,
    background = SruvikColors.Background,
    onBackground = SruvikColors.TextPrimary,
    surface = SruvikColors.Surface,
    onSurface = SruvikColors.TextPrimary,
    surfaceVariant = SruvikColors.SurfaceElevated,
    onSurfaceVariant = SruvikColors.TextSecondary,
    outline = SruvikColors.Border,
    error = SruvikColors.Danger
)

@Composable
fun SruvikTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = SruvikType.material, content = content)
}
