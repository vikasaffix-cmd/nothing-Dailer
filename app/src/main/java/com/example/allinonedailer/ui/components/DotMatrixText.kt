package com.example.allinonedailer.ui.components

import android.content.Context
import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.allinonedailer.R
import com.example.allinonedailer.data.AppFont
import com.example.allinonedailer.data.AppFontStyle

/**
 * A 5x7 dot-matrix glyph set (Latin capitals, digits and common phone
 * punctuation). Each glyph is seven rows of five columns, '#' = lit dot.
 */
object DotMatrixFont {
    const val COLS = 5
    const val ROWS = 7

    private val glyphs: Map<Char, Array<String>> = mapOf(
        ' ' to arrayOf(".....", ".....", ".....", ".....", ".....", ".....", "....."),
        'A' to arrayOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to arrayOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to arrayOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to arrayOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
        'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to arrayOf(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'J' to arrayOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to arrayOf("#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
        'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to arrayOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to arrayOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to arrayOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
        'X' to arrayOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to arrayOf("#...#", "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#.."),
        'Z' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        '+' to arrayOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        '-' to arrayOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '(' to arrayOf("...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#."),
        ')' to arrayOf(".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#..."),
        '.' to arrayOf(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
        ',' to arrayOf(".....", ".....", ".....", ".....", ".##..", "..#..", ".#..."),
        ':' to arrayOf(".....", ".##..", ".##..", ".....", ".##..", ".##..", "....."),
        '#' to arrayOf(".#.#.", ".#.#.", "#####", ".#.#.", "#####", ".#.#.", ".#.#."),
        '*' to arrayOf(".....", "..#..", "#.#.#", ".###.", "#.#.#", "..#..", "....."),
        '/' to arrayOf(".....", "....#", "...#.", "..#..", ".#...", "#....", "....."),
        '\'' to arrayOf("..#..", "..#..", ".#...", ".....", ".....", ".....", "....."),
        '&' to arrayOf(".##..", "#..#.", "#.#..", ".#...", "#.#.#", "#..#.", ".##.#"),
        '?' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
        '!' to arrayOf("..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#..")
    )

    fun glyph(c: Char): Array<String>? = glyphs[c]

    /** Upper-cases and maps typographic characters onto the glyph set. */
    fun normalize(text: String): String =
        text.uppercase().replace("…", "...").replace('’', '\'').replace('–', '-').replace('—', '-')

    fun supports(normalized: String): Boolean = normalized.all { glyphs.containsKey(it) }
}

/**
 * Default display font: "Nothing Font" by Dmitriy Sychiov, a Nothing Phone–inspired
 * dot font (SIL OFL 1.1; licence and readme ship in assets/licenses).
 */
private val SychiovDotFamily = FontFamily(Font(R.font.sychiov_dot))

/** Doto: open-source (SIL OFL) dot-matrix font, round dots at a bold weight. */
@OptIn(ExperimentalTextApi::class)
private val DotoFamily = FontFamily(
    Font(
        resId = R.font.doto,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(700),
            FontVariation.Setting("ROND", 100f)
        )
    )
)

/**
 * The app's display text for names, numbers, keypad digits and titles, in the
 * font chosen under Settings → Font (Doto dot-matrix by default). [pitch] is
 * the size of one dot in the classic 7-row grid; the font styles are sized to
 * the same height. Text shrinks to fit the width, wrapping onto up to
 * [maxLines] lines.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun DotMatrixText(
    text: String,
    modifier: Modifier = Modifier,
    pitch: Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.onBackground,
    centered: Boolean = false,
    maxLines: Int = 1
) {
    val context = LocalContext.current
    val version = AppFont.customVersion
    // The user's own font file (Settings → Font → Custom); Doto if there isn't one.
    val style = AppFont.current(context)
    // Only touches the file system when the custom font is chosen, once per import.
    val customFamily = if (style == AppFontStyle.CUSTOM) customFontFamily(context, version) else SychiovDotFamily
    // Dot fonts only cover Latin; Hindi/Marathi labels and Devanagari names
    // switch to the phone's own font instead of mixing in fallback glyphs.
    val fallback = remember(text, style, version) { !DisplayGlyphs.canShow(context, style, text) }
    when {
        // Drawn dots have no text for screen readers, so describe them.
        style == AppFontStyle.CLASSIC && !fallback -> ClassicDotText(
            text, modifier.semantics { contentDescription = text }, pitch, color, centered, maxLines
        )
        fallback -> FontDisplayText(
            text = text,
            family = FontFamily.Default,
            weight = FontWeight.Medium,
            modifier = modifier,
            pitch = pitch,
            color = color,
            centered = centered,
            maxLines = maxLines,
            systemFallback = true
        )
        else -> FontDisplayText(
            text = text.uppercase(),
            family = when (style) {
                AppFontStyle.SYCHIOV_DOT -> SychiovDotFamily
                AppFontStyle.DOTO -> DotoFamily
                AppFontStyle.MONO -> FontFamily.Monospace
                AppFontStyle.CUSTOM -> customFamily
                else -> FontFamily.Default
            },
            weight = when (style) {
                AppFontStyle.DOTO -> FontWeight.Bold
                AppFontStyle.SYCHIOV_DOT, AppFontStyle.CUSTOM -> FontWeight.Normal
                else -> FontWeight.Medium
            },
            modifier = modifier,
            pitch = pitch,
            color = color,
            centered = centered,
            maxLines = maxLines
        )
    }
}

/** The imported font's family for [version], built once rather than per text. */
private var customFamilyCache: Pair<Int, FontFamily>? = null

