package com.hamhuo.tplanner

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

@Composable
fun MainScreen(
    store: JournalStore,
    eventStore: EventStore,
    manager: LanSyncManager,
    insightStore: InsightStore,
    deepseekService: DeepSeekAnalysisService?,
    amapApiKey: String,
    anxietyTriggerCount: Int,
) {
    val scope  = rememberCoroutineScope()
    val context = LocalContext.current
    var content    by remember { mutableStateOf(store.getToday()) }
    var panelOpen  by remember { mutableStateOf(false) }
    var events     by remember { mutableStateOf(eventStore.getAll()) }

    DisposableEffect(Unit) {
        val todayKey = java.time.LocalDate.now().toString()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == todayKey) content = store.getToday()
        }
        store.registerListener(listener)
        onDispose { store.unregisterListener(listener) }
    }

    // ── Sync state ───────────────────────────────────────────────────────
    var serverUrl  by remember { mutableStateOf(manager.getServerUrl()) }
    var syncStatus by remember { mutableStateOf("idle") }
    var syncMsg    by remember { mutableStateOf("") }

    val syncedTemplate  = stringResource(R.string.sync_success_with_name)

    fun serverHost(url: String): String =
        try { java.net.URL(LanSyncManager.normalizeServerUrl(url)).host } catch (_: Exception) { url }

    val onSync: () -> Unit = {
        scope.launch {
            syncStatus = "syncing"; syncMsg = ""
            manager.saveServerUrl(serverUrl)
            when (val r = manager.syncJournals(serverUrl)) {
                is LanSyncManager.SyncResult.Success -> {
                    content = r.todayText
                    syncStatus = "success"; syncMsg = syncedTemplate.format(serverHost(serverUrl))
                    events = manager.fetchEvents(serverUrl)
                    manager.syncInsights(serverUrl)
                }
                is LanSyncManager.SyncResult.Error -> {
                    syncStatus = "error"; syncMsg = r.message
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        syncStatus = "syncing"; syncMsg = ""
        when (val r = manager.syncJournals(serverUrl)) {
            is LanSyncManager.SyncResult.Success -> {
                content = r.todayText
                syncStatus = "success"; syncMsg = syncedTemplate.format(serverHost(serverUrl))
                events = manager.fetchEvents(serverUrl)
                manager.syncInsights(serverUrl)
            }
            is LanSyncManager.SyncResult.Error -> {
                syncStatus = "idle"
            }
        }
    }

    val isPhone = LocalConfiguration.current.screenWidthDp < 840
    var phoneTab by remember { mutableStateOf(0) }   // 0=Journal, 1=Tasks, 2=Insights

    // ── Watch-triggered thought capture ("理一理") ────────────────────────
    var showAnxietySheet by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }            // AI 正在读你写的
    var sheetQuestions by remember { mutableStateOf<List<String>?>(null) }  // 非空 → 显示追问
    var sheetAction by remember { mutableStateOf<DeepSeekAnalysisService.ProposedAction?>(null) }  // 非空 → 显示"加日程"提议
    var sheetClarify by remember { mutableStateOf<DeepSeekAnalysisService.Clarify?>(null) }  // 非空 → 先追问补全参数
    var pendingAction by remember { mutableStateOf<DeepSeekAnalysisService.ProposedAction?>(null) }  // 待补全的部分操作
    var pendingText by remember { mutableStateOf("") }  // 原文，供 refineAction / 多轮追问用
    var qaHistory by remember { mutableStateOf("") }  // 多轮追问对话记录（供 followUp 上下文）
    var prefillLocation by remember { mutableStateOf("") }
    // 手表打点定位（唯一真相源来自 WatchLocationStore，由蓝牙服务异步写入），
    // submit 时直接用这两个值存进 InsightStore，不再从随笔文本正则抠坐标
    var watchLat by remember { mutableStateOf(0.0) }
    var watchLng by remember { mutableStateOf(0.0) }
    var insightRefreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(anxietyTriggerCount) {
        if (anxietyTriggerCount > 0) {
            showAnxietySheet = true
            thinking = false; sheetQuestions = null; sheetAction = null
            sheetClarify = null; pendingAction = null; pendingText = ""; qaHistory = ""  // 新会话回到编辑态
            prefillLocation = ""; watchLat = 0.0; watchLng = 0.0   // 本次会话重置，面板显示"Locating..."

            // 轮询等待蓝牙服务的异步定位落地（GPS 冷启可达数秒）。savedAt 需晚于
            // 本次唤醒（留 15s 余量，因取定位在拉起 Activity 之前就已开始），避免
            // 采用上一次打点的陈旧坐标。最多等 ~12s。
            val triggerAt = System.currentTimeMillis()
            var fix: WatchLocationStore.Fix? = null
            while (System.currentTimeMillis() - triggerAt < 12_000) {
                val cur = WatchLocationStore.get(context)
                if (cur != null && cur.savedAt >= triggerAt - 15_000) { fix = cur; break }
                kotlinx.coroutines.delay(500)
            }
            // 超时兜底：即便是陈旧定位也好过没有
            if (fix == null) fix = WatchLocationStore.get(context)

            // 手机自身兜底：手表始终没提供定位时，直接取手机最近已知定位，
            // 避免记成 Unknown（面板入口仍只由手表唤醒，这里只补定位来源）。
            if (fix == null) {
                try {
                    val lm = context.getSystemService(android.location.LocationManager::class.java)
                    val last = listOf(
                        android.location.LocationManager.FUSED_PROVIDER,
                        android.location.LocationManager.GPS_PROVIDER,
                        android.location.LocationManager.NETWORK_PROVIDER,
                    ).asSequence()
                        .filter { lm?.allProviders?.contains(it) == true }
                        .mapNotNull { p -> runCatching { lm?.getLastKnownLocation(p) }.getOrNull() }
                        .maxByOrNull { it.time }
                    if (last != null) fix = WatchLocationStore.Fix(last.latitude, last.longitude, last.time)
                } catch (_: SecurityException) { /* 无定位权限则跳过 */ }
            }

            fix?.let {
                watchLat = it.lat; watchLng = it.lng
                if (amapApiKey.isNotBlank()) {
                    // 逆地理编码失败会回退坐标串——面板从"Locating..."更新为地名/坐标
                    prefillLocation = AmapGeocoder.reverseGeocode(it.lat, it.lng, amapApiKey)
                }
            }
        }
    }

    // ── Panel building blocks ────────────────────────────────────────────
    val notesCardContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                NotesHeader(
                    syncStatus = syncStatus,
                    onPanelToggle = { panelOpen = !panelOpen }
                )
                HorizontalDivider(color = BORDER, thickness = 1.dp)
                MarkdownField(
                    content = content,
                    onSave = { text -> content = text; store.saveToday(text) },
                    placeholder = stringResource(R.string.journal_edit_hint),
                    modifier = Modifier.weight(1f)
                )
            }
            if (panelOpen) {
                SyncPanel(
                    modifier    = Modifier.align(Alignment.TopEnd).padding(top = 50.dp, end = 8.dp),
                    serverUrl   = serverUrl,
                    syncStatus  = syncStatus,
                    syncMsg     = syncMsg,
                    canSync     = serverUrl.isNotBlank() && syncStatus != "syncing",
                    onUrlChange = { serverUrl = it },
                    onSync      = onSync,
                    onClose     = { panelOpen = false }
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
                events = events.map {
                    if (it.id == eventId) it.copy(completed = completed, updatedAt = System.currentTimeMillis()) else it
                }
                eventStore.saveAll(events)
                scope.launch { events = manager.fetchEvents(serverUrl) }
            },
            onAddEvent = { type -> pendingAddType = type },
            onDelete = { eventId ->
                val now = System.currentTimeMillis()
                events = events.map {
                    if (it.id == eventId) it.copy(deletedAt = now, updatedAt = now) else it
                }
                eventStore.saveAll(events)
                scope.launch { events = manager.fetchEvents(serverUrl) }
            },
            onItemClick = { event -> editingEvent = event },
            onTypeChange = { eventId, newType ->
                events = events.map {
                    if (it.id == eventId) {
                        it.copy(
                            type = newType,
                            completed = if (newType == "task") it.completed else false,
                            checklist = if (newType == "task") it.checklist else emptyList(),
                            updatedAt = System.currentTimeMillis()
                        )
                    } else it
                }
                eventStore.saveAll(events)
                scope.launch { events = manager.fetchEvents(serverUrl) }
            }
        )
    }

    // ── Main layout ──────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(BG).windowInsetsPadding(WindowInsets.systemBars)) {
        if (showAnxietySheet) {
            // "理一理"全屏面板：默认只把想法修好语法记成随笔；仅当用户在文字里
            // 明确求助时，AI 才反过来提问（不给答案/不诊断）。
            val submitThought: (String) -> Unit = { text ->
                val now = System.currentTimeMillis()
                val loc = prefillLocation.ifBlank { "Unknown" }
                val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(now))
                // 想法本身先原样落随笔（保证记录必存），拿到结果后再替换成修好语法的版本
                val entryLine = "\n\n---\n\n### $stamp · $loc\n\n$text"
                content = content + entryLine
                store.saveToday(content)

                thinking = true
                sheetQuestions = null
                sheetAction = null
                sheetClarify = null
                pendingText = text
                scope.launch {
                    val res = deepseekService?.processThought(text, stamp, loc)
                        ?: DeepSeekAnalysisService.ThoughtResult("record", text, emptyList())

                    // 语法修正：把随笔里的原文替换成修好的版本（record 是默认行为；
                    // 其它模式也顺带把错别字理一下）
                    if (res.text.isNotBlank() && res.text != text) {
                        store.replaceInToday(text, res.text)
                        content = store.getToday()
                    }

                    insightStore.addEvent(StructuredEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = now,
                        text = res.text,
                        location = loc,
                        lat = watchLat, lng = watchLng,
                        questions = res.questions,
                    ))

                    when {
                        res.mode == "questions" && res.questions.isNotEmpty() -> {
                            // 追问追加到随笔，方便日后翻看；面板切到"追问"态
                            val block = buildString {
                                append("\n\n> **有几个问题给你**")
                                res.questions.forEachIndexed { i, q -> append("\n> ${i + 1}. $q") }
                            }
                            content = store.getToday() + block
                            store.saveToday(content)
                            thinking = false
                            sheetQuestions = res.questions
                        }
                        res.mode == "action" && res.action != null -> {
                            thinking = false
                            if (res.clarify != null) {
                                // 缺关键参数（如提醒时间）→ 先追问，答完再补全
                                pendingAction = res.action
                                sheetClarify = res.clarify
                            } else {
                                // 参数齐全 → 直接提议，等用户确认
                                sheetAction = res.action
                            }
                        }
                        else -> {
                            // record 模式（或失败）：已记下，直接关面板
                            thinking = false
                            showAnxietySheet = false
                        }
                    }
                    insightRefreshTrigger++
                }
            }

            // 用户确认"加入日程"：把 AI 提议的动作落成 TaskEvent，落盘并同步
            val confirmAction: (DeepSeekAnalysisService.ProposedAction) -> Unit = { act ->
                val start = parseAgentDatetime(act.datetimeIso)
                val ev = TaskEvent(
                    id = UUID.randomUUID().toString(),
                    title = act.title,
                    type = if (act.type == "reminder") "reminder" else "task",
                    start = start,
                    end = start.plusSeconds(3600),
                    completed = false,
                    checklist = emptyList(),
                    colorId = 0,
                    note = "",
                    deletedAt = 0L,
                    updatedAt = System.currentTimeMillis(),
                )
                events = events + ev
                eventStore.saveAll(events)
                showAnxietySheet = false; sheetAction = null
                scope.launch { events = manager.fetchEvents(serverUrl) }   // 推到服务器/其它端
            }

            // 用户回答了澄清追问：用答案补全操作，再切到确认卡
            val answerClarify: (String) -> Unit = { answer ->
                val partial = pendingAction
                sheetClarify = null
                if (partial == null) { showAnxietySheet = false }
                else {
                    thinking = true
                    scope.launch {
                        val refined = deepseekService?.refineAction(pendingText, partial, answer) ?: partial
                        thinking = false
                        pendingAction = null
                        sheetAction = refined
                    }
                }
            }

            // 多轮追问：用户答完当前问题 → 基于整段对话往更深处再问，直到点 Done
            val answerQuestion: (String) -> Unit = { answer ->
                val current = sheetQuestions ?: emptyList()
                qaHistory += "AI问：${current.joinToString("；")}\n用户答：$answer\n"
                // 回答追加进随笔，保留思路
                content = store.getToday() + "\n> 你：$answer"
                store.saveToday(content)
                thinking = true
                scope.launch {
                    val next = deepseekService?.followUpQuestions(pendingText, qaHistory) ?: emptyList()
                    thinking = false
                    if (next.isNotEmpty()) {
                        val block = buildString { append("\n> 再问："); next.forEach { append("\n> - $it") } }
                        content = store.getToday() + block
                        store.saveToday(content)
                        sheetQuestions = next
                    }
                    // next 为空：保持当前问题，用户可继续答或点 Done
                }
            }

            UntangleSheet(
                prefillLocation = prefillLocation,
                thinking = thinking,
                questions = sheetQuestions,
                action = sheetAction,
                clarify = sheetClarify,
                onDismiss = { showAnxietySheet = false; thinking = false; sheetQuestions = null; sheetAction = null; sheetClarify = null },
                onSubmit = submitThought,
                onConfirmAction = confirmAction,
                onDeclineAction = { showAnxietySheet = false; sheetAction = null },  // 就当笔记（想法已存）
                onAnswerClarify = answerClarify,
                onAnswerQuestion = answerQuestion,
            )
        } else if (isPhone) {
            Column(Modifier.fillMaxSize()) {
                PhoneTabBar(
                    tabs      = listOf(
                        stringResource(R.string.tab_journal),
                        stringResource(R.string.tab_tasks),
                        "Insights"
                    ),
                    selected  = phoneTab,
                    onSelect  = { phoneTab = it }
                )
                Card(
                    modifier  = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 10.dp),
                    shape     = RoundedCornerShape(20.dp),
                    colors    = CardDefaults.cardColors(containerColor = SURFACE),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    if (phoneTab == 0) notesCardContent()
                    else if (phoneTab == 1) taskCardContent()
                    else InsightPanel(
                        store = insightStore, amapApiKey = amapApiKey,
                        onRefresh = { insightRefreshTrigger++ },
                        onDelete = { entry ->
                            insightStore.deleteEvent(entry.id, entry.timestamp)   // 软删（同步）
                            scope.launch { manager.syncInsights(serverUrl) }       // 把墓碑推到服务器
                        },
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(10.dp)) {
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

    // ── Overlay panels ───────────────────────────────────────────────────
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

// AI 提议的本地时间 ISO（如 2026-07-07T08:00:00）→ Instant；空/解析失败回退到"现在"。
// 无明确时间的待办（task）就落在当前时刻，用户之后可在详情页调。
private fun parseAgentDatetime(iso: String): Instant = try {
    if (iso.isBlank()) Instant.now()
    else java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneId.systemDefault()).toInstant()
} catch (_: Exception) { Instant.now() }
