package com.example.allinonedailer.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.allinonedailer.data.AppTheme
import com.example.allinonedailer.data.ThemeMode

/**
 * Nothing-style monochrome theme: pure black canvas, off-white ink, a single
 * red alert accent, and a monospace ("dot-matrix"-ish) type system. Dynamic
 * color is intentionally off so the look is consistent across devices.
 *
 * Dark is the default; Settings → Theme adds a light variant (off-white canvas,
 * white surfaces, near-black ink, the same red) and "follow system".
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

// Light: contrast checked against WCAG AA (ink 18:1, grey 6:1, red 4.7:1 on the canvas).
private val LightCanvas = Color(0xFFF5F5F5)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE8E8E8)
private val LightInk = Color(0xFF111111)
private val LightGrey = Color(0xFF5E5E5E)

private val NothingLightColors = lightColorScheme(
    primary = LightInk,
    onPrimary = LightSurface,
    secondary = LightInk,
    onSecondary = LightSurface,
    tertiary = NothingRed,
    onTertiary = Color.White,
    background = LightCanvas,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightGrey,
    surfaceTint = LightSurface,
    inverseSurface = LightInk,
    inverseOnSurface = LightCanvas,
    inversePrimary = LightCanvas,
    error = NothingRed,
    onError = Color.White,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightInk,
    errorContainer = LightSurface,
    onErrorContainer = NothingRed,
    outline = Color(0xFF7A7A7A),
    outlineVariant = Color(0xFFDADADA),
    surfaceBright = LightSurface,
    surfaceDim = Color(0xFFE0E0E0),
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = Color(0xFFEDEDED)
)

/**
 * Colours the Material roles don't cover (keypad keys, pills, call buttons).
 * Read with `DialerTheme.colors`. Dark values are the ones the screens used before
 * the light theme existed.
 */
@Immutable
data class DialerColors(
    val isDark: Boolean,
    /** Text and icons drawn straight on the canvas (pure white on dark). */
    val ink: Color,
    /** Text and icons on an [ink]-filled shape (e.g. the call button). */
    val onInk: Color,
    /** Secondary labels and icons; AA on the canvas and on surfaces. */
    val muted: Color,
    /** Keypad keys and in-call control circles. */
    val keyBackground: Color,
    /** Thin outlines round keys, cards and fields. */
    val keyOutline: Color,
    /** Menus, sheets and pop-up panels. */
    val panel: Color,
    /** Chips and segmented pills. */
    val chip: Color,
    /** Faint list dividers. */
    val divider: Color,
    /** Stronger hairlines between list rows. */
    val hairline: Color,
    /** The Nothing red and what sits on it. Same in both themes. */
    val accent: Color,
    val onAccent: Color,
    /** Answer / call button: white on dark, near-black on light. */
    val answer: Color,
    val onAnswer: Color,
    /** Decline / end call: always red. */
    val decline: Color,
    val onDecline: Color,
    /** The incoming-call overlay pill. */
    val callPillBackground: Color,
    val callPillBorder: Color
)

private val DarkDialerColors = DialerColors(
    isDark = true,
    ink = Color.White,
    onInk = Color.Black,
    muted = Color.White.copy(alpha = 0.55f),
    keyBackground = Color(0xFF262626),
    keyOutline = Color(0xFF2E2E2E),
    panel = Color(0xFF1A1A1A),
    chip = Color(0xFF2C2D30),
    divider = Color(0xFF161616),
    hairline = Color(0xFF262626),
    accent = NothingRed,
    onAccent = Color.White,
    answer = Color.White,
    onAnswer = Color.Black,
    decline = NothingRed,
    onDecline = Color.White,
    callPillBackground = Color.Black,
    callPillBorder = Color.White.copy(alpha = 0.14f)
)

private val LightDialerColors = DialerColors(
    isDark = false,
    ink = LightInk,
    onInk = Color.White,
    muted = Color.Black.copy(alpha = 0.6f),
    keyBackground = Color(0xFFE4E4E4),
    keyOutline = Color(0xFFC8C8C8),
    panel = LightSurface,
    chip = Color(0xFFE4E4E4),
    divider = Color(0xFFE2E2E2),
    hairline = Color(0xFFD6D6D6),
    accent = NothingRed,
    onAccent = Color.White,
    answer = LightInk,
    onAnswer = Color.White,
    decline = NothingRed,
    onDecline = Color.White,
    callPillBackground = LightSurface,
    callPillBorder = Color.Black.copy(alpha = 0.12f)
)

private val LocalDialerColors = staticCompositionLocalOf { DarkDialerColors }

object DialerTheme {
    val colors: DialerColors
        @Composable @ReadOnlyComposable get() = LocalDialerColors.current
}

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

/** Whether the app should draw dark right now, from Settings → Theme. */
@Composable
fun isAppInDarkTheme(): Boolean = when (AppTheme.current(LocalContext.current)) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun AllinoneDailerTheme(
    darkTheme: Boolean = isAppInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect { view.setLightSystemBars(!darkTheme) }
    }
    CompositionLocalProvider(LocalDialerColors provides if (darkTheme) DarkDialerColors else LightDialerColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) NothingColors else NothingLightColors,
            typography = NothingTypography,
            content = content
        )
    }
}

/**
 * Always dark, whatever the setting: the incoming-call screen over a full-bleed
 * contact photo (like iOS), so the photo and the white text on it read well.
 * Status/navigation bar icons turn light while it shows.
 */
@Composable
fun AlwaysDarkTheme(content: @Composable () -> Unit) {
    val outerDark = DialerTheme.colors.isDark
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(outerDark) {
            view.setLightSystemBars(false)
            onDispose { view.setLightSystemBars(!outerDark) }
        }
    }
    CompositionLocalProvider(LocalDialerColors provides DarkDialerColors) {
        MaterialTheme(colorScheme = NothingColors, typography = NothingTypography, content = content)
    }
}

/** Dark status/navigation bar icons on the light theme, light ones on dark. No-op outside an activity. */
private fun View.setLightSystemBars(light: Boolean) {
    val window = context.findActivity()?.window ?: return
    WindowCompat.getInsetsController(window, this).apply {
        isAppearanceLightStatusBars = light
        isAppearanceLightNavigationBars = light
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
