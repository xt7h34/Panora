package com.rigen.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/* In-app updates: asks GitHub for the newest release of this repo, downloads the APK, verifies it, opens the installer. */

class UpdateInfo(val code: Int, val name: String, val notes: String, val url: String, val size: Long, val sha: String?)

object Updater {
    fun installedCode(c: Context): Int =
        PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0)).toInt()

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) return@withContext null
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "PanoraGallery")
            if (conn.responseCode !in 200..299) return@withContext null
            parse(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): UpdateInfo? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (a.optString("name").endsWith(".apk") && url.startsWith("https://")) {
                return UpdateInfo(
                    code, o.optString("name").ifBlank { tag }, o.optString("body"), url, a.optLong("size"),
                    a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
                )
            }
        }
        return null
    }

    suspend fun download(c: Context, info: UpdateInfo, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(c.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "panora-${info.code}.apk")
        val conn = URL(info.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "PanoraGallery")
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    var n = input.read(buf)
                    while (n != -1) {
                        os.write(buf, 0, n); md.update(buf, 0, n); done += n
                        val now = System.nanoTime()
                        if (now - last > 100_000_000L) { last = now; onProgress(done, total) }
                        n = input.read(buf)
                    }
                    onProgress(done, total)
                }
            }
            val sum = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha != null && !sum.equals(info.sha, ignoreCase = true)) { out.delete(); throw IOException("checksum") }
            val pi = c.packageManager.getPackageArchiveInfo(out.absolutePath, 0)
            if (pi == null || pi.packageName != c.packageName || PackageInfoCompat.getLongVersionCode(pi) <= installedCode(c)) {
                out.delete(); throw IOException("not a newer version of this app")
            }
            out
        } finally {
            conn.disconnect()
        }
    }

    /** Returns false when the person still has to allow installs from this app. */
    fun install(c: Context, file: File): Boolean {
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", file)
        c.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var status by remember { mutableIntStateOf(0) } // 0 new, 1 downloading, 2 ready, 3 failed
    var progress by remember { mutableFloatStateOf(0f) }
    var file by remember { mutableStateOf<File?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val i = runCatching { Updater.latest() }.getOrNull()
        val skipped = ctx.getSharedPreferences("upd", 0).getInt("skip", 0)
        if (i != null && i.code > Updater.installedCode(ctx) && i.code != skipped) info = i
    }
    val i = info ?: return
    if (dismissed) return
    AlertDialog(
        onDismissRequest = { if (status != 1) dismissed = true },
        title = { Text("Update available") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i.name}" + if (i.size > 0) "  •  ${"%.1f".format(i.size / 1048576.0)} MB" else "")
                val notes = i.notes.replace(Regex("[#*`>]"), "").trim()
                if (notes.isNotBlank()) Text(notes)
                if (status == 1) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                if (status == 2) Text("If Android asked, allow installs from this app, then tap Install.")
                if (status == 3) Text("Download failed. Check your connection and try again.")
            }
        },
        confirmButton = {
            when (status) {
                1 -> {}
                2 -> TextButton(onClick = { file?.let { Updater.install(ctx, it) } }) { Text("Install") }
                else -> TextButton(onClick = {
                    scope.launch {
                        status = 1; progress = 0f
                        try {
                            val f = Updater.download(ctx, i) { d, t -> progress = if (t > 0) d.toFloat() / t else 0f }
                            file = f; status = 2
                            Updater.install(ctx, f)
                        } catch (e: Exception) { status = 3 }
                    }
                }) { Text("Download & install") }
            }
        },
        dismissButton = {
            if (status != 1) Row {
                TextButton(onClick = { ctx.getSharedPreferences("upd", 0).edit().putInt("skip", i.code).apply(); dismissed = true }) { Text("Skip") }
                TextButton(onClick = { dismissed = true }) { Text("Later") }
            }
        },
    )
}
