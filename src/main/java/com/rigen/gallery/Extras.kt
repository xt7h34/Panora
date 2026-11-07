package com.rigen.gallery

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Share downsized JPEG copies (re-encoding also drops EXIF/GPS). */
fun shareSmall(ctx: Context, l: List<Photo>) {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val uris = ArrayList<Uri>()
    l.forEachIndexed { i, p ->
        val bmp = runCatching { ctx.contentResolver.loadThumbnail(p.uri, Size(1280, 1280), null) }.getOrNull() ?: return@forEachIndexed
        val f = File(dir, "img_$i.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        uris += FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }
    if (uris.isEmpty()) return
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, null))
}

@Composable
fun InfoCard(p: Photo, modifier: Modifier, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lines by produceState(listOf<String>(), p) {
        value = withContext(Dispatchers.IO) {
            val l = ArrayList<String>()
            l.add(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").format(Instant.ofEpochMilli(p.millis).atZone(ZoneId.systemDefault())))
            l.add("${fmtSize(p.size)} • ${p.bucket}")
            runCatching {
                ctx.contentResolver.openInputStream(p.uri)?.use { s ->
                    val e = ExifInterface(s)
                    val w = e.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
                    val h = e.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)
                    if (w != null && h != null) l.add("$w × $h")
                    val cam = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ")
                    if (cam.isNotBlank()) l.add(cam)
                    val ex = listOfNotNull(
                        e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                        e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { "${it}s" },
                        e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
                    ).joinToString("  ")
                    if (ex.isNotBlank()) l.add(ex)
                }
            }
            l
        }
    }
    Column(
        modifier.padding(16.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE61C1C1E)).clickable { onClose() }.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { lines.forEach { Text(it, color = Color.White, fontSize = 14.sp) } }
}

@Composable
private fun Entry(title: String, ps: List<Photo>, open: (String, List<Photo>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalContentColor.current.copy(0.07f))
            .clickable(enabled = ps.isNotEmpty()) { open(title, ps) }.padding(16.dp),
    ) {
        Text(title, Modifier.weight(1f))
        Text("${ps.size} • ${fmtSize(ps.sumOf { it.size })}", color = Color.Gray)
    }
}

@Composable
fun StorageScreen(all: List<Photo>, back: () -> Unit, open: (String, List<Photo>) -> Unit) {
    val total = remember(all) { all.sumOf { it.size } }
    val folders = remember(all) { all.groupBy { it.bucket }.map { (k, v) -> Triple(k, v.size, v.sumOf { it.size }) }.sortedByDescending { it.third }.take(8) }
    val shots = remember(all) { all.filter { it.bucket.contains("screenshot", true) } }
    val wa = remember(all) { all.filter { it.bucket.startsWith("WhatsApp", true) } }
    val big = remember(all) { all.sortedByDescending { it.size }.take(30) }
    val fg = LocalContentColor.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header("Storage", fmtSize(total)) { Chip("Close") { back() } }
        LazyColumn(contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("${all.size} photos", color = Color.Gray) }
            items(folders) { (name, cnt, sz) ->
                Column {
                    Row { Text(name, Modifier.weight(1f)); Text("$cnt • ${fmtSize(sz)}", color = Color.Gray, fontSize = 13.sp) }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(fg.copy(0.1f))) {
                        Box(Modifier.fillMaxWidth(sz.toFloat() / total.coerceAtLeast(1)).fillMaxHeight().clip(CircleShape).background(Color(0xFF2F80ED)))
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)); Text("Free up space", fontWeight = FontWeight.SemiBold, fontSize = 18.sp) }
            item { Entry("Screenshots", shots, open) }
            item { Entry("WhatsApp images", wa, open) }
            item { Entry("Largest 30", big, open) }
        }
    }
}

