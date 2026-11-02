package `in`.nispax.sruvik.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Sruvik's mineral-water palette: deep petrol surfaces, sea-glass primary,
 * periwinkle secondary and a restrained sand tertiary. The accent is not
 * used as a large background; it is reserved for actions, selection and focus.
 */
object SruvikColors {
    val Background = Color(0xFF101C25)
    val Surface = Color(0xFF182934)
    val SurfaceElevated = Color(0xFF203A43)
    val SurfaceSoft = Color(0xFF2B4850)

    val Primary = Color(0xFF89DCC9)
    val OnPrimary = Color(0xFF102826)
    val PrimaryContainer = Color(0xFF25534F)
    val OnPrimaryContainer = Color(0xFFC9F7EB)

    val SecondaryAccent = Color(0xFFC1C7F4)
    val OnSecondary = Color(0xFF292842)
    val SecondaryContainer = Color(0xFF363E5E)
    val OnSecondaryContainer = Color(0xFFE0E3FF)

    val Tertiary = Color(0xFFE1D6B5)
    val OnTertiary = Color(0xFF332C1D)

    val Mint = Color(0xFFB7CAC8)
    val TextPrimary = Color(0xFFF0F5F3)
    val TextSecondary = Color(0xFFB7CAC8)
    val TextMuted = Color(0xFF8CA6A6)
    val Border = Color(0xFF42606A)
    val GlassBorder = Color(0x6642606A)
    val Danger = Color(0xFFFF9B96)

    // Legacy semantic aliases retained while screens migrate to the new names.
    val Lime = Primary
    val LimeMuted = Color(0xFFB9F2E5)
}
