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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
        val store       = JournalStore(this)
        val eventStore  = EventStore(this)
        val manager     = LanSyncManager(store, eventStore)
        val historyStore = SyncHistoryStore(this)
        setContent { MainScreen(store = store, eventStore = eventStore, manager = manager, historyStore = historyStore) }
    }
}

@Composable
fun MainScreen(store: JournalStore, eventStore: EventStore, manager: LanSyncManager, historyStore: SyncHistoryStore) {
    val scope  = rememberCoroutineScope()
    var content    by remember { mutableStateOf(store.getToday()) }
    var panelOpen  by remember { mutableStateOf(false) }
    var events     by remember { mutableStateOf(eventStore.getAll()) }

    // ── 扫描状态 ──────────────────────────────────────────────────────────────
    var scanning      by remember { mutableStateOf(false) }
    var peers         by remember { mutableStateOf<List<LanSyncManager.Peer>>(emptyList()) }
    var selected      by remember { mutableStateOf<LanSyncManager.Peer?>(null) }
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
                    historyStore.saveSuccess(peer)
                    events = manager.fetchEvents(peer)
                }
                is LanSyncManager.SyncResult.Error -> {
                    syncStatus = "error"; syncMsg = r.message
                }
            }
        }
    }

    // 启动时后台自动连接历史服务器
    LaunchedEffect(Unit) {
        val historyKeys = historyStore.getHistory().map { "${it.ip}:${it.port}" }.toHashSet()
        val found = manager.discoverPeers()
        val target = found.firstOrNull { historyKeys.isEmpty() || "${it.ip}:${it.port}" in historyKeys }
            ?: return@LaunchedEffect
        syncStatus = "syncing"; syncMsg = ""
        when (val r = manager.syncJournals(target)) {
            is LanSyncManager.SyncResult.Success -> {
                content = r.todayText
                selected = target
                syncStatus = "success"; syncMsg = "已同步 · ${target.name}"
                historyStore.saveSuccess(target)
                events = manager.fetchEvents(target)
            }
            is LanSyncManager.SyncResult.Error -> {
                syncStatus = "idle"
            }
        }
    }

    // 宽度 < 840dp（Material3 Expanded 断点）视为紧凑布局：单列 + 顶部标签页切换。
    // 平板竖屏宽度通常 700-840dp，仍不足以舒展两栏，需与手机横屏一样走单栏。
    val isPhone = LocalConfiguration.current.screenWidthDp < 840
    var phoneTab by remember { mutableStateOf(0) }   // 0=随手记, 1=今日任务

    // 共用面板构建块（notes card content）
    val notesCardContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                NotesHeader(
                    syncStatus = syncStatus,
                    onPanelToggle = { panelOpen = !panelOpen }
                )
                HorizontalDivider(color = BORDER, thickness = 1.dp)
                MarkdownViewer(content = content, modifier = Modifier.weight(1f))
            }
            if (panelOpen) {
                SyncPanel(
                    modifier    = Modifier.align(Alignment.TopEnd).padding(top = 50.dp, end = 8.dp),
                    scanning    = scanning,
                    peers       = peers,
                    selected    = selected,
                    manualIp     = manualIp,
                    manualPort   = manualPort,
                    syncStatus   = syncStatus,
                    syncMsg      = syncMsg,
                    canSync      = activePeer != null && syncStatus != "syncing",
                    onScan       = onScan,
                    onSelect     = { selected = it },
                    onIpChange   = { manualIp = it },
                    onPortChange = { manualPort = it },
                    onSync       = onSync,
                    onClose      = { panelOpen = false }
                )
            }
        }
    }

    var pendingAddType by remember { mutableStateOf<String?>(null) }

    val taskCardContent: @Composable () -> Unit = {
        TaskWidget(
            events   = events,
            onToggle = { eventId, completed ->
                val peer = activePeer ?: return@TaskWidget
                scope.launch {
                    manager.toggleTask(peer, eventId, completed)
                    events = events.map { if (it.id == eventId) it.copy(completed = completed) else it }
                    eventStore.saveAll(events)
                }
            },
            onAddEvent = { type -> pendingAddType = type }
        )
    }

    if (isPhone) {
        // ── 手机布局：顶部圆角胶囊标签 + 单面板 ────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BG)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            PhoneTabBar(
                tabs      = listOf("随手记", "今日任务"),
                selected  = phoneTab,
                onSelect  = { phoneTab = it }
            )
            Card(
                modifier  = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 10.dp),
                shape     = RoundedCornerShape(20.dp),
                colors    = CardDefaults.cardColors(containerColor = SURFACE),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                if (phoneTab == 0) notesCardContent() else taskCardContent()
            }
        }
    } else {
        // ── 平板布局：左右双面板 ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BG)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(10.dp)
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    modifier  = Modifier.weight(1.618f).fillMaxHeight(),
                    shape     = RoundedCornerShape(20.dp),
                    colors    = CardDefaults.cardColors(containerColor = SURFACE),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) { notesCardContent() }

                Card(
                    modifier  = Modifier.weight(1.0f).fillMaxHeight(),
                    shape     = RoundedCornerShape(20.dp),
                    colors    = CardDefaults.cardColors(containerColor = SURFACE),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) { taskCardContent() }
            }
        }
    }
}

