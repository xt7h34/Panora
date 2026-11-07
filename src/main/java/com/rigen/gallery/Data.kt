package com.rigen.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*

data class Photo(val uri: Uri, val millis: Long, val size: Long) {
    val day: LocalDate get() = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}

fun loadPhotos(ctx: Context): List<Photo> {
    val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val out = ArrayList<Photo>()
    ctx.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE), null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val taken = c.getLong(1)
            val millis = if (taken > 0) taken else c.getLong(2) * 1000
            out += Photo(ContentUris.withAppendedId(base, c.getLong(0)), millis, c.getLong(3))
        }
    }
    return out.sortedByDescending { it.millis }
}

fun fmtSize(b: Long): String = if (b >= 1_000_000_000L) "%.1fGB".format(b / 1e9) else "${b / 1_000_000}MB"

private val cache = LruCache<String, Bitmap>(200)

@Composable
fun Thumb(uri: Uri, px: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val key = "$uri@$px"
    val bmp by produceState<Bitmap?>(cache.get(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
                ?.also { cache.put(key, it) }
        }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Color(0xFFE9E9EC)))
}
