package `in`.nispax.sruvik.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Obsidian Liquid Glass — near-black mineral surfaces with controlled cyan and
 * violet light. Colour is reserved for hierarchy, focus and system state.
 */
object SruvikColors {
    val Background = Color(0xFF080A10)
    val Surface = Color(0xFF111520)
    val SurfaceElevated = Color(0xFF171D2A)
    val SurfaceSoft = Color(0xFF202838)

    val Primary = Color(0xFF75E3F3)
    val OnPrimary = Color(0xFF061318)
    val PrimaryContainer = Color(0xFF173943)
    val OnPrimaryContainer = Color(0xFFC8F7FC)

    val SecondaryAccent = Color(0xFFA99BFF)
    val OnSecondary = Color(0xFF17132B)
    val SecondaryContainer = Color(0xFF302A50)
    val OnSecondaryContainer = Color(0xFFE5DFFF)

    val Tertiary = Color(0xFFD6C9B8)
    val OnTertiary = Color(0xFF28221B)

    val Mint = Color(0xFF9FB8C1)
    val TextPrimary = Color(0xFFF0F2F8)
    val TextSecondary = Color(0xFFB0B8C9)
    val TextMuted = Color(0xFF7E899E)
    val Border = Color(0xFF343B4B)
    val GlassBorder = Color(0x667E91B8)
    val Danger = Color(0xFFFF829B)

    // Legacy semantic aliases keep existing screens functional during migration.
    val Lime = Primary
    val LimeMuted = Color(0xFFB7F1F7)
}
