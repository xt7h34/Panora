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

data class Photo(val uri: Uri, val millis: Long, val size: Long, val bucket: String) {
    val day: LocalDate get() = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}

fun loadPhotos(ctx: Context): List<Photo> {
    val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val out = ArrayList<Photo>()
    val cols = arrayOf(
        MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.SIZE, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )
    ctx.contentResolver.query(base, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val taken = c.getLong(1)
            val millis = if (taken > 0) taken else c.getLong(2) * 1000
            out += Photo(ContentUris.withAppendedId(base, c.getLong(0)), millis, c.getLong(3), c.getString(4) ?: "Other")
        }
    }
    return out.sortedByDescending { it.millis }
}

fun fmtSize(b: Long): String = if (b >= 1_000_000_000L) "%.1fGB".format(b / 1e9) else "${b / 1_000_000}MB"

object Favs {
    fun load(ctx: Context): Set<String> = ctx.getSharedPreferences("favs", 0).getStringSet("u", emptySet())!!.toSet()
    fun save(ctx: Context, s: Set<String>) { ctx.getSharedPreferences("favs", 0).edit().putStringSet("u", s).apply() }
}

private val cache = LruCache<String, Bitmap>(300)

@Composable
fun Thumb(uri: Uri, px: Int, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, cached: Boolean = true) {
    val ctx = LocalContext.current
    val key = "$uri@$px"
    val bmp by produceState<Bitmap?>(if (cached) cache.get(key) else null, key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
                ?.also { if (cached) cache.put(key, it) }
        }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = scale)
    else Box(modifier.background(Color(0xFF8E8E93).copy(alpha = 0.25f)))
}

// ---------- Clean: near-duplicates (dHash) + blurry (Laplacian variance) ----------

class CleanResult(val dups: List<List<Photo>>, val blurry: List<Photo>)

private fun luma(c: Int) = (android.graphics.Color.red(c) * 299 + android.graphics.Color.green(c) * 587 + android.graphics.Color.blue(c) * 114) / 1000

fun analyze(ctx: Context, all: List<Photo>): CleanResult {
    val items = all.take(1500)
    val n = items.size
    val hashes = LongArray(n)
    val ok = BooleanArray(n)
    val blur = BooleanArray(n)
    for (i in 0 until n) {
        val bmp = runCatching { ctx.contentResolver.loadThumbnail(items[i].uri, Size(96, 96), null) }.getOrNull() ?: continue
        val g = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            h = h shl 1
            if (luma(g.getPixel(x, y)) < luma(g.getPixel(x + 1, y))) h = h or 1L
        }
        hashes[i] = h
        ok[i] = true
        val s = Bitmap.createScaledBitmap(bmp, 64, 64, true)
        val l = Array(64) { y -> IntArray(64) { x -> luma(s.getPixel(x, y)) } }
        var sum = 0.0; var sq = 0.0; var cnt = 0
        for (y in 1..62) for (x in 1..62) {
            val v = 4 * l[y][x] - l[y - 1][x] - l[y + 1][x] - l[y][x - 1] - l[y][x + 1]
            sum += v; sq += v.toDouble() * v; cnt++
        }
        val mean = sum / cnt
        blur[i] = (sq / cnt - mean * mean) < 40.0
    }
    val used = BooleanArray(n)
    val groups = ArrayList<List<Photo>>()
    for (i in 0 until n) {
        if (used[i] || !ok[i]) continue
        used[i] = true
        val g = arrayListOf(items[i])
        for (j in i + 1 until n) {
            if (!used[j] && ok[j] && java.lang.Long.bitCount(hashes[i] xor hashes[j]) <= 4) { used[j] = true; g += items[j] }
        }
        if (g.size > 1) groups += g
    }
    return CleanResult(groups, items.filterIndexed { i, _ -> blur[i] })
}
