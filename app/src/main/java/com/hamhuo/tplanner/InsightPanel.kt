package com.hamhuo.tplanner

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// 回顾页：不再有"焦虑统计/认知扭曲分布/强度"，改为陈列当天记下的想法、
// 当时 AI 抛回的追问、想法发生的地点（地图），以及一段描述性的日终回顾。
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InsightPanel(
    store: InsightStore,
    amapApiKey: String,
    onDelete: (StructuredEntry) -> Unit = {},
) {
    val scrollState = rememberScrollState()
    var dateOffset by remember { mutableIntStateOf(0) }
    val date = remember(dateOffset) { LocalDate.now().plusDays(dateOffset.toLong()) }
    val dateStr = remember(date) { date.toString() }

    // 左滑删除的条目立即从视图隐藏（按 id）——不在手势过程中重读 store，避免
    // 重组连锁误删。墓碑软删由 onDelete 异步落盘 + 同步。
    val deletedIds = remember { mutableStateListOf<String>() }
    var storeRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            storeRevision++
        }
        store.registerListener(listener)
        onDispose { store.unregisterListener(listener) }
    }
    val entries = remember(dateStr, storeRevision) { store.getEvents(dateStr) }

    val dateDisplay = remember(date) {
        when (dateOffset) {
            0 -> "Today"
            -1 -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofPattern("MMM d  E", Locale.US))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(BG).verticalScroll(scrollState).padding(horizontal = 20.dp)
    ) {
        // ── Date nav ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("< Prev", color = DIM, fontSize = 14.sp, modifier = Modifier.clickable { dateOffset-- })
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Reflections", color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(dateDisplay, color = DIM, fontSize = 13.sp)
            }
            Text("Next >", color = DIM, fontSize = 14.sp,
                modifier = Modifier.clickable { if (dateOffset < 0) dateOffset++ })
        }

        val visible = entries.filter { it.id !in deletedIds }.sortedByDescending { it.timestamp }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                Text("这一天还没有记下什么", color = DIM, fontSize = 15.sp)
            }
            return@Column
        }

        // ── Thoughts + the questions AI asked back ─────────────────────
        Text("今天理过的（左滑删除）", color = Color(0xFFE0D8C0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visible.forEach { e ->
                key(e.id) {
                    val dismissState = rememberSwipeToDismissBoxState()
                    // 只在真正左滑到位（EndToStart）时删除；初始 Settled 不触发。
                    // deletedIds 守卫保证每条只删一次，且不在手势中重读 store。
                    LaunchedEffect(dismissState.currentValue) {
                        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart && e.id !in deletedIds) {
                            deletedIds.add(e.id)   // 立即从视图隐藏
                            onDelete(e)            // 软删落盘 + 同步（异步）
                        }
                    }
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,   // 只允许左滑
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().background(Color(0xFF3A1414), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) { Text("删除", color = Color(0xFFE07A6A), fontSize = 14.sp) }
                        },
                    ) { ThoughtCard(e) }
                }
            }
        }

        // ── Location scatter map ─────────────────────────────────────────
        Spacer(Modifier.height(16.dp))
        AmapScatterMap(entries = visible, amapApiKey = amapApiKey,
            modifier = Modifier.fillMaxWidth())

        // ── Top location / time slot（本地统计，不依赖已删除的 Day Review 报告）──
        val topLocation = store.getTopLocation(dateStr)
        val topTimeSlot = store.getTopTimeSlot(dateStr)
        if (topLocation.isNotBlank() || topTimeSlot.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (topLocation.isNotBlank()) StatCard("Top Place", topLocation, GOLD, Modifier.weight(1f))
                if (topTimeSlot.isNotBlank()) StatCard("Top Time", topTimeSlot, TEAL, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ThoughtCard(e: StructuredEntry) {
    var expanded by remember(e.id) { mutableStateOf(false) }
    val time = remember(e.timestamp) {
        java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(e.timestamp))
    }
    val metadata = remember(time, e.location) {
        listOfNotNull(time, e.location.takeIf { it.isNotBlank() }).joinToString(" · ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .background(SURFACE, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(metadata, color = DIM, fontSize = 11.sp)
        Text(
            text = e.text,
            color = Color(0xFFE8E0D0),
            fontSize = 15.sp,
            lineHeight = 24.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
        if (expanded && e.questions.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            e.questions.forEach { q ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("?", color = GOLD, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(q, color = Color(0xFFB8B0A0), fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(SURFACE, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, color = DIM, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Amap scatter map (WebView + Leaflet + 高德在线瓦片) ────────────────────
// 见 assets/insight_map.html。地图容器零高度是 WebView-in-scrollview 的经典坑，
// 已在 html 用固定高度兜底 + onPageFinished 按实际高度校准（详见该文件注释）。
private class AmapJsBridge {
    var onReady: (() -> Unit)? = null
    @android.webkit.JavascriptInterface fun onMapReady() { onReady?.invoke() }
}

private fun buildPointsJson(points: List<StructuredEntry>): String {
    val arr = JSONArray()
    for (p in points) {
        arr.put(JSONObject().apply {
            put("lat", p.lat)
            put("lng", p.lng)
            put("location", p.location)
        })
    }
    return arr.toString()
}

@Composable
private fun AmapScatterMap(
    entries: List<StructuredEntry>,
    amapApiKey: String,
    modifier: Modifier = Modifier,
) {
    val points = remember(entries) { entries.filter { it.lat != 0.0 || it.lng != 0.0 } }
    if (points.isEmpty()) return

    var webView by remember { mutableStateOf<WebView?>(null) }
    val bridge = remember { AmapJsBridge() }
    bridge.onReady = { webView?.post { pushPoints(webView!!, points) } }

    Box(modifier = modifier.background(SURFACE, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Column {
            Text("Where", color = Color(0xFFE0D8C0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp))
            Text("${points.size} spot${if (points.size > 1) "s" else ""}", color = DIM, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp))
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(8.dp)),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowContentAccess = true
                        settings.allowFileAccess = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        WebView.setWebContentsDebuggingEnabled(true)
                        addJavascriptInterface(bridge, "AmapBridge")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                view.post {
                                    val h = (view.height / view.resources.displayMetrics.density).toInt()
                                    if (h > 0) view.evaluateJavascript("setMapHeight($h)", null)
                                }
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.d("TplannerMap", "${m.message()} @${m.lineNumber()}")
                                return true
                            }
                        }
                        loadUrl("file:///android_asset/insight_map.html")
                    }
                },
                update = { wv -> pushPoints(wv, points) },
            )
        }
    }
}

private fun pushPoints(wv: WebView, points: List<StructuredEntry>) {
    val json = buildPointsJson(points)
    wv.evaluateJavascript("if(typeof updateMarkers==='function')updateMarkers($json)", null)
}
