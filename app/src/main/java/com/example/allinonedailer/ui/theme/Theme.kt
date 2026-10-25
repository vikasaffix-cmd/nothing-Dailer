package com.example.allinonedailer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Nothing-style monochrome theme: pure black canvas, off-white ink, a single
 * red alert accent, and a monospace ("dot-matrix"-ish) type system. Dynamic
 * color is intentionally off so the look is consistent across devices.
 */
val NothingBlack = Color(0xFF000000)
val NothingSurface = Color(0xFF0D0D0D)
val NothingSurfaceVariant = Color(0xFF1A1A1A)
val NothingWhite = Color(0xFFF5F5F5)
val NothingGrey = Color(0xFF8A8A8A)
val NothingRed = Color(0xFFD71921)

private val NothingColors = darkColorScheme(
    primary = NothingWhite,
    onPrimary = NothingBlack,
    secondary = NothingWhite,
    onSecondary = NothingBlack,
    tertiary = NothingRed,
    onTertiary = NothingWhite,
    background = NothingBlack,
    onBackground = NothingWhite,
    surface = NothingSurface,
    onSurface = NothingWhite,
    surfaceVariant = NothingSurfaceVariant,
    onSurfaceVariant = NothingGrey,
    error = NothingRed,
    onError = NothingWhite,
    primaryContainer = NothingSurfaceVariant,
    onPrimaryContainer = NothingWhite,
    errorContainer = NothingSurfaceVariant,
    onErrorContainer = NothingRed,
    outline = NothingGrey,
    outlineVariant = NothingSurfaceVariant
)

private val Mono = FontFamily.Monospace

private val NothingTypography = Typography(
    displayLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = 1.sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontSize = 15.sp, letterSpacing = 0.3.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp)
)

@Composable
fun AllinoneDailerTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NothingColors,
        typography = NothingTypography,
        content = content
    )
}
