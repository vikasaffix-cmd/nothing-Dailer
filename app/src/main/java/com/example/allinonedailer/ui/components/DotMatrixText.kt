package com.example.allinonedailer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * Renders [text] as a dot-matrix display. Words wrap onto up to [maxLines]
 * lines, and the dot pitch shrinks to fit the available width. Text the glyph
 * set can't draw (e.g. Devanagari names) falls back to a monospace [Text].
 */
@Composable
fun DotMatrixText(
    text: String,
    modifier: Modifier = Modifier,
    pitch: Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.onBackground,
    centered: Boolean = false,
    maxLines: Int = 1
) {
    val normalized = DotMatrixFont.normalize(text)
    if (normalized.isBlank() || !DotMatrixFont.supports(normalized)) {
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
