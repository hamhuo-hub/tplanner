package com.hamhuo.tplanner

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    store: JournalStore,
    eventStore: EventStore,
    manager: LanSyncManager,
    deepseekService: DeepSeekAnalysisService?,
    amapApiKey: String,
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
                    WatchScheduleSync.push(context, events)
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
                WatchScheduleSync.push(context, events)
            }
            is LanSyncManager.SyncResult.Error -> {
                syncStatus = "idle"
            }
        }
    }

    val isPhone = LocalConfiguration.current.screenWidthDp < 840
    var phoneTab by remember { mutableStateOf(0) }   // 0=Journal, 1=EventList
    var selectedList by remember { mutableStateOf<EventList>(EventList.Inbox) }
    var showListSheet by remember { mutableStateOf(false) }
    val listSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val listLabel = when (selectedList) {
        is EventList.Inbox -> stringResource(R.string.list_inbox)
        is EventList.Today -> stringResource(R.string.list_today)
    }

    // ── Schedule extraction sheet ───────────────────────────────────────
    var showScheduleSheet by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var sheetAction by remember { mutableStateOf<DeepSeekAnalysisService.ProposedAction?>(null) }
    var prefillLocation by remember { mutableStateOf("") }
    var gpsLat by remember { mutableStateOf(0.0) }
    var gpsLng by remember { mutableStateOf(0.0) }

    LaunchedEffect(scheduleTriggerCount) {
        if (scheduleTriggerCount > 0) {
            showScheduleSheet = true
            thinking = false
            sheetAction = null
            prefillLocation = ""
            gpsLat = 0.0; gpsLng = 0.0

            // Start foreground location capture. primeFreshCache was already
            // called by WakeDataLayerService before the Activity was visible.
            val handle = LocationCapture.start(context)

            // Poll WatchLocationStore for a fix matching this capture generation.
            val deadline = System.currentTimeMillis() + 12_000
            var fix: WatchLocationStore.Fix? = null
            while (System.currentTimeMillis() < deadline && fix == null) {
                delay(500)
                val cur = WatchLocationStore.get(context)
                if (cur != null && cur.requestId == handle.requestId) fix = cur
            }
            // Reverse-geocode if we got a fix.
            if (fix != null) {
                gpsLat = fix.lat; gpsLng = fix.lng
                if (amapApiKey.isNotBlank()) {
                    prefillLocation = AmapGeocoder.reverseGeocode(fix.lat, fix.lng, amapApiKey)
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
            list     = selectedList,
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
        // Append to journal with location line
        val now = System.currentTimeMillis()
        val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(now))
        val loc = prefillLocation.ifBlank { "" }
        val locationPart = if (loc.isNotBlank()) " · $loc" else ""
        val entryLine = "\n\n---\n\n### $stamp$locationPart\n\n$text"
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
            lat = gpsLat,
            lng = gpsLng,
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
                WatchScheduleSync.push(context, events)
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
                prefillLocation = prefillLocation,
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
                        listLabel,
                    ),
                    selected  = phoneTab,
                    onSelect  = { selected ->
                        if (selected == 1 && phoneTab == 1) showListSheet = true
                        phoneTab = selected
                    }
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

    // ── List picker (same style as AddEventTypeSheet) ──────────────────
    if (showListSheet) {
        ModalBottomSheet(
            onDismissRequest = { showListSheet = false },
            sheetState       = listSheetState,
            containerColor   = Color(0xFF1A1A1A),
            dragHandle       = null,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            ) {
                // 拖拽把手
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.width(36.dp).height(4.dp)
                            .background(Color(0xFF444444), RoundedCornerShape(2.dp))
                    )
                }
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("清单", color = Color(0xFFE0D8C8), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Icon(
                        Icons.Default.Close, contentDescription = "Close", tint = DIM,
                        modifier = Modifier.size(18.dp).clickable { showListSheet = false },
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 清单项
                val current = selectedList
                EventList.ALL.forEach { item ->
                    val icon = when (item) {
                        is EventList.Today -> Icons.Filled.Today
                        is EventList.Inbox -> Icons.Filled.Inbox
                    }
                    val itemLabel = when (item) {
                        is EventList.Today -> stringResource(R.string.list_today)
                        is EventList.Inbox -> stringResource(R.string.list_inbox)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedList = item; showListSheet = false
                        }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(52.dp)
                                .background(Color(0xFF2E2E2E), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = null,
                                tint = if (current.key == item.key) GOLD else Color(0xFFE0D8C8),
                                modifier = Modifier.size(26.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(itemLabel, color = if (current.key == item.key) GOLD else Color(0xFFE0D8C8),
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when (item) {
                                    is EventList.Today -> "仅显示今天的事项"
                                    is EventList.Inbox -> "所有未删除的事项"
                                },
                                color = DIM, fontSize = 13.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 预留：新建清单（同样式）
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        showListSheet = false
                    }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier.size(52.dp)
                            .background(Color(0xFF2E2E2E), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null,
                            tint = DIM, modifier = Modifier.size(26.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.list_new), color = DIM,
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("创建自定义清单", color = DIM, fontSize = 13.sp)
                    }
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
