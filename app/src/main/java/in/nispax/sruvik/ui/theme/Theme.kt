package `in`.nispax.sruvik.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = SruvikColors.Primary,
    onPrimary = SruvikColors.OnPrimary,
    primaryContainer = SruvikColors.PrimaryContainer,
    onPrimaryContainer = SruvikColors.OnPrimaryContainer,
    secondary = SruvikColors.SecondaryAccent,
    onSecondary = SruvikColors.OnSecondary,
    secondaryContainer = SruvikColors.SecondaryContainer,
    onSecondaryContainer = SruvikColors.OnSecondaryContainer,
    tertiary = SruvikColors.Tertiary,
    onTertiary = SruvikColors.OnTertiary,
    background = SruvikColors.Background,
    onBackground = SruvikColors.TextPrimary,
    surface = SruvikColors.Surface,
    onSurface = SruvikColors.TextPrimary,
    surfaceVariant = SruvikColors.SurfaceElevated,
    onSurfaceVariant = SruvikColors.TextSecondary,
    surfaceTint = SruvikColors.Primary,
    outline = SruvikColors.Border,
    outlineVariant = SruvikColors.GlassBorder,
    error = SruvikColors.Danger
)

@Composable
fun SruvikTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = SruvikType.material, content = content)
}
