package com.hamhuo.tplanner

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ── Colors ────────────────────────────────────────────────────────────────────
private val BG       = Color(0xFF0E0E0E)
private val SURFACE  = Color(0xFF1A1A1A)
private val SURFACE2 = Color(0xFF222222)
private val GOLD     = Color(0xFFC9A84C)
private val DIM      = Color(0xFF7A7163)
private val BLUE     = Color(0xFF5B8FCC)
private val TEAL     = Color(0xFF4A9DA8)
private val RED      = Color(0xFFC0392B)
private val BORDER   = Color(0xFF2D2D2D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store   = JournalStore(this)
        val manager = LanSyncManager(store)
        setContent { MainScreen(store = store, manager = manager) }
    }
}

@Composable
fun MainScreen(store: JournalStore, manager: LanSyncManager) {
    val scope  = rememberCoroutineScope()
    var content    by remember { mutableStateOf(store.getToday()) }
    var panelOpen  by remember { mutableStateOf(false) }

    // ── 扫描状态 ──────────────────────────────────────────────────────────────
    var scanning   by remember { mutableStateOf(false) }
    var peers      by remember { mutableStateOf<List<LanSyncManager.Peer>>(emptyList()) }
    var selected   by remember { mutableStateOf<LanSyncManager.Peer?>(null) }
    var manualIp   by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("37401") }

    // ── 同步状态 ──────────────────────────────────────────────────────────────
    var syncStatus by remember { mutableStateOf("idle") }   // idle|syncing|success|error
    var syncMsg    by remember { mutableStateOf("") }

    val onScan: () -> Unit = {
        scope.launch {
            scanning = true; peers = emptyList(); selected = null
            val found = manager.discoverPeers()
            peers   = found
            selected = if (found.size == 1) found.first() else null
            scanning = false
        }
    }

    val activePeer: LanSyncManager.Peer? = selected
        ?: manualIp.trim().takeIf { it.isNotBlank() }?.let {
            LanSyncManager.Peer("手动", it, manualPort.toIntOrNull() ?: 37401, 0)
        }

    val onSync: () -> Unit = {
        val peer = activePeer
        if (peer != null) scope.launch {
            syncStatus = "syncing"; syncMsg = ""
            when (val r = manager.syncJournals(peer)) {
                is LanSyncManager.SyncResult.Success -> {
                    content = r.todayText
                    syncStatus = "success"; syncMsg = "已同步 · ${peer.name}"
                }
                is LanSyncManager.SyncResult.Error -> {
                    syncStatus = "error"; syncMsg = r.message
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {

            // 左侧面板 — 随手记，黄金比例 61.8%
            Card(
                modifier = Modifier.weight(1.618f).fillMaxHeight(),
                shape    = RoundedCornerShape(20.dp),
                colors   = CardDefaults.cardColors(containerColor = SURFACE),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        NotesHeader(
                            syncStatus = syncStatus,
                            onPanelToggle = { panelOpen = !panelOpen }
                        )
                        HorizontalDivider(color = BORDER, thickness = 1.dp)
                        MarkdownViewer(content = content, modifier = Modifier.weight(1f))
                    }

                    // 同步面板 overlay
                    if (panelOpen) {
                        SyncPanel(
                            modifier    = Modifier.align(Alignment.TopEnd).padding(top = 50.dp, end = 8.dp),
                            scanning    = scanning,
                            peers       = peers,
                            selected    = selected,
                            manualIp    = manualIp,
                            manualPort  = manualPort,
                            syncStatus  = syncStatus,
                            syncMsg     = syncMsg,
                            canSync     = activePeer != null && syncStatus != "syncing",
                            onScan      = onScan,
                            onSelect    = { selected = it },
                            onIpChange  = { manualIp = it },
                            onPortChange = { manualPort = it },
                            onSync      = onSync,
                            onClose     = { panelOpen = false }
                        )
                    }
                }
            }

            // 右侧面板 — 预留 38.2%
            Card(
                modifier  = Modifier.weight(1.0f).fillMaxHeight(),
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = SURFACE),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {}
        }
    }
}

