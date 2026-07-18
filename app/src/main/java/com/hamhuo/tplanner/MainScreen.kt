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
    deepseekService: DeepSeekAnalysisService?,
    scheduleTriggerCount: Int,
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
            }
            is LanSyncManager.SyncResult.Error -> {
                syncStatus = "idle"
            }
        }
    }

    val isPhone = LocalConfiguration.current.screenWidthDp < 840
    var phoneTab by remember { mutableStateOf(0) }   // 0=Journal, 1=Tasks

    // ── Schedule extraction sheet ───────────────────────────────────────
    var showScheduleSheet by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var sheetAction by remember { mutableStateOf<DeepSeekAnalysisService.ProposedAction?>(null) }

    LaunchedEffect(scheduleTriggerCount) {
        if (scheduleTriggerCount > 0) {
            showScheduleSheet = true
            thinking = false
            sheetAction = null
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

    // ── Schedule extraction flow ────────────────────────────────────────

    val submitForExtraction: (String) -> Unit = { text ->
        // Append to journal
        val now = System.currentTimeMillis()
        val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(now))
        val entryLine = "\n\n---\n\n### $stamp\n\n$text"
        content = content + entryLine
        store.saveToday(content)

        thinking = true
        sheetAction = null
        scope.launch {
            val action = deepseekService?.extractSchedule(text, stamp)
            thinking = false
            if (action != null) {
                sheetAction = action
            } else {
                Toast.makeText(context, R.string.ai_service_unavailable, Toast.LENGTH_LONG).show()
                showScheduleSheet = false
                thinking = false
            }
        }
    }

    fun confirmAction(act: DeepSeekAnalysisService.ProposedAction) {
        val start = parseAgentDatetime(act.startIso)
        val end = parseAgentDatetime(act.endIso)
        if (start == null || end == null || !end.isAfter(start)) {
            Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
            sheetAction = null
            return
        }
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
            alarmEnabled = act.alarmEnabled,
            alarmOffsetMinutes = act.alarmOffsetMinutes,
        )
        val nextEvents = events + ev
        scope.launch {
            try {
                withContext(Dispatchers.IO) { eventStore.saveAll(nextEvents) }
                events = nextEvents
                val toastMessage = when {
                    !act.alarmEnabled -> context.getString(R.string.schedule_created_toast, act.title)
                    TaskAlarmScheduler.canScheduleExactAlarms(context) ->
                        context.getString(R.string.schedule_created_with_alarm_toast, act.title)
                    else -> context.getString(
                        R.string.schedule_created_with_fallback_alarm_toast,
                        act.title,
                    )
                }
                Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
                events = manager.fetchEvents(serverUrl)
            } catch (e: Exception) {
                android.util.Log.e("TplannerTool", "create_schedule failed", e)
                Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
            }
        }
        showScheduleSheet = false
        thinking = false
        sheetAction = null
    }

    // ── Main layout ──────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(BG).windowInsetsPadding(WindowInsets.systemBars)) {
        if (showScheduleSheet) {
            UntangleSheet(
                thinking = thinking,
                action = sheetAction,
                onDismiss = {
                    showScheduleSheet = false
                    thinking = false
                    sheetAction = null
                },
                onSubmit = submitForExtraction,
                onConfirmAction = ::confirmAction,
                onDeclineAction = {
                    showScheduleSheet = false
                    thinking = false
                    sheetAction = null
                },
            )
        } else if (isPhone) {
            Column(Modifier.fillMaxSize().imePadding()) {
                PhoneTabBar(
                    tabs      = listOf(
                        stringResource(R.string.tab_journal),
                        stringResource(R.string.tab_tasks),
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
                    else taskCardContent()
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
                    alarmEnabled = type == "event",
                    alarmOffsetMinutes = 0,
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

private fun parseAgentDatetime(iso: String): Instant? = try {
    java.time.LocalDateTime.parse(iso).atZone(java.time.ZoneId.systemDefault()).toInstant()
} catch (_: Exception) { null }
