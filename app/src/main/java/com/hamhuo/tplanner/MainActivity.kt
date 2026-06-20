package com.hamhuo.tplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store       = JournalStore(this)
        val eventStore  = EventStore(this)
        val manager     = LanSyncManager(this, store, eventStore)
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

    // stringResource() 只能在 composition 中调用，不能在 coroutine/launch 回调里调用，
    // 所以提前在这里解析好，再在回调里用 .format() 套用参数。
    val peerManualName  = stringResource(R.string.peer_manual)
    val syncedTemplate  = stringResource(R.string.sync_success_with_name)

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
            LanSyncManager.Peer(peerManualName, it, manualPort.toIntOrNull() ?: 37401, 0)
        }

    val onSync: () -> Unit = {
        val peer = activePeer
        if (peer != null) scope.launch {
            syncStatus = "syncing"; syncMsg = ""
            when (val r = manager.syncJournals(peer)) {
                is LanSyncManager.SyncResult.Success -> {
                    content = r.todayText
                    syncStatus = "success"; syncMsg = syncedTemplate.format(peer.name)
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
                syncStatus = "success"; syncMsg = syncedTemplate.format(target.name)
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
    var editingEvent   by remember { mutableStateOf<TaskEvent?>(null) }

    val taskCardContent: @Composable () -> Unit = {
        TaskWidget(
            events   = events,
            onToggle = { eventId, completed ->
                // 本地优先：先落盘，再尽力推送给已连接的对端；不要求必须有 peer 才能勾选。
                events = events.map {
                    if (it.id == eventId) it.copy(completed = completed, updatedAt = System.currentTimeMillis()) else it
                }
                eventStore.saveAll(events)
                activePeer?.let { peer ->
                    scope.launch { manager.toggleTask(peer, eventId, completed) }
                }
            },
            onAddEvent = { type -> pendingAddType = type },
            onDelete = { eventId ->
                val now = System.currentTimeMillis()
                events = events.map {
                    if (it.id == eventId) it.copy(deletedAt = now, updatedAt = now) else it
                }
                eventStore.saveAll(events)
            }
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
                tabs      = listOf(stringResource(R.string.tab_journal), stringResource(R.string.tab_tasks)),
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

    // 命名半屏面板 — 选完类型后先命名，再进入详情页
    pendingAddType?.let { type ->
        NameInputSheet(
            type = type,
            onCancel = { pendingAddType = null },
            onConfirm = { name ->
                val now = Instant.now()
                pendingAddType = null
                editingEvent = TaskEvent(
                    id        = UUID.randomUUID().toString(),
                    title     = name,
                    type      = type,
                    start     = now,
                    end       = now.plusSeconds(3600),
                    completed = false,
                    checklist = emptyList(),
                    colorId   = 0,
                    note      = "",
                    deletedAt = 0L,
                    updatedAt = now.toEpochMilli(),
                )
            }
        )
    }

    // 详情页 — 时间/清单/备注/颜色
    editingEvent?.let { ev ->
        EventDetailScreen(
            event = ev,
            onSave = { updated ->
                events = events.filter { it.id != updated.id } + updated
                eventStore.saveAll(events)
                editingEvent = null
            }
        )
    }
}
