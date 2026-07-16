package com.hamhuo.tplanner

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var pendingText by remember { mutableStateOf("") }  // 原文，供 refineAction / 多轮追问用
    var qaHistory by remember { mutableStateOf("") }  // 多轮追问对话记录（供 followUp 上下文）
    var pendingFollowUpQuestions by remember { mutableStateOf<List<String>?>(null) }  // followUp 同时返回问题和 action 时，暂存问题
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
            sheetClarify = null; pendingText = ""; qaHistory = ""  // 新会话回到编辑态
            pendingFollowUpQuestions = null
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
            // "理一理"全屏面板：只记录用户输入，不持久化 LLM 输出。
            // 文本原样落随笔和 Insight，不做语法修正替换——那是 LLM 的输出。
            val submitThought: (String) -> Unit = { text ->
                val now = System.currentTimeMillis()
                val loc = prefillLocation.ifBlank { "Unknown" }
                val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(now))
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
                        ?: DeepSeekAnalysisService.ThoughtResult("questions", text, DEFAULT_QA_QUESTIONS)

                    // 只记录用户原文到 Insight（与 journal 一致），不记录 LLM 修正后的版本
                    insightStore.addEvent(StructuredEntry(
                        id = UUID.randomUUID().toString(),
                        timestamp = now,
                        text = text,
                        location = loc,
                        lat = watchLat, lng = watchLng,
                        questions = emptyList(),  // AI questions are not user input
                    ))

                    thinking = false
                    when {
                        res.action != null -> {
                            // Native Tool Call 已包含完整参数；UI 仍保留最终确认。
                            pendingFollowUpQuestions = res.questions.takeIf { it.isNotEmpty() }
                            sheetAction = res.action
                        }
                        res.clarify != null -> {
                            // 字段不全时模型不会调用工具，而是先一次问完全部缺失项。
                            pendingFollowUpQuestions = res.questions.takeIf { it.isNotEmpty() }
                            sheetClarify = res.clarify
                        }
                        else -> sheetQuestions = res.questions.ifEmpty { DEFAULT_QA_QUESTIONS }
                    }
                    insightRefreshTrigger++
                }
            }

            // 用户确认 Native Tool Call：立即恢复 QA，把创建任务交给后台协程。
            val confirmAction: (DeepSeekAnalysisService.ProposedAction) -> Unit = { act ->
                val start = parseAgentDatetime(act.startIso)
                val end = parseAgentDatetime(act.endIso)
                val resumeQuestions = pendingFollowUpQuestions ?: DEFAULT_QA_QUESTIONS
                pendingFollowUpQuestions = null
                sheetAction = null
                sheetQuestions = resumeQuestions

                if (start == null || end == null || !end.isAfter(start)) {
                    deepseekService?.submitToolResult(
                        toolCallId = act.toolCallId,
                        status = "failed",
                        message = "开始或结束时间无效",
                    )
                    Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
                } else {
                    val ev = TaskEvent(
                        id = UUID.randomUUID().toString(),
                        title = act.title,
                        type = act.type,
                        start = start,
                        end = end,
                        completed = false,
                        checklist = act.checklist.map { item ->
                            CheckItem(UUID.randomUUID().toString(), item, false)
                        },
                        colorId = act.colorId,
                        note = act.note,
                        deletedAt = 0L,
                        updatedAt = System.currentTimeMillis(),
                    )
                    val nextEvents = events + ev

                    // Tool Result 立即回传 accepted，确保 QA 下一轮具备完整工具调用链；
                    // 真正落盘与远端同步在后台执行，完成后由 Android Toast 反馈。
                    deepseekService?.submitToolResult(
                        toolCallId = act.toolCallId,
                        status = "accepted",
                        scheduleId = ev.id,
                        message = "日程已提交后台创建",
                    )
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { eventStore.saveAll(nextEvents) }
                            events = nextEvents
                            Toast.makeText(
                                context,
                                context.getString(R.string.schedule_created_toast, act.title),
                                Toast.LENGTH_SHORT,
                            ).show()
                            events = manager.fetchEvents(serverUrl)
                        } catch (e: Exception) {
                            android.util.Log.e("TplannerTool", "create_schedule failed", e)
                            Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // 用户回答了澄清追问：用答案补全操作，再切到确认卡
            val answerClarify: (String) -> Unit = { answer ->
                sheetClarify = null
                // Record user's answer (clicked option or typed) to journal and Insight
                val now = System.currentTimeMillis()
                content = store.getToday() + "\n> 你选了：$answer"
                store.saveToday(content)
                val loc = prefillLocation.ifBlank { "Unknown" }
                insightStore.addEvent(StructuredEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    text = answer,
                    location = loc,
                    lat = watchLat, lng = watchLng,
                    questions = emptyList(),
                ))
                insightRefreshTrigger++
                thinking = true
                scope.launch {
                    val refined = deepseekService?.refineAction(pendingText, answer)
                        ?: DeepSeekAnalysisService.ThoughtResult(
                            "questions",
                            pendingText,
                            DEFAULT_QA_QUESTIONS,
                        )
                    thinking = false
                    when {
                        refined.action != null -> {
                            pendingFollowUpQuestions = refined.questions.takeIf { it.isNotEmpty() }
                            sheetAction = refined.action
                        }
                        refined.clarify != null -> {
                            pendingFollowUpQuestions = refined.questions.takeIf { it.isNotEmpty() }
                            sheetClarify = refined.clarify
                        }
                        else -> sheetQuestions = refined.questions.ifEmpty { DEFAULT_QA_QUESTIONS }
                    }
                }
            }

            // 多轮追问：用户答完当前问题 → 基于整段对话往更深处再问，直到点 Done。
            // 同时 agent 可以在对话中随时识别出"要做的事"并提议插入日程。
            val answerQuestion: (String) -> Unit = { answer ->
                val current = sheetQuestions ?: emptyList()
                val now = System.currentTimeMillis()
                qaHistory += "AI问：${current.joinToString("；")}\n用户答：$answer\n"
                // 回答追加进随笔，保留思路
                content = store.getToday() + "\n> 你：$answer"
                store.saveToday(content)
                // 同时记录用户回答到 Insight（与 journal 一致）
                val loc = prefillLocation.ifBlank { "Unknown" }
                insightStore.addEvent(StructuredEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    text = answer,
                    location = loc,
                    lat = watchLat, lng = watchLng,
                    questions = emptyList(),
                ))
                insightRefreshTrigger++
                thinking = true
                scope.launch {
                    val result = deepseekService?.followUp(pendingText, qaHistory)
                        ?: DeepSeekAnalysisService.FollowUpResult(DEFAULT_QA_QUESTIONS)
                    thinking = false

                    // 完整字段会产生 Native Tool Call；字段不全时只返回 clarify。
                    when {
                        result.action != null -> {
                            pendingFollowUpQuestions = result.questions.takeIf { it.isNotEmpty() }
                            sheetAction = result.action
                            sheetQuestions = null
                        }
                        result.clarify != null -> {
                            pendingFollowUpQuestions = result.questions.takeIf { it.isNotEmpty() }
                            sheetClarify = result.clarify
                            sheetQuestions = null
                        }
                        result.questions.isNotEmpty() -> {
                            sheetQuestions = result.questions
                        }
                        else -> sheetQuestions = DEFAULT_QA_QUESTIONS
                    }
                }
            }

            UntangleSheet(
                prefillLocation = prefillLocation,
                thinking = thinking,
                questions = sheetQuestions,
                action = sheetAction,
                clarify = sheetClarify,
                onDismiss = { showAnxietySheet = false; thinking = false; sheetQuestions = null; sheetAction = null; sheetClarify = null; pendingFollowUpQuestions = null },
                onSubmit = submitThought,
                onConfirmAction = confirmAction,
                onDeclineAction = {
                    // 拒绝也要补齐 Native Tool Calls 的 tool 结果，然后继续 QA。
                    val declined = sheetAction
                    declined?.let {
                        deepseekService?.submitToolResult(
                            toolCallId = it.toolCallId,
                            status = "declined",
                            message = "用户取消创建日程",
                        )
                    }
                    val savedQuestions = pendingFollowUpQuestions ?: DEFAULT_QA_QUESTIONS
                    pendingFollowUpQuestions = null
                    sheetAction = null
                    sheetQuestions = savedQuestions
                },
                onAnswerClarify = answerClarify,
                onAnswerQuestion = answerQuestion,
            )
        } else if (isPhone) {
            Column(Modifier.fillMaxSize().imePadding()) {
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
            Box(Modifier.fillMaxSize().padding(10.dp).imePadding()) {
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

private val DEFAULT_QA_QUESTIONS = listOf("这段话里，你最想先理清或继续展开的是哪一部分？")

// 工具参数必须给出有效时间；解析失败不再静默猜成“现在”。
private fun parseAgentDatetime(iso: String): Instant? = try {
    java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneId.systemDefault()).toInstant()
} catch (_: Exception) { null }