@Composable
fun PhoneTabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .background(
                        color = if (isSelected) GOLD else Color(0xFF252525),
                        shape = RoundedCornerShape(50.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) GOLD else BORDER,
                        shape = RoundedCornerShape(50.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 22.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = label,
                    color      = if (isSelected) Color(0xFF0E0E0E) else DIM,
                    fontSize   = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    letterSpacing = 0.3.sp
                )
            }
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
            Text("随手记", color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(today, color = DIM, fontSize = 14.sp)
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
                    text = if (scanning) "扫描中…" else "⌕  扫描局域网",
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

// ── Task Widget ───────────────────────────────────────────────────────────────

private val EVENT_COLORS = listOf(
    Color(0xFF5B8FCC), Color(0xFFC9A84C), Color(0xFFC0697A), Color(0xFF5B9E72),
    Color(0xFF8B6BAE), Color(0xFFC87D5A), Color(0xFF4A9DA8), Color(0xFF8A8A8A)
)

private fun taskStatus(e: TaskEvent, now: Instant): String {
    return when {
        e.end.isBefore(now)                                -> "past"
        !e.start.isAfter(now) && !e.end.isBefore(now)     -> "now"
        e.start.epochSecond - now.epochSecond <= 5 * 60   -> "soon"
        else                                               -> "future"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskWidget(events: List<TaskEvent>, onToggle: (String, Boolean) -> Unit, onAddEvent: (String) -> Unit) {
    val now    = remember { Instant.now() }
    val today  = remember { LocalDate.now() }
    val zone   = remember { ZoneId.systemDefault() }
    val fmt    = remember { DateTimeFormatter.ofPattern("HH:mm") }

    var showTypeSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val todayEvents = remember(events) { events.forToday() }

    val groups = remember(todayEvents) {
        val current  = mutableListOf<TaskEvent>()
        val upcoming = mutableListOf<TaskEvent>()
        val past     = mutableListOf<TaskEvent>()
        val done     = mutableListOf<TaskEvent>()
        todayEvents.forEach { e ->
            if (e.type == "task" && e.completed) { done += e; return@forEach }
            when (taskStatus(e, now)) {
                "now"  -> current += e
                "soon" -> upcoming += e
                "past" -> past += e
                else   -> upcoming += e
            }
        }
        mapOf("进行中" to current, "稍后" to upcoming, "已过" to past, "已完成" to done)
    }

    val taskTotal = todayEvents.count { it.type == "task" }
    val taskDone  = todayEvents.count { it.type == "task" && it.completed }

    Column(Modifier.fillMaxSize()) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("今日任务", color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    today.format(DateTimeFormatter.ofPattern("M月d日 · E")),
                    color = DIM, fontSize = 15.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (taskTotal > 0) {
                    Text("$taskDone/$taskTotal", color = DIM, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                }
                // 右侧 + 按钮
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0xFF252525), CircleShape)
                        .border(1.dp, BORDER, CircleShape)
                        .clickable { showTypeSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新建", tint = GOLD, modifier = Modifier.size(20.dp))
                }
            }
        }

        HorizontalDivider(color = BORDER, thickness = 1.dp)

        if (todayEvents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("今日空闲", color = Color(0xFF3A342A), fontSize = 16.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                groups.forEach { (label, list) ->
                    if (list.isEmpty()) return@forEach
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(label, color = Color(0xFF6B5928), fontSize = 13.sp, letterSpacing = 0.12.sp)
                            Box(
                                Modifier
                                    .background(Color(0x1FC9A84C), RoundedCornerShape(2.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("${list.size}", color = Color(0xFF6B5928), fontSize = 13.sp)
                            }
                        }
                    }
                    items(list) { e ->
                        TaskItem(
                            event  = e,
                            fmt    = fmt,
                            zone   = zone,
                            now    = now,
                            onToggle = onToggle
                        )
                    }
                }
            }
        }
    }

    // 底部弹出面板 — 选择新建类型
    if (showTypeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTypeSheet = false },
            sheetState       = sheetState,
            containerColor   = Color(0xFF1A1A1A),
            dragHandle       = null,
        ) {
            AddEventTypeSheet(
                onSelect = { type ->
                    showTypeSheet = false
                    onAddEvent(type)
                },
                onDismiss = { showTypeSheet = false }
            )
        }
    }
}

