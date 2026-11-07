@file:OptIn(ExperimentalFoundationApi::class)

package com.rigen.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle as TStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

private val LocalBg = compositionLocalOf { Color.White }
private val DarkBg = Color(0xFF111214)

private class Detail(val title: String, val photos: List<Photo>)

private fun share(ctx: android.content.Context, l: List<Photo>) {
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(l.map { it.uri }))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, null))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val sysDark = isSystemInDarkTheme()
    val bg = if (sysDark) DarkBg else Color.White
    val fg = if (sysDark) Color.White else Color.Black
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    var photos by remember { mutableStateOf<List<Photo>?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var detailOpen by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<Photo>, Int>?>(null) }
    var viewerOpen by remember { mutableStateOf(false) }
    var cleanOpen by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Uri>()) }
    var favs by remember { mutableStateOf(Favs.load(ctx)) }
    val list = photos ?: emptyList()

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        photos = if (ok) loadPhotos(ctx) else emptyList()
    }
    LaunchedEffect(Unit) { ask.launch(perm) }

    fun reset() {
        selecting = false; selected = emptySet(); viewerOpen = false; cleanOpen = false; detailOpen = false
        photos = loadPhotos(ctx)
    }
    val del = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) reset()
    }
    fun delete(l: List<Photo>) {
        if (l.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, l.map { it.uri })
            del.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else Toast.makeText(ctx, "Delete needs Android 11+", Toast.LENGTH_SHORT).show()
    }
    fun toggleFav(p: Photo) {
        val k = p.uri.toString()
        favs = if (k in favs) favs - k else favs + k
        Favs.save(ctx, favs)
    }

    BackHandler(detailOpen) { detailOpen = false }
    BackHandler(selecting) { selecting = false; selected = emptySet() }
    BackHandler(cleanOpen) { cleanOpen = false }
    BackHandler(viewerOpen) { viewerOpen = false }

    val onTap: (List<Photo>, Int) -> Unit = { l, i ->
        if (selecting) {
            val u = l[i].uri
            selected = if (u in selected) selected - u else selected + u
        } else { viewer = l to i; viewerOpen = true }
    }
    val onLong: (Photo) -> Unit = { p -> selecting = true; selected = selected + p.uri }
    val open: (String, List<Photo>) -> Unit = { t, p -> detail = Detail(t, p); detailOpen = true }

    CompositionLocalProvider(LocalBg provides bg, LocalContentColor provides fg) {
        Box(Modifier.fillMaxSize().background(if (tab == 2) DarkBg else bg)) {
            when {
                photos == null -> Unit
                list.isEmpty() -> Text("No photos / permission denied", Modifier.align(Alignment.Center))
                tab == 0 -> HomeScreen(list, open) { cleanOpen = true }
                tab == 1 -> PhotosTab(list, favs, selected, onTap, onLong)
                else -> MonthsScreen(list, open)
            }
            if (!selecting) NavPill(tab, sysDark || tab == 2, Modifier.align(Alignment.BottomCenter)) { tab = it }

            AnimatedVisibility(detailOpen, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
                detail?.let { d ->
                    Box(Modifier.fillMaxSize().background(bg)) {
                        DetailScreen(d.title, d.photos, selected, selecting,
                            onTap = { i -> onTap(d.photos, i) }, onLong = { i -> onLong(d.photos[i]) },
                            onSelect = { selecting = !selecting; if (!selecting) selected = emptySet() },
                            back = { detailOpen = false })
                    }
                }
            }

            if (selecting && !viewerOpen && !cleanOpen) {
                Pill(Modifier.align(Alignment.BottomCenter), sysDark) {
                    val chosen = list.filter { it.uri in selected }
                    PillItem("${selected.size} selected", false) {}
                    PillItem("Share", true) { if (chosen.isNotEmpty()) share(ctx, chosen) }
                    PillItem("Delete", true) { delete(chosen) }
                    PillItem("Cancel", true) { selecting = false; selected = emptySet() }
                }
            }

            AnimatedVisibility(cleanOpen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Box(Modifier.fillMaxSize().background(bg)) {
                    CleanScreen(list, back = { cleanOpen = false }, onDelete = { delete(it) })
                }
            }

            AnimatedVisibility(viewerOpen, enter = fadeIn() + scaleIn(initialScale = 0.92f), exit = fadeOut() + scaleOut(targetScale = 0.92f)) {
                viewer?.let { (ps, start) ->
                    key(ps, start) {
                        Viewer(ps, start, favs, { toggleFav(it) }, { share(ctx, listOf(it)) }, { delete(listOf(it)) }) { viewerOpen = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, trailing: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(trailing, color = Color.Gray, fontSize = 16.sp)
        if (action != null) { Spacer(Modifier.width(10.dp)); action() }
    }
}

@Composable
private fun Chip(label: String, on: Boolean = false, onClick: () -> Unit) {
    val fg = LocalContentColor.current
    Text(
        label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(CircleShape).background(fg.copy(if (on) 0.2f else 0.08f)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun dateText(d: LocalDate) = buildAnnotatedString {
    append("${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ")
    withStyle(SpanStyle(color = Color(0xFFB5B5B5))) { append("${d.year}") }
}

@Composable
private fun HomeScreen(all: List<Photo>, open: (String, List<Photo>) -> Unit, onClean: () -> Unit) {
    val days = remember(all) { all.groupBy { it.day }.toList() }
    val top = remember(days) { days.sortedByDescending { it.second.size }.take(6) }
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { Header("Home", "${all.size}") { Chip("Clean ✨") { onClean() } } }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(top) { (day, ps) ->
                    Box(Modifier.width(260.dp).height(360.dp).clip(RoundedCornerShape(28.dp)).clickable { open(day.toString(), ps) }) {
                        Thumb(ps.first().uri, 600, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)), startY = 400f)))
                        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                            Text(dateText(day), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Text("${ps.size} • ${fmtSize(ps.sumOf { it.size })}", color = Color.White.copy(0.85f), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)); Header("Recent", "${days.size}") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(days.take(20)) { (day, ps) ->
                    Column(Modifier.width(110.dp).clickable { open(day.toString(), ps) }) {
                        Thumb(ps.first().uri, 300, Modifier.height(160.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)))
                        Spacer(Modifier.height(6.dp))
                        Text(dateText(day), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${ps.size} • ${fmtSize(ps.sumOf { it.size })}", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotosTab(
    all: List<Photo>, favs: Set<String>, selected: Set<Uri>,
    onTap: (List<Photo>, Int) -> Unit, onLong: (Photo) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) }
    val fg = LocalContentColor.current
    val buckets = remember(all) { all.groupingBy { it.bucket }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val shown = remember(all, q, folder, favs) {
        val s = q.trim().lowercase()
        all.filter { p ->
            (folder == null || (folder == "★" && p.uri.toString() in favs) || p.bucket == folder) &&
                (s.isEmpty() || "${p.day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${p.day.year} ${p.day.dayOfMonth} ${p.bucket}".lowercase().contains(s))
        }
    }
    Column(Modifier.statusBarsPadding()) {
        Header("All Images", "${shown.size}")
        BasicTextField(
            q, { q = it }, singleLine = true,
            textStyle = TStyle(color = fg, fontSize = 15.sp), cursorBrush = SolidColor(fg),
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(fg.copy(0.07f)).padding(14.dp),
            decorationBox = { inner ->
                Box { if (q.isEmpty()) Text("Search: oct 2025, Camera…", color = Color.Gray, fontSize = 15.sp); inner() }
            },
        )
        LazyRow(contentPadding = PaddingValues(20.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("All", folder == null) { folder = null } }
            item { Chip("★ Favorites", folder == "★") { folder = "★" } }
            items(buckets) { b -> Chip(b, folder == b) { folder = b } }
        }
        PhotoGrid(shown, selected, { onTap(shown, it) }, { onLong(shown[it]) }, Modifier.weight(1f))
    }
}

@Composable
private fun PhotoGrid(
    ps: List<Photo>, selected: Set<Uri>, onTap: (Int) -> Unit, onLong: (Int) -> Unit,
    modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null,
) {
    LazyVerticalGrid(
        GridCells.Fixed(3), modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        itemsIndexed(ps, key = { _, p -> p.uri.toString() }) { i, p ->
            Box(Modifier.aspectRatio(0.72f).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = { onTap(i) }, onLongClick = { onLong(i) })) {
                Thumb(p.uri, 300, Modifier.fillMaxSize())
                if (p.uri in selected) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.3f)))
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(Color(0xFF2F80ED)), contentAlignment = Alignment.Center) {
                        Text("✓", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(
    title: String, ps: List<Photo>, selected: Set<Uri>, selecting: Boolean,
    onTap: (Int) -> Unit, onLong: (Int) -> Unit, onSelect: () -> Unit, back: () -> Unit,
) {
    val bg = LocalBg.current
    Box(Modifier.fillMaxSize()) {
        PhotoGrid(ps, selected, onTap, onLong) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                Thumb(ps.first().uri, 400, Modifier.fillMaxSize().blur(24.dp).alpha(0.9f))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, bg))))
                Text(title, Modifier.align(Alignment.BottomStart).padding(bottom = 12.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${ps.size}", Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp), color = Color.Gray)
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.85f)).clickable { back() }, contentAlignment = Alignment.Center) {
                Text("‹", fontSize = 26.sp, color = Color.Black)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (selecting) "Done" else "Select", color = Color.Black, fontSize = 14.sp,
                modifier = Modifier.clip(CircleShape).background(Color.White.copy(0.85f)).clickable { onSelect() }.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun MonthsScreen(all: List<Photo>, open: (String, List<Photo>) -> Unit) {
    val months = remember(all) { all.groupBy { YearMonth.from(it.day) }.toList() }
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(months) { (ym, ps) ->
            val name = ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(28.dp)).clickable { open("$name ${ym.year}", ps) }) {
                Thumb(ps.first().uri, 700, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
                Text("${ps.size} photos", Modifier.padding(14.dp).clip(CircleShape).background(Color.White.copy(0.2f)).padding(horizontal = 12.dp, vertical = 5.dp), color = Color.White, fontSize = 12.sp)
                Text(name, Modifier.align(Alignment.BottomStart).padding(start = 14.dp), color = Color.White.copy(0.75f), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ZoomImage(uri: Uri, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val zoom = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        if (zoom != 1f || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            off = if (scale > 1f) off + pan else Offset.Zero
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        Thumb(
            uri, 1600,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y },
            ContentScale.Fit, cached = false,
        )
    }
}

@Composable
private fun Viewer(
    ps: List<Photo>, start: Int, favs: Set<String>,
    onFav: (Photo) -> Unit, onShare: (Photo) -> Unit, onDelete: (Photo) -> Unit, onClose: () -> Unit,
) {
    val pager = rememberPagerState(start) { ps.size }
    var chrome by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
            ZoomImage(ps[i].uri) { chrome = !chrome }
        }
        if (chrome) {
            val p = ps[pager.currentPage]
            val isFav = p.uri.toString() in favs
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.2f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
                    Text("‹", fontSize = 26.sp, color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text("${pager.currentPage + 1} / ${ps.size}", color = Color.White.copy(0.8f), fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.2f)).clickable { onFav(p) }, contentAlignment = Alignment.Center) {
                    Text(if (isFav) "♥" else "♡", fontSize = 20.sp, color = if (isFav) Color(0xFFFF5A6E) else Color.White)
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
                    .clip(CircleShape).background(Color.White.copy(0.18f)).padding(6.dp),
            ) {
                listOf("Share" to { onShare(p) }, "Delete" to { onDelete(p) }).forEach { (l, a) ->
                    Text(l, color = Color.White, fontSize = 14.sp, modifier = Modifier.clip(CircleShape).clickable { a() }.padding(horizontal = 22.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun CleanScreen(all: List<Photo>, back: () -> Unit, onDelete: (List<Photo>) -> Unit) {
    val ctx = LocalContext.current
    val res by produceState<CleanResult?>(null) { value = withContext(Dispatchers.Default) { analyze(ctx, all) } }
    val r = res
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header("Clean ✨", "") { Chip("Close") { back() } }
        if (r == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Scanning your photos…") }
        } else {
            val byUri = remember(r) { (r.dups.flatten() + r.blurry).associateBy { it.uri } }
            var chosen by remember(r) { mutableStateOf(r.dups.flatMap { it.drop(1) }.map { it.uri }.toSet() + r.blurry.map { it.uri }) }
            @Composable
            fun Cell(p: Photo) {
                val on = p.uri in chosen
                Box(Modifier.size(92.dp).clip(RoundedCornerShape(14.dp)).clickable { chosen = if (on) chosen - p.uri else chosen + p.uri }) {
                    Thumb(p.uri, 250, Modifier.fillMaxSize())
                    if (on) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.35f)))
                        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(Color(0xFF2F80ED)), contentAlignment = Alignment.Center) {
                            Text("✓", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Duplicates • ${r.dups.size} groups", fontWeight = FontWeight.SemiBold, fontSize = 18.sp) }
                if (r.dups.isEmpty()) item { Text("None found", color = Color.Gray) }
                items(r.dups) { g ->
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(g) { Cell(it) } }
                }
                item { Spacer(Modifier.height(8.dp)); Text("Blurry • ${r.blurry.size}", fontWeight = FontWeight.SemiBold, fontSize = 18.sp) }
                if (r.blurry.isEmpty()) item { Text("None found", color = Color.Gray) }
                items(r.blurry.chunked(3)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { Cell(it) } }
                }
            }
            val picked = chosen.mapNotNull { byUri[it] }
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (picked.isEmpty()) "Nothing selected" else "Delete ${picked.size} • ${fmtSize(picked.sumOf { it.size })}",
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(if (picked.isEmpty()) Color.Gray else Color(0xFFE5484D))
                        .clickable(enabled = picked.isNotEmpty()) { onDelete(picked) }.padding(horizontal = 28.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun Pill(modifier: Modifier, dark: Boolean, content: @Composable RowScope.() -> Unit) {
    val bg = if (dark) Color(0xEE2A2B2E) else Color(0xF2F3F3F5)
    Row(modifier.navigationBarsPadding().padding(bottom = 12.dp).clip(CircleShape).background(bg).padding(6.dp), content = content)
}

@Composable
private fun PillItem(label: String, clickable: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(CircleShape).then(if (clickable) Modifier.clickable { onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun NavPill(sel: Int, dark: Boolean, modifier: Modifier, onSel: (Int) -> Unit) {
    val fg = if (dark) Color.White else Color.Black
    Pill(modifier, dark) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            listOf("Home", "Photos", "Months").forEachIndexed { i, label ->
                Text(
                    label, color = fg.copy(if (i == sel) 1f else 0.5f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(CircleShape).background(if (i == sel) fg.copy(0.12f) else Color.Transparent)
                        .clickable { onSel(i) }.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}