private fun customFontFamily(context: Context, version: Int): FontFamily {
    customFamilyCache?.let { (v, family) -> if (v == version) return family }
    val file = AppFont.customFontFile(context)
    val family = if (file.length() > 0) FontFamily(Font(file)) else SychiovDotFamily
    customFamilyCache = version to family
    return family
}

/**
 * Whether the chosen display font has a glyph for every character of a text.
 * Checked with [Paint.hasGlyph] against the bare font (no system fallback) on
 * Android 10+, and by script (Latin and symbols only) before that.
 */
private object DisplayGlyphs {
    private val bare = HashMap<String, android.graphics.Typeface?>()
    private val paints = HashMap<android.graphics.Typeface, Paint>()

    fun canShow(context: Context, style: AppFontStyle, text: String): Boolean {
        val resId = when (style) {
            AppFontStyle.SYCHIOV_DOT -> R.font.sychiov_dot
            AppFontStyle.DOTO -> R.font.doto
            AppFontStyle.CUSTOM -> if (customFontFamily(context, AppFont.customVersion) !== SychiovDotFamily) 0 else R.font.sychiov_dot
            AppFontStyle.CLASSIC -> return DotMatrixFont.supports(DotMatrixFont.normalize(text))
            // System fonts already cover Devanagari.
            AppFontStyle.MONO, AppFontStyle.STANDARD -> return true
        }
        val shown = text.uppercase().filterNot { it.isWhitespace() }
        if (shown.isEmpty()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return shown.allCodePoints(::isLatinOrSymbol)
        val typeface = typefaceFor(context, resId) ?: return shown.allCodePoints(::isLatinOrSymbol)
        val paint = paints.getOrPut(typeface) { Paint().apply { this.typeface = typeface } }
        return shown.allCodePoints { paint.hasGlyph(String(Character.toChars(it))) }
    }

    private inline fun String.allCodePoints(test: (Int) -> Boolean): Boolean {
        var i = 0
        while (i < length) {
            val cp = Character.codePointAt(this, i)
            if (!test(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    private fun isLatinOrSymbol(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> true
        else -> false
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun typefaceFor(context: Context, resId: Int): android.graphics.Typeface? {
        // resId 0 = the custom font file, which changes on every import.
        val key = if (resId == 0) "custom:${AppFont.customVersion}" else "res:$resId"
        return bare.getOrPut(key) {
            try {
                val font = if (resId == 0) android.graphics.fonts.Font.Builder(AppFont.customFontFile(context)).build()
                else android.graphics.fonts.Font.Builder(context.resources, resId).build()
                android.graphics.Typeface.CustomFallbackBuilder(
                    android.graphics.fonts.FontFamily.Builder(font).build()
                ).build()
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Real-font rendering: starts at the classic glyph height and shrinks until it fits. */
@Composable
private fun FontDisplayText(
    text: String,
    family: FontFamily,
    weight: FontWeight,
    modifier: Modifier,
    pitch: Dp,
    color: Color,
    centered: Boolean,
    maxLines: Int,
    systemFallback: Boolean = false
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        // Fit the slot's height too, or tall fonts spill onto whatever sits below
        // (e.g. the keypad number over the "Add contact" pill).
        val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        // Capital letters are ~0.72 em tall; match the 7-dot classic glyph height.
        // Devanagari has no capitals and tall vowel signs, so it starts smaller
        // with more line height and no letter spacing (which splits conjuncts).
        val startSize = pitch.value * 7f / (if (systemFallback) 0.9f else 0.72f)
        fun styleAt(s: Float) = TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = s.sp,
            lineHeight = (s * (if (systemFallback) 1.4f else 1.1f)).sp,
            letterSpacing = if (systemFallback) 0.sp else (s * 0.04f).sp,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start
        )
        // Measured once per text and slot size, not on every recomposition.
        val size = remember(text, family, weight, startSize, maxWidth, maxHeight, maxLines, centered, systemFallback) {
            var fitted = startSize
            var tries = 0
            while (tries < 24) {
                val result = measurer.measure(
                    text = text,
                    style = styleAt(fitted),
                    maxLines = maxLines,
                    constraints = Constraints(maxWidth = maxWidth)
                )
                if (!result.hasVisualOverflow && result.size.height <= maxHeight) break
                fitted *= 0.9f
                tries++
            }
            fitted
        }
        Text(text = text, style = styleAt(size), color = color, maxLines = maxLines)
    }
}

/**
 * The original hand-drawn 5x7 dot-matrix display. Words wrap onto up to
 * [maxLines] lines, and the dot pitch shrinks to fit the available width. Text
 * the glyph set can't draw (e.g. Devanagari) goes to the system font instead.
 */
@Composable
private fun ClassicDotText(
    text: String,
    modifier: Modifier,
    pitch: Dp,
    color: Color,
    centered: Boolean,
    maxLines: Int
) {
    val normalized = DotMatrixFont.normalize(text)
    if (normalized.isBlank()) {
        Text(
            text = text,
            modifier = modifier,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = (pitch.value * 6f).sp,
            lineHeight = (pitch.value * 7.5f).sp,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            color = color,
            maxLines = maxLines
        )
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val maxWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.MAX_VALUE

        fun lineWidth(chars: Int, p: Float) = (chars * (DotMatrixFont.COLS + 1) - 1) * p

        fun wrap(p: Float): List<String> {
            val maxChars = ((maxWidthPx / p + 1) / (DotMatrixFont.COLS + 1)).toInt().coerceAtLeast(1)
            val lines = mutableListOf<String>()
            var current = ""
            for (word in normalized.trim().split(Regex("\\s+"))) {
                current = when {
                    current.isEmpty() -> word
                    current.length + 1 + word.length <= maxChars -> "$current $word"
                    else -> { lines += current; word }
                }
            }
            if (current.isNotEmpty()) lines += current
            return lines
        }

        var pitchPx = with(density) { pitch.toPx() }
        var lines = wrap(pitchPx)
        var attempts = 0
        while (attempts < 24 &&
            (lines.size > maxLines || lines.any { lineWidth(it.length, pitchPx) > maxWidthPx })
        ) {
            pitchPx *= 0.9f
            lines = wrap(pitchPx)
            attempts++
        }
        if (lines.size > maxLines) {
            // Still too many words: join everything onto the allowed lines.
            lines = listOf(normalized.trim())
            pitchPx = minOf(pitchPx, maxWidthPx / ((normalized.length * (DotMatrixFont.COLS + 1)) - 1).coerceAtLeast(1))
        }

        val lineGap = 2f
        val widthPx = lines.maxOf { lineWidth(it.length, pitchPx) }
        val heightPx = (lines.size * DotMatrixFont.ROWS + (lines.size - 1) * lineGap) * pitchPx
        val finalPitch = pitchPx
        val finalLines = lines

        Canvas(
            modifier = Modifier.size(
                width = with(density) { widthPx.toDp() },
                height = with(density) { heightPx.toDp() }
            )
        ) {
            val radius = finalPitch * 0.4f
            finalLines.forEachIndexed { lineIndex, line ->
                val x0 = if (centered) (size.width - lineWidth(line.length, finalPitch)) / 2f else 0f
                val y0 = lineIndex * (DotMatrixFont.ROWS + lineGap) * finalPitch
                line.forEachIndexed { charIndex, ch ->
                    val glyph = DotMatrixFont.glyph(ch) ?: return@forEachIndexed
                    for (row in 0 until DotMatrixFont.ROWS) {
                        val bits = glyph[row]
                        for (col in 0 until DotMatrixFont.COLS) {
                            if (bits[col] != '#') continue
                            drawCircle(
                                color = color,
                                radius = radius,
                                center = Offset(
                                    x0 + (charIndex * (DotMatrixFont.COLS + 1) + col) * finalPitch + finalPitch / 2f,
                                    y0 + row * finalPitch + finalPitch / 2f
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