@Composable
fun AddEventTypeSheet(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
    ) {
        // 拖拽把手
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .width(36.dp).height(4.dp)
                    .background(Color(0xFF444444), RoundedCornerShape(2.dp))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("新建", color = Color(0xFFE0D8C8), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("✕", color = DIM, fontSize = 16.sp, modifier = Modifier.clickable { onDismiss() })
        }

        Spacer(Modifier.height(12.dp))

        AddTypeItem(
            icon   = Icons.Outlined.CheckCircle,
            title  = "任务",
            desc   = "创建可勾选的待办任务",
            onClick = { onSelect("task") }
        )
        AddTypeItem(
            icon   = Icons.Outlined.CalendarMonth,
            title  = "事件",
            desc   = "添加日程或会议安排",
            onClick = { onSelect("event") }
        )
        AddTypeItem(
            icon   = Icons.Outlined.Alarm,
            title  = "提醒",
            desc   = "在指定时间发送提醒",
            onClick = { onSelect("reminder") }
        )
    }
}

@Composable
private fun AddTypeItem(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(0xFF2E2E2E), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFFE0D8C8), modifier = Modifier.size(26.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFFE0D8C8), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = DIM, fontSize = 13.sp)
        }
    }
}

@Composable
fun TaskItem(
    event: TaskEvent,
    fmt: DateTimeFormatter,
    zone: ZoneId,
    now: Instant,
    onToggle: (String, Boolean) -> Unit,
) {
    val status     = taskStatus(event, now)
    val isDone     = event.type == "task" && event.completed
    val isPast     = status == "past"
    val isNow      = status == "now"
    val color      = EVENT_COLORS.getOrElse(event.colorId) { EVENT_COLORS[0] }
    val doneCount  = event.checklist.count { it.completed }
    val totalCheck = event.checklist.size

    val rowAlpha   = if (isDone || isPast) 0.45f else 1f
    val bgColor    = if (isNow) Color(0x0F5B8FCC) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .then(if (isNow) Modifier.border(
                width = 1.dp,
                color = Color(0x305B8FCC),
                shape = RoundedCornerShape(0.dp)
            ) else Modifier)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 勾选框 / 颜色条
        if (event.type == "task") {
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .background(
                        if (isDone) GOLD else Color.Transparent,
                        RoundedCornerShape(2.dp)
                    )
                    .border(1.5.dp, if (isDone) GOLD else BORDER, RoundedCornerShape(2.dp))
                    .clickable { onToggle(event.id, !event.completed) },
                contentAlignment = Alignment.Center
            ) {
                if (isDone) Text("✓", color = Color.Black, fontSize = 9.sp, lineHeight = 9.sp)
            }
        } else {
            Box(
                Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .background(color, RoundedCornerShape(1.dp))
            )
        }

        Column(Modifier.weight(1f).then(if (rowAlpha < 1f) Modifier.then(Modifier) else Modifier)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text       = event.title.ifBlank { "(无标题)" },
                    color      = if (isDone) DIM else Color(0xFFE0D8C8),
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 1,
                    modifier   = Modifier.weight(1f, fill = false),
                    style      = if (isDone) TextStyle(
                        color          = DIM,
                        fontSize       = 15.sp,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                    ) else TextStyle(color = Color(0xFFE0D8C8), fontSize = 15.sp)
                )
                if (totalCheck > 0) {
                    val allDone = doneCount == totalCheck
                    Box(
                        Modifier
                            .background(
                                if (allDone) Color(0x334A7C59) else Color(0x1FC9A84C),
                                RoundedCornerShape(2.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "$doneCount/$totalCheck",
                            color    = if (allDone) Color(0xFF4A7C59) else Color(0xFF6B5928),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (isNow) Text("现在", color = Color(0xFF8BB8E8), fontSize = 11.sp)
                else if (status == "soon") Text("即将", color = GOLD, fontSize = 11.sp)
            }
            val startFmt = event.start.atZone(zone).format(fmt)
            val endFmt   = event.end.atZone(zone).format(fmt)
            Text(
                "$startFmt – $endFmt",
                color = DIM, fontSize = 14.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}
