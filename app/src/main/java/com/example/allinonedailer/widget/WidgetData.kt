package com.example.allinonedailer.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.example.allinonedailer.R
import com.example.allinonedailer.data.AppFont
import com.example.allinonedailer.data.AppFontStyle
import com.example.allinonedailer.data.CallLogEntry
import com.example.allinonedailer.data.CallLogRepository
import com.example.allinonedailer.data.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.ceil
import kotlin.math.max

data class WidgetFavourite(
    val name: String,
    val number: String,
    /** Black-and-white, circle-cropped contact photo; null shows the initial. */
    val photo: Bitmap?
)

data class WidgetRecent(
    val label: String,
    val number: String,
    val callType: Int,
    val time: String
)

data class WidgetSnapshot(
    val favourites: List<WidgetFavourite>,
    val recents: List<WidgetRecent>,
    /** The title drawn in the app's display font (Glance can't use custom fonts). */
    val title: Bitmap?,
    val canReadContacts: Boolean,
    val canReadCallLog: Boolean
)

/**
 * What the home-screen widget shows. [refresh] reloads it off the main thread;
 * the widget collects [state], so a live widget session redraws as soon as
 * new data lands.
 */
object WidgetData {
    const val MAX_FAVOURITES = 4
    const val MAX_RECENTS = 6
    private const val AVATAR_PX = 144

    private val _state = MutableStateFlow<WidgetSnapshot?>(null)
    val state: StateFlow<WidgetSnapshot?> = _state.asStateFlow()

    /**
     * Instant first frame for a cold widget: whatever Recents last loaded
     * (CallLogRepository's cache), with no favourites yet. Null if nothing is cached.
     */
    fun cachedSnapshot(context: Context): WidgetSnapshot? {
        val cached = CallLogRepository.lastResult
        if (cached.isEmpty()) return null
        return WidgetSnapshot(
            favourites = emptyList(),
            recents = toRecents(context, cached),
            title = null,
            canReadContacts = true,
            canReadCallLog = true
        )
    }

    suspend fun refresh(context: Context): WidgetSnapshot = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val canContacts = granted(app, Manifest.permission.READ_CONTACTS)
        val canCallLog = granted(app, Manifest.permission.READ_CALL_LOG)

        val favourites = if (!canContacts) emptyList() else
            ContactsRepository(app).loadContacts()
                .filter { it.starred }
                .take(MAX_FAVOURITES)
                .map { WidgetFavourite(it.name, it.phoneNumber, it.photoUri?.let { uri -> loadAvatar(app, uri) }) }

        // A full load also refreshes CallLogRepository.lastResult, so Recents
        // opens instantly next time.
        val recents = if (!canCallLog) emptyList() else toRecents(app, CallLogRepository(app).getCallLogs())

        WidgetSnapshot(favourites, recents, titleBitmap(app), canContacts, canCallLog).also {
            _state.value = it
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SIGNATURE, contentSignature(app)).apply()
        }
    }

    // ---- change detection for the content-trigger worker --------------------

    private const val PREFS = "dialer_widget"
    private const val KEY_SIGNATURE = "signature"

    /** True if starred contacts or the newest call changed since the last [refresh]. */
    fun hasChanged(context: Context): Boolean {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SIGNATURE, null)
        return saved != contentSignature(context)
    }

    /** Cheap fingerprint: starred contacts (name, photo) plus the newest call-log row. */
    private fun contentSignature(context: Context): String = buildString {
        try {
            if (granted(context, Manifest.permission.READ_CONTACTS)) {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME,
                        ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
                    ),
                    "${ContactsContract.Contacts.STARRED} = 1",
                    null,
                    "${ContactsContract.Contacts._ID} ASC"
                )?.use { c ->
                    while (c.moveToNext()) append(c.getString(0)).append(c.getString(1)).append(c.getString(2)).append('|')
                }
            }
            append('#')
            if (granted(context, Manifest.permission.READ_CALL_LOG)) {
                val uri = CallLog.Calls.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1").build()
                context.contentResolver.query(
                    uri, arrayOf(CallLog.Calls._ID, CallLog.Calls.DATE), null, null, "${CallLog.Calls.DATE} DESC"
                )?.use { c -> if (c.moveToFirst()) append(c.getString(0)).append(c.getLong(1)) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Newest calls, with back-to-back calls from the same number shown once. */
    private fun toRecents(context: Context, calls: List<CallLogEntry>): List<WidgetRecent> {
        val out = mutableListOf<WidgetRecent>()
        var lastDigits: String? = null
        for (call in calls) {
            val digits = call.number.filter(Char::isDigit).takeLast(10)
            if (digits.isNotEmpty() && digits == lastDigits) continue
            lastDigits = digits
            out += WidgetRecent(
                label = call.callerName ?: call.number,
                number = call.number,
                callType = call.callType,
                time = timeLabel(context, call.date.time)
            )
            if (out.size == MAX_RECENTS) break
        }
        return out
    }

    private fun timeLabel(context: Context, ms: Long): String = when {
        DateUtils.isToday(ms) -> DateFormat.getTimeFormat(context).format(Date(ms))
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.widget_yesterday)
        else -> DateUtils.formatDateTime(
            context, ms,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
        )
    }

    /** Contact photo, centre-cropped to a circle and turned black and white like CallerAvatar. */
    private fun loadAvatar(context: Context, photoUri: String): Bitmap? = try {
        context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { BitmapFactory.decodeStream(it) }
            ?.let { src ->
                val out = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
                val scale = AVATAR_PX.toFloat() / minOf(src.width, src.height)
                val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((AVATAR_PX - src.width * scale) / 2f, (AVATAR_PX - src.height * scale) / 2f)
                    })
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    this.shader = shader
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                }
                Canvas(out).drawCircle(AVATAR_PX / 2f, AVATAR_PX / 2f, AVATAR_PX / 2f, paint)
                src.recycle()
                out
            }
    } catch (e: Exception) {
        null
    }

    /** The widget title rendered with the display font chosen in Settings. */
    private fun titleBitmap(context: Context): Bitmap? = try {
        val typeface = when (AppFont.current(context)) {
            AppFontStyle.SYCHIOV_DOT, AppFontStyle.CLASSIC -> ResourcesCompat.getFont(context, R.font.sychiov_dot)
            AppFontStyle.DOTO -> ResourcesCompat.getFont(context, R.font.doto)
            AppFontStyle.MONO -> Typeface.MONOSPACE
            AppFontStyle.STANDARD -> Typeface.DEFAULT_BOLD
            AppFontStyle.CUSTOM -> AppFont.customFontFile(context).takeIf { it.length() > 0 }
                ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        } ?: Typeface.MONOSPACE
        val metrics = context.resources.displayMetrics
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, metrics)
            color = android.graphics.Color.WHITE
            letterSpacing = 0.06f
        }
        val text = context.getString(R.string.widget_title).uppercase()
        val fm = paint.fontMetrics
        val width = max(1, ceil(paint.measureText(text)).toInt())
        val height = max(1, ceil(fm.descent - fm.ascent).toInt())
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            density = metrics.densityDpi
            Canvas(this).drawText(text, 0f, -fm.ascent, paint)
        }
    } catch (e: Exception) {
        null
    }
}
