package com.example.allinonedailer.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.database.ContentObserver
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Black-and-white filter used for every caller photo. */
val GrayscaleFilter: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Decoded contact photos, shared by every list so scrolling back doesn't decode
 * again. Keyed by URI and target size; bounded by bytes (1/16 of the heap).
 */
private object PhotoCache {
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    // Uris that had no readable photo, so they aren't retried on every bind.
    private val missing = LruCache<String, Boolean>(512)

    // A contact's photo URI stays the same when the photo is edited, so any
    // contacts change drops what is cached.
    @Volatile
    private var observing = false

    private fun observe(context: Context) {
        if (observing) return
        observing = true
        try {
            context.applicationContext.contentResolver.registerContentObserver(
                ContactsContract.AUTHORITY_URI,
                true,
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        cache.evictAll()
                        missing.evictAll()
                    }
                }
            )
        } catch (e: Exception) {
            observing = false
        }
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun isMissing(key: String) = missing.get(key) != null

    fun load(context: Context, photoUri: String, key: String, maxPx: Int): ImageBitmap? {
        observe(context)
        val bitmap = try {
            decodeSampled(context, Uri.parse(photoUri), maxPx)
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) {
            missing.put(key, true)
            return null
        }
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }

    /** Decodes at the smallest power-of-two scale still at least [maxPx] on the short side. */
    private fun decodeSampled(context: Context, uri: Uri, maxPx: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        while (shortSide > 0 && shortSide / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}

/**
 * Loads a contact photo off the main thread, downsampled to about [maxSize]
 * and cached; null while loading or if there is none.
 */
@Composable
fun rememberContactPhoto(photoUri: String?, maxSize: Dp = 96.dp): ImageBitmap? {
    val context = LocalContext.current
    val maxPx = with(LocalDensity.current) { maxSize.roundToPx() }.coerceAtLeast(1)
    val key = photoUri?.let { "$it@$maxPx" }
    // A cached photo shows on the first frame, with no placeholder flash while scrolling.
    val photo by produceState(initialValue = key?.let(PhotoCache::get), key) {
        val cached = key?.let(PhotoCache::get)
        value = cached
        if (photoUri == null || key == null || cached != null || PhotoCache.isMissing(key)) return@produceState
        value = withContext(Dispatchers.IO) { PhotoCache.load(context, photoUri, key, maxPx) }
    }
    return photo
}

/**
 * Caller avatar: the contact's photo in black and white when there is one,
 * otherwise the caller's initial (or trailing digit for bare numbers).
 * [pill] gives the wide oval used in lists; otherwise it is a circle.
 */
@Composable
fun CallerAvatar(
    name: String?,
    number: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    spam: Boolean = false,
    photoUri: String? = null,
    pill: Boolean = false
) {
    val photo = rememberContactPhoto(photoUri, maxSize = if (pill) size * 1.45f else size)
    val shape = if (pill) RoundedCornerShape(50) else CircleShape
    val width = if (pill) size * 1.45f else size
    val ring = if (spam) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .size(width = width, height = size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, ring, shape),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = GrayscaleFilter,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initialFor(name, number),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value / 2.4f).sp,
                color = if (spam) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

fun initialFor(name: String?, number: String): String {
    val n = name?.trim().orEmpty()
    if (n.isNotEmpty() && n.first().isLetter()) return n.first().uppercaseChar().toString()
    val digits = number.filter { it.isDigit() }
    return if (digits.isNotEmpty()) digits.last().toString() else "#"
}
