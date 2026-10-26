package com.example.allinonedailer.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** The app's own language, chosen under Settings → Language. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    HINDI("hi"),
    MARATHI("mr")
}

/**
 * Per-app language. On Android 13+ this is the system's per-app locale (the
 * same setting as Settings → Apps → NoDialer → Language), which also covers
 * the call screen and notifications. AppCompatDelegate.setApplicationLocales
 * would do the same, but only through an AppCompatActivity, and MainActivity
 * is a plain ComponentActivity. Before Android 13 the choice is kept in
 * SharedPreferences and applied to MainActivity in [wrap].
 */
object AppLanguageSetting {
    private const val PREFS = "app_language"
    private const val KEY = "tag"

    fun current(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
                .takeIf { !it.isEmpty }?.get(0)?.language.orEmpty()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        }
        return AppLanguage.entries.firstOrNull { it.tag.isNotEmpty() && it.tag == tag } ?: AppLanguage.SYSTEM
    }

    /** Switches language; the activity is recreated in the new language. */
    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(language.tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, language.tag).apply()
            activity.recreate()
        }
    }

    /** For attachBaseContext before Android 13, where the system can't do it. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}
