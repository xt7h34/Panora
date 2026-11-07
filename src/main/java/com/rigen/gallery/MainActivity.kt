package com.rigen.gallery

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private class Detail(val title: String, val photos: List<Photo>)

@Composable
fun App() {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var photos by remember { mutableStateOf<List<Photo>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        photos = if (ok) loadPhotos(ctx) else emptyList()
    }
    LaunchedEffect(Unit) { ask.launch(perm) }

    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    BackHandler(detail != null) { detail = null }
    val dark = tab == 2 && detail == null
    val list = photos ?: emptyList()

    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF111214) else Color.White)) {
        val d = detail
        when {
            photos == null -> Unit
            list.isEmpty() -> Text("No photos / permission denied", Modifier.align(Alignment.Center))
            d != null -> DetailScreen(d.title, d.photos) { detail = null }
            tab == 0 -> HomeScreen(list) { t, p -> detail = Detail(t, p) }
            tab == 1 -> Column(Modifier.statusBarsPadding()) {
                Header("All Images", "${list.size}")
                PhotoGrid(list, Modifier.weight(1f))
            }
            else -> MonthsScreen(list) { t, p -> detail = Detail(t, p) }
        }
        if (d == null) NavPill(tab, dark, Modifier.align(Alignment.BottomCenter)) { tab = it }
    }
}

@Composable
private fun Header(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(trailing, color = Color.Gray, fontSize = 16.sp)
    }
}

private fun dateText(d: LocalDate) = buildAnnotatedString {
    append("${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ")
    withStyle(SpanStyle(color = Color(0xFFB5B5B5))) { append("${d.year}") }
}

@Composable
private fun HomeScreen(all: List<Photo>, open: (String, List<Photo>) -> Unit) {
    val days = remember(all) { all.groupBy { it.day }.toList() }
    val top = remember(days) { days.sortedByDescending { it.second.size }.take(6) }
    LazyColumn(Modifier.statusBarsPadding(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { Header("Home", "${all.size}") }
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
private fun PhotoGrid(ps: List<Photo>, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    LazyVerticalGrid(
        GridCells.Fixed(3), modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(ps, key = { it.uri.toString() }) { p ->
            Thumb(p.uri, 300, Modifier.aspectRatio(0.72f).clip(RoundedCornerShape(16.dp)))
        }
    }
}

@Composable
private fun DetailScreen(title: String, ps: List<Photo>, back: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        PhotoGrid(ps) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                Thumb(ps.first().uri, 400, Modifier.fillMaxSize().blur(24.dp).alpha(0.9f))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.White))))
                Text(title, Modifier.align(Alignment.BottomStart).padding(bottom = 12.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${ps.size}", Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp), color = Color.Gray)
            }
        }
        Box(
            Modifier.statusBarsPadding().padding(16.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(0.85f)).clickable { back() },
            contentAlignment = Alignment.Center,
        ) { Text("‹", fontSize = 26.sp) }
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
                Text(name, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 0.dp), color = Color.White.copy(0.75f), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NavPill(sel: Int, dark: Boolean, modifier: Modifier, onSel: (Int) -> Unit) {
    val bg = if (dark) Color(0xCC2A2B2E) else Color(0xE6F3F3F5)
    val fg = if (dark) Color.White else Color.Black
    Row(modifier.navigationBarsPadding().padding(bottom = 12.dp).clip(CircleShape).background(bg).padding(6.dp)) {
        listOf("Home", "Photos", "Months").forEachIndexed { i, label ->
            Text(
                label, color = fg.copy(if (i == sel) 1f else 0.5f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(CircleShape).background(if (i == sel) fg.copy(0.12f) else Color.Transparent)
                    .clickable { onSel(i) }.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}