@Composable
fun NotesHeader(syncStatus: String, onPanelToggle: () -> Unit) {
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    val iconColor = when (syncStatus) {
        "success" -> TEAL
        "error"   -> RED
        "syncing" -> GOLD
        else      -> DIM
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("随手记", color = GOLD, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(today, color = DIM, fontSize = 11.sp)
        }
        IconButton(onClick = onPanelToggle) {
            Text("⇄", color = iconColor, fontSize = 17.sp)
        }
    }
}

@Composable
fun SyncPanel(
    modifier: Modifier,
    scanning: Boolean,
    peers: List<LanSyncManager.Peer>,
    selected: LanSyncManager.Peer?,
    manualIp: String,
    manualPort: String,
    syncStatus: String,
    syncMsg: String,
    canSync: Boolean,
    onScan: () -> Unit,
    onSelect: (LanSyncManager.Peer) -> Unit,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    val msgColor = when (syncStatus) {
        "success" -> TEAL; "error" -> RED; else -> GOLD
    }

    Card(
        modifier  = modifier.width(270.dp).shadow(16.dp, RoundedCornerShape(10.dp)),
        shape     = RoundedCornerShape(10.dp),
        colors    = CardDefaults.cardColors(containerColor = SURFACE2),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // 标题行
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("局域网同步", color = DIM, fontSize = 10.sp, letterSpacing = 0.1.sp)
                Text("✕", color = DIM, fontSize = 12.sp, modifier = Modifier.clickable { onClose() })
            }

            // 扫描按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(5.dp))
                    .clickable(enabled = !scanning) { onScan() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (scanning) "扫描中…" else "🔍  扫描局域网",
                    color = if (scanning) DIM else Color(0xFFE0D8C8),
                    fontSize = 11.sp
                )
            }

            // 发现的服务器列表
            if (peers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    peers.forEach { peer ->
                        val isSelected = selected?.ip == peer.ip && selected?.port == peer.port
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) Color(0x265B8FCC) else Color(0x08FFFFFF),
                                    RoundedCornerShape(5.dp)
                                )
                                .border(1.dp, if (isSelected) Color(0x805B8FCC) else BORDER, RoundedCornerShape(5.dp))
                                .clickable { onSelect(peer) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(peer.name, color = Color(0xFFE0D8C8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("${peer.ip}:${peer.port}", color = DIM, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text("${peer.journalCount}条", color = DIM, fontSize = 9.sp)
                        }
                    }
                }
            } else if (!scanning) {
                Text("未发现设备，可手动填写 IP", color = DIM, fontSize = 10.sp)
            }

            // 手动 IP 输入
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoInput(
                    value       = manualIp,
                    placeholder = "192.168.x.x",
                    onValue     = onIpChange,
                    modifier    = Modifier.weight(1f)
                )
                MonoInput(
                    value       = manualPort,
                    placeholder = "37401",
                    onValue     = onPortChange,
                    modifier    = Modifier.width(60.dp)
                )
            }

            // 状态
            if (syncMsg.isNotBlank()) {
                Text(syncMsg, color = msgColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            // 同步按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (canSync) BLUE else Color(0xFF2A2A2A), RoundedCornerShape(5.dp))
                    .clickable(enabled = canSync) { onSync() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = if (syncStatus == "syncing") "同步中…" else "立即同步",
                    color = if (canSync) Color.White else DIM,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun MonoInput(value: String, placeholder: String, onValue: (String) -> Unit, modifier: Modifier) {
    BasicTextField(
        value         = value,
        onValueChange = onValue,
        singleLine    = true,
        textStyle     = TextStyle(color = Color(0xFFE0D8C8), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        cursorBrush   = SolidColor(GOLD),
        modifier      = modifier
            .background(Color(0xFF111111), RoundedCornerShape(4.dp))
            .border(1.dp, BORDER, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            inner()
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownViewer(content: String, modifier: Modifier = Modifier) {
    var webView   by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    LaunchedEffect(pageReady, content) {
        if (!pageReady) return@LaunchedEffect
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        webView?.evaluateJavascript("renderBase64('$b64')", null)
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory  = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(0x00000000)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { pageReady = true }
                }
                loadUrl("file:///android_asset/md_viewer.html")
                webView = this
            }
        },
        update = { view -> webView = view }
    )
}
