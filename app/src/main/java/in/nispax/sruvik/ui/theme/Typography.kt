package `in`.nispax.sruvik.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Platform sans-serif for interface copy and platform serif for editorial display. */
object SruvikType {
    val family: FontFamily = FontFamily.SansSerif
    val display: FontFamily = FontFamily.Serif

    val material = Typography(
        displayLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Normal, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp),
        displayMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 39.sp, letterSpacing = (-0.5).sp),
        displaySmall = TextStyle(fontFamily = display, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 35.sp),
        headlineLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 33.sp),
        headlineMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.Normal, fontSize = 25.sp, lineHeight = 30.sp),
        headlineSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        titleMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp),
        titleSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        bodyLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.25.sp),
        labelMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.35.sp),
        labelSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 9.sp, lineHeight = 13.sp, letterSpacing = 0.45.sp)
    )
}
