package com.example.allinonedailer.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import androidx.annotation.StringRes
import com.example.allinonedailer.R

/** The display font used for names, numbers, keypad digits and titles. */
enum class AppFontStyle(@StringRes val label: Int, @StringRes val description: Int) {
    SYCHIOV_DOT(R.string.font_dot_matrix, R.string.font_dot_matrix_summary),
    DOTO(R.string.font_doto, R.string.font_doto_summary),
    CLASSIC(R.string.font_classic, R.string.font_classic_summary),
    MONO(R.string.font_mono, R.string.font_mono_summary),
    STANDARD(R.string.font_standard, R.string.font_standard_summary),
    CUSTOM(R.string.font_custom, R.string.font_custom_summary)
}

/**
 * The chosen display font, kept as Compose state so every screen redraws as
 * soon as it changes in Settings. Loaded lazily from SharedPreferences.
 */
object AppFont {
    private const val PREFS = "display_font"
    private const val KEY = "style"

    private var loaded = false
    var style by mutableStateOf(AppFontStyle.SYCHIOV_DOT)
        private set

    fun current(context: Context): AppFontStyle {
        if (!loaded) {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            style = AppFontStyle.entries.firstOrNull { it.name == saved } ?: AppFontStyle.SYCHIOV_DOT
            loaded = true
        }
        return style
    }

    fun set(context: Context, newStyle: AppFontStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, newStyle.name).apply()
        style = newStyle
        loaded = true
    }

    /** Bumped on every import so screens reload the custom font file. */
    var customVersion by mutableStateOf(0)
        private set

    /** Re-reads the saved choice and the custom font file, e.g. after a backup is restored. Main thread. */
    fun reload(context: Context) {
        loaded = false
        current(context)
        customVersion++
    }

    /** The user's own font file, kept in app-private storage (never bundled or shared). */
    fun customFontFile(context: Context): File = File(context.filesDir, "custom_display_font")

    fun hasCustomFont(context: Context): Boolean = customFontFile(context).length() > 0

    /**
     * Copies the chosen font into app storage and switches to it. Returns false
     * (and keeps the current font) if the file isn't a font Android can load.
     * Call off the main thread.
     */
    fun importCustomFont(context: Context, uri: Uri): Boolean {
        val target = customFontFile(context)
        val temp = File(context.filesDir, "custom_display_font.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: return false
            Typeface.createFromFile(temp) // throws if it isn't a valid font
            temp.renameTo(target.also { it.delete() })
            customVersion++
            set(context, AppFontStyle.CUSTOM)
            true
        } catch (e: Exception) {
            temp.delete()
            false
        }
    }
}
