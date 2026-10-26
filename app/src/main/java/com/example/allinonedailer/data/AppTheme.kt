package com.example.allinonedailer.data

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.allinonedailer.R

/** Settings → Theme. Dark is the default look. */
enum class ThemeMode(@StringRes val label: Int) {
    DARK(R.string.theme_dark),
    LIGHT(R.string.theme_light),
    SYSTEM(R.string.theme_system)
}

/**
 * The chosen theme, kept as Compose state (like [AppFont]) so every screen
 * redraws as soon as it changes. The app's night mode is set too, so system
 * dialogs, pickers and the window background match.
 */
object AppTheme {
    const val PREFS = "app_theme"
    const val KEY = "mode"

    private var loaded = false
    var mode by mutableStateOf(ThemeMode.DARK)
        private set

    fun current(context: Context): ThemeMode {
        if (!loaded) {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            mode = ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.DARK
            loaded = true
        }
        return mode
    }

    fun set(context: Context, newMode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, newMode.name).apply()
        mode = newMode
        loaded = true
        applyNightMode(context)
    }

    /** Re-reads the saved choice, e.g. after a backup is restored. Main thread. */
    fun reload(context: Context) {
        loaded = false
        current(context)
        applyNightMode(context)
    }

    /**
     * Tells Android which mode the app is in. 12+ keeps it per app (and MainActivity
     * handles uiMode changes itself, so nothing is recreated); older versions only
     * have AppCompat's setting.
     */
    fun applyNightMode(context: Context) {
        val mode = current(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val uiMode = context.getSystemService(UiModeManager::class.java) ?: return
            uiMode.setApplicationNightMode(
                when (mode) {
                    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                }
            )
        }
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
