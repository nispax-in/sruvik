package `in`.nispax.sruvik.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Readable sans-serif interface with a restrained editorial display face. */
object SruvikType {
    val family: FontFamily = FontFamily.SansSerif
    val display: FontFamily = FontFamily.SansSerif

    val material = Typography(
        displayLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-1.0).sp),
        displayMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.7).sp),
        displaySmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 31.sp, lineHeight = 37.sp),
        headlineLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 29.sp, lineHeight = 35.sp),
        headlineMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp),
        titleLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
        titleSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp),
        bodyLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.15.sp),
        labelSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp)
    )
}
