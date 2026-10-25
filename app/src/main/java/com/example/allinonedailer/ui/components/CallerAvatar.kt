package com.example.allinonedailer.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Black-and-white filter used for every caller photo. */
val GrayscaleFilter: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Loads a contact photo off the main thread; null while loading or if there is none. */
@Composable
fun rememberContactPhoto(photoUri: String?): ImageBitmap? {
    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(initialValue = null, photoUri) {
        value = if (photoUri == null) null else withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(photoUri))?.use {
                    BitmapFactory.decodeStream(it)?.asImageBitmap()
                }
            } catch (e: Exception) {
                null
            }
        }
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
    val photo = rememberContactPhoto(photoUri)
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