@Composable
fun TrashScreen(trash: List<Photo>, back: () -> Unit, onRestore: (List<Photo>) -> Unit, onDelete: (List<Photo>) -> Unit) {
    var sel by remember { mutableStateOf(setOf<Uri>()) }
    val tgt = if (sel.isEmpty()) trash else trash.filter { it.uri in sel }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header("Trash", "${trash.size}") { Chip("Close") { back() } }
        Text("Items stay here for about 30 days, then Android removes them.", Modifier.padding(horizontal = 20.dp), color = Color.Gray, fontSize = 13.sp)
        if (trash.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Trash is empty") }
        } else {
            PhotoGrid(
                trash, sel,
                { i -> val u = trash[i].uri; sel = if (u in sel) sel - u else sel + u },
                { i -> sel = sel + trash[i].uri },
                Modifier.weight(1f),
            )
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                Chip("Restore ${tgt.size}") { onRestore(tgt) }
                Spacer(Modifier.width(10.dp))
                Chip("Delete forever") { onDelete(tgt) }
            }
        }
    }
}

private val filterNames = listOf("Original", "B&W", "Sepia", "Vivid", "Warm", "Cool")

private fun colorArray(f: Int, br: Float, ct: Float): FloatArray {
    val m = ColorMatrix()
    when (f) {
        1 -> m.setSaturation(0f)
        2 -> m.set(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        3 -> m.setSaturation(1.6f)
        4 -> m.setScale(1.1f, 1f, 0.9f, 1f)
        5 -> m.setScale(0.9f, 1f, 1.1f, 1f)
    }
    val t = 128f * (1f - ct) + br
    m.postConcat(ColorMatrix(floatArrayOf(ct, 0f, 0f, 0f, t, 0f, ct, 0f, 0f, t, 0f, 0f, ct, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    return m.array
}

private fun saveEdited(ctx: Context, src: Bitmap, ratio: Float?, cm: FloatArray): Uri? {
    var b = src
    if (ratio != null) {
        val cur = b.width.toFloat() / b.height
        val w = if (cur > ratio) (b.height * ratio).toInt() else b.width
        val h = if (cur > ratio) b.height else (b.width / ratio).toInt()
        if (w > 0 && h > 0) b = Bitmap.createBitmap(b, (b.width - w) / 2, (b.height - h) / 2, w, h)
    }
    val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(cm)) })
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "EDIT_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Edited")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val r = ctx.contentResolver
    val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
    r.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
    r.update(uri, cv, null, null)
    return uri
}

@Composable
fun EditScreen(p: Photo, back: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var ratio by remember { mutableStateOf<Float?>(null) }
    var f by remember { mutableIntStateOf(0) }
    var br by remember { mutableFloatStateOf(0f) }
    var ct by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(p) {
        bmp = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(p.uri, Size(2048, 2048), null) }.getOrNull() }
    }
    val cm = remember(f, br, ct) { colorArray(f, br, ct) }
    val ratios = listOf<Pair<String, Float?>>("Original" to null, "1:1" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header("Edit", "") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Cancel") { back() }
                Chip("Save") {
                    val b = bmp
                    if (b != null) scope.launch {
                        val u = withContext(Dispatchers.IO) { saveEdited(ctx, b, ratio, cm) }
                        Toast.makeText(ctx, if (u != null) "Saved to Pictures/Edited" else "Save failed", Toast.LENGTH_SHORT).show()
                        if (u != null) onSaved()
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b == null) Text("Loading…") else Image(
                b.asImageBitmap(), null,
                Modifier.aspectRatio(ratio ?: (b.width.toFloat() / b.height), true).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(cm)),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Chip("⟳ Rotate") {
                    bmp = bmp?.let { Bitmap.createBitmap(it, 0, 0, it.width, it.height, Matrix().apply { postRotate(90f) }, true) }
                }
            }
            items(ratios) { (n, r) -> Chip(n, ratio == r) { ratio = r } }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filterNames.size) { i -> Chip(filterNames[i], f == i) { f = i } }
        }
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Brightness", fontSize = 13.sp, color = Color.Gray)
            Slider(br, { br = it }, valueRange = -60f..60f)
            Text("Contrast", fontSize = 13.sp, color = Color.Gray)
            Slider(ct, { ct = it }, valueRange = 0.6f..1.6f)
        }
    }
}
