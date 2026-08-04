package com.hamhuo.tplanner

import android.util.Log
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.hamhuo.tplanner.timeline.TimelineScreen
import com.hamhuo.tplanner.persistence.DraftCommitResult
import com.hamhuo.tplanner.persistence.DraftConflict
import com.hamhuo.tplanner.persistence.EventDraftRecovery
import com.hamhuo.tplanner.persistence.EventEditStage
import com.hamhuo.tplanner.persistence.journalOnceMarker
import com.hamhuo.tplanner.persistence.PendingActionCommitResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

private const val LLM_LOG_TAG = "TplannerLLM"
private const val PRIMARY_NAVIGATION_VISIBLE_MILLIS = 2_500L

private enum class ChromeMode {
    Minimal,
    PrimaryNavigation,
    TimelineNavigation,
}

private data class JournalConflictPrompt(val details: DraftConflict) {
    val date: String get() = details.target.entityId
    val draftText: String get() = details.draftContent
}

private fun Throwable.locationForLog(): String {
    val frame = stackTrace.firstOrNull { it.className.startsWith("com.hamhuo.tplanner") }
        ?: stackTrace.firstOrNull()
        ?: return "unknown"
    return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    store: JournalStore,
    eventStore: EventStore,
    manager: LanSyncManager,
    deepseekService: DeepSeekAnalysisService?,
    amapApiKey: String,
    scheduleTriggerCount: Int,
    initialContent: String,
    initialEvents: List<TaskEvent>,
    initialJournalDate: String,
    initialJournalRecovery: JournalDraftRecovery,
    initialServerUrl: String,
    initialEventRecovery: EventDraftRecovery?,
    untangleStore: UntangleStateStore,
    initialUntangleState: UntangleRecoveryState?,
    onScheduleSheetReady: () -> Unit,
) {
    val scope  = rememberCoroutineScope()
    val context = LocalContext.current
    var content    by remember { mutableStateOf(initialContent) }
    var panelOpen  by remember { mutableStateOf(false) }
    var events     by remember { mutableStateOf(initialEvents) }
    var journalHasDraft by remember {
        mutableStateOf(initialJournalRecovery !is JournalDraftRecovery.None)
    }
    var journalConflict by remember {
        mutableStateOf(
            (initialJournalRecovery as? JournalDraftRecovery.Conflict)
                ?.let { JournalConflictPrompt(it.details) }
        )
    }
    val journalWriteMutex = remember { Mutex() }
    val eventWriteMutex = remember { Mutex() }
    // Freeze only while editing/recovering a draft. An Activity kept alive across midnight should
    // move to the new day once the previous session has safely committed.
    var journalEditing by remember { mutableStateOf(false) }
    var journalDate by remember {
        mutableStateOf(
            runCatching { java.time.LocalDate.parse(initialJournalDate) }
                .getOrDefault(appToday())
        )
    }
    val journalDateKey = journalDate.toString()

    LaunchedEffect(journalEditing, journalHasDraft) {
        while (!journalEditing && !journalHasDraft) {
            val today = appToday()
            if (today != journalDate) {
                journalDate = today
                journalConflict = null
            }
            delay(30_000L)
        }
    }

    LaunchedEffect(eventStore) {
        eventStore.observeAll().collect { storedEvents -> events = storedEvents }
    }
    LaunchedEffect(store, journalDateKey) {
        store.observe(journalDateKey).collect { entry ->
            if (!journalHasDraft) {
                content = entry?.takeIf { it.deletedAt == 0L }?.text.orEmpty()
            }
        }
    }

    fun saveJournalDraft(text: String) {
        journalHasDraft = true
        store.enqueueDraft(journalDateKey, text)
    }

    fun commitJournalDraft(text: String) {
        journalHasDraft = true
        store.enqueueDraft(journalDateKey, text)
        scope.launch {
            try {
                val result = journalWriteMutex.withLock {
                    store.commitDraft(journalDateKey, text)
                }
                when (result) {
                    DraftCommitResult.Saved,
                    DraftCommitResult.AlreadySaved,
                    -> journalHasDraft = false
                    is DraftCommitResult.Conflict -> {
                        journalHasDraft = true
                        journalConflict = JournalConflictPrompt(result.details)
                        Toast.makeText(
                            context,
                            "内容已在其他设备修改，当前草稿已安全保留",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (_: Exception) {
                journalHasDraft = true
                Toast.makeText(context, "保存失败，草稿仍保留在本机", Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun refreshJournalRecovery(date: String) {
        when (val recovery = store.getDraftRecovery(date)) {
            JournalDraftRecovery.None -> {
                if (journalConflict?.date == date) journalConflict = null
                if (date == journalDateKey) {
                    journalHasDraft = false
                    content = store.get(date)
                }
            }
            is JournalDraftRecovery.Recovered -> {
                if (journalConflict?.date == date) journalConflict = null
                if (date == journalDateKey) {
                    journalHasDraft = true
                    content = recovery.text
                }
            }
            is JournalDraftRecovery.Conflict -> {
                journalConflict = JournalConflictPrompt(recovery.details)
                if (date == journalDateKey) {
                    journalHasDraft = true
                    content = recovery.text
                }
            }
        }
    }

    // ── Sync state ───────────────────────────────────────────────────────
    var serverUrl  by remember { mutableStateOf(initialServerUrl) }
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
                    refreshJournalRecovery(journalDateKey)
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
                val recovered = store.getDraft(journalDateKey)
                journalHasDraft = recovered != null
                content = recovered ?: store.get(journalDateKey)
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
    var phoneTab by rememberSaveable { mutableStateOf(0) } // 0=Notes, 1=Inbox, 2=Timeline
    var chromeMode by remember { mutableStateOf(ChromeMode.PrimaryNavigation) }
    var primaryNavigationGeneration by remember { mutableIntStateOf(0) }
    var selectedListKey by rememberSaveable { mutableStateOf(EventList.Inbox.key) }
    val selectedList = EventList.fromKey(selectedListKey)
    var showListSheet by remember { mutableStateOf(false) }
    var taskWidgetModalVisible by remember { mutableStateOf(false) }
    var timelineModalVisible by remember { mutableStateOf(false) }
    val listSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val phoneTabStateHolder = rememberSaveableStateHolder()

    // ── Schedule extraction sheet ───────────────────────────────────────
    var showScheduleSheet by remember { mutableStateOf(initialUntangleState != null) }
    var thinking by remember { mutableStateOf(false) }
    var sheetAction by remember {
        mutableStateOf(initialUntangleState?.proposal)
    }
    var sheetRequestId by remember { mutableStateOf(initialUntangleState?.requestId.orEmpty()) }
    var untangleInput by remember { mutableStateOf(initialUntangleState?.inputText.orEmpty()) }
    var prefillLocation by remember { mutableStateOf(initialUntangleState?.location.orEmpty()) }
    var gpsLat by remember { mutableStateOf(initialUntangleState?.lat ?: 0.0) }
    var gpsLng by remember { mutableStateOf(initialUntangleState?.lng ?: 0.0) }
    var untangleJournalDate by remember {
        mutableStateOf(initialUntangleState?.journalDate.orEmpty())
    }
    var untangleJournalLine by remember {
        mutableStateOf(initialUntangleState?.journalLine.orEmpty())
    }
    var untangleSubmissionInput by remember {
        mutableStateOf(initialUntangleState?.submissionInput.orEmpty())
    }
    var untangleSubmissionStamp by remember {
        mutableStateOf(initialUntangleState?.submissionStamp.orEmpty())
    }
    var untangleSubmissionLocation by remember {
        mutableStateOf(initialUntangleState?.submissionLocation.orEmpty())
    }
    var untangleRequiresFreshSubmission by remember {
        mutableStateOf(initialUntangleState?.requiresFreshSubmission == true)
    }
    val untangleWriteMutex = remember { Mutex() }

    fun untangleSnapshot(
        phase: UntanglePhase,
        proposal: DeepSeekAnalysisService.ProposedAction? = sheetAction,
    ): UntangleRecoveryState {
        val requestId = sheetRequestId.ifBlank {
            "ui-${UUID.randomUUID()}".also { sheetRequestId = it }
        }
        return UntangleRecoveryState(
            requestId = requestId,
            inputText = untangleInput,
            location = prefillLocation,
            lat = gpsLat,
            lng = gpsLng,
            phase = phase,
            proposal = proposal,
            journalDate = untangleJournalDate,
            journalLine = untangleJournalLine,
            submissionInput = untangleSubmissionInput,
            submissionStamp = untangleSubmissionStamp,
            submissionLocation = untangleSubmissionLocation,
            requiresFreshSubmission = untangleRequiresFreshSubmission,
        )
    }

    fun saveUntangleState(
        phase: UntanglePhase,
        proposal: DeepSeekAnalysisService.ProposedAction? = sheetAction,
    ) {
        untangleStore.enqueue(untangleSnapshot(phase, proposal))
    }

    fun discardUntangleState(requestId: String = sheetRequestId) {
        if (requestId.isBlank()) return
        scope.launch {
            untangleWriteMutex.withLock { untangleStore.delete(requestId) }
        }
    }

    /** Open the AI schedule-extraction sheet directly (no watch wake required). */
    fun startDirectAiExtraction() {
        if (deepseekService == null) {
            Toast.makeText(context, R.string.ai_service_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            showScheduleSheet = true
            thinking = false
            sheetAction = null
            val previousRequestId = sheetRequestId
            val openedRequestId = "direct-${UUID.randomUUID()}"
            sheetRequestId = openedRequestId
            untangleInput = ""
            prefillLocation = ""
            gpsLat = 0.0; gpsLng = 0.0
            untangleJournalDate = ""
            untangleJournalLine = ""
            untangleSubmissionInput = ""
            untangleSubmissionStamp = ""
            untangleSubmissionLocation = ""
            untangleRequiresFreshSubmission = false
            untangleWriteMutex.withLock {
                untangleStore.replace(
                    previousRequestId,
                    UntangleRecoveryState(
                        requestId = openedRequestId,
                        inputText = "",
                        location = "",
                        lat = 0.0,
                        lng = 0.0,
                        phase = UntanglePhase.EDITING,
                    ),
                )
            }
            Log.i(
                LLM_LOG_TAG,
                "phase=sheet_open source=direct requestId=$openedRequestId " +
                    "serviceConfigured=${deepseekService != null}",
            )
        }
    }

    LaunchedEffect(scheduleTriggerCount) {
        if (scheduleTriggerCount > 0) {
            Log.i(
                LLM_LOG_TAG,
                "phase=sheet_open triggerCount=$scheduleTriggerCount " +
                    "serviceConfigured=${deepseekService != null} locationApiConfigured=${amapApiKey.isNotBlank()}",
            )
            showScheduleSheet = true
            thinking = false
            sheetAction = null
            val previousRequestId = sheetRequestId
            val openedRequestId = "watch-${UUID.randomUUID()}"
            sheetRequestId = openedRequestId
            untangleInput = ""
            prefillLocation = ""
            gpsLat = 0.0; gpsLng = 0.0
            untangleJournalDate = ""
            untangleJournalLine = ""
            untangleSubmissionInput = ""
            untangleSubmissionStamp = ""
            untangleSubmissionLocation = ""
            untangleRequiresFreshSubmission = false
            untangleWriteMutex.withLock {
                untangleStore.replace(
                    previousRequestId,
                    UntangleRecoveryState(
                        requestId = openedRequestId,
                        inputText = "",
                        location = "",
                        lat = 0.0,
                        lng = 0.0,
                        phase = UntanglePhase.EDITING,
                    ),
                )
            }
            // This is the durable-consumption boundary for watch wake requests. Activity launch or
            // setContent alone is insufficient: only now may the phone ACK and let the watch drop it.
            onScheduleSheetReady()

            // Start foreground location capture. primeFreshCache was already
            // called by WakeDataLayerService before the Activity was visible.
            val handle = LocationCapture.start(context)
            Log.d(
                LLM_LOG_TAG,
                "phase=location_capture result=started requestId=${handle.requestId}",
            )

            // Poll WatchLocationStore for a fix matching this capture generation.
            val deadline = System.currentTimeMillis() + 12_000
            var fix: WatchLocationStore.Fix? = null
            while (System.currentTimeMillis() < deadline && fix == null) {
                delay(500)
                val cur = WatchLocationStore.get(context)
                if (cur != null && cur.requestId == handle.requestId) fix = cur
            }
            if (!showScheduleSheet || sheetRequestId != openedRequestId || thinking ||
                sheetAction != null || untangleJournalLine.isNotBlank()
            ) {
                return@LaunchedEffect
            }
            // Resolve into locals first. A submission may finish while geocoding is suspended; in
            // that case its frozen timestamp/location must not be mutated by this late result.
            val resolvedLocation = if (fix != null && amapApiKey.isNotBlank()) {
                AmapGeocoder.reverseGeocode(fix.lat, fix.lng, amapApiKey)
            } else {
                ""
            }
            Log.i(
                LLM_LOG_TAG,
                "phase=location_capture result=${if (fix == null) "timeout" else "fix"} " +
                "reverseGeocoded=${resolvedLocation.isNotBlank()}",
            )
            if (!showScheduleSheet || sheetRequestId != openedRequestId || thinking ||
                sheetAction != null || untangleJournalLine.isNotBlank()
            ) {
                return@LaunchedEffect
            }
            if (fix != null) {
                gpsLat = fix.lat
                gpsLng = fix.lng
                prefillLocation = resolvedLocation
            }
            // Location belongs to the same durable request. Persist it before the user can submit
            // so a process restart does not silently drop a fix that was already displayed.
            untangleWriteMutex.withLock {
                if (thinking || sheetAction != null || sheetRequestId != openedRequestId ||
                    untangleJournalLine.isNotBlank()
                ) {
                    return@withLock
                }
                untangleStore.save(untangleSnapshot(UntanglePhase.EDITING, proposal = null))
            }
        }
    }

    // ── Panel building blocks ────────────────────────────────────────────
    val notesCardContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                NotesHeader(
                    date = journalDate,
                    syncStatus = syncStatus,
                    onPanelToggle = { panelOpen = !panelOpen }
                )
                HorizontalDivider(color = BORDER, thickness = 1.dp)
                MarkdownField(
                    content = content,
                    onEditStart = {
                        val recovery = journalWriteMutex.withLock { store.beginDraft(journalDateKey) }
                        val sessionText = when (recovery) {
                            JournalDraftRecovery.None -> store.get(journalDateKey)
                            is JournalDraftRecovery.Recovered -> recovery.text
                            is JournalDraftRecovery.Conflict -> recovery.text
                        }
                        content = sessionText
                        journalHasDraft = recovery != JournalDraftRecovery.None
                        sessionText
                    },
                    onSave = { text ->
                        content = text
                        commitJournalDraft(text)
                    },
                    onDraftChange = { text ->
                        content = text
                        saveJournalDraft(text)
                    },
                    onEditingChange = { journalEditing = it },
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
            if (deepseekService != null) {
                IconButton(
                    onClick = { startDirectAiExtraction() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp)
                        .size(40.dp)
                        .background(GOLD, CircleShape),
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = stringResource(R.string.ai_schedule_extraction),
                        tint = BG,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    val initiallyRecoveredEvent = initialEventRecovery as? EventDraftRecovery.Recovered
    val initialEventStage = initiallyRecoveredEvent?.stage ?: EventEditStage.DETAIL
    var pendingNewEvent by remember {
        mutableStateOf(
            initiallyRecoveredEvent?.event?.takeIf { initialEventStage == EventEditStage.NAMING }
        )
    }
    var editingEvent by remember {
        mutableStateOf(
            initiallyRecoveredEvent?.event?.takeUnless { initialEventStage == EventEditStage.NAMING }
        )
    }
    var eventConflict by remember {
        mutableStateOf(initialEventRecovery as? EventDraftRecovery.Conflict)
    }

    suspend fun revealNextEventDraft() {
        when (val next = eventStore.latestEventDraftRecovery()) {
            is EventDraftRecovery.Recovered -> {
                if (next.stage == EventEditStage.NAMING) pendingNewEvent = next.event
                else editingEvent = next.event
            }
            is EventDraftRecovery.Conflict -> eventConflict = next
            EventDraftRecovery.None,
            null,
            -> Unit
        }
    }

    fun beginNewEvent(type: String) {
        val now = Instant.now()
        val draft = TaskEvent(
            id = UUID.randomUUID().toString(),
            title = "",
            type = type,
            start = now,
            end = now.plusSeconds(3_600),
            completed = false,
            checklist = emptyList(),
            colorId = 0,
            note = "",
            deletedAt = 0L,
            updatedAt = now.toEpochMilli(),
            alarmEnabled = type == "event",
            alarmOffsetMinutes = 0,
        )
        scope.launch {
            try {
                eventWriteMutex.withLock {
                    eventStore.saveEventDraft(draft, EventEditStage.NAMING)
                }
                pendingNewEvent = draft
            } catch (_: Exception) {
                Toast.makeText(context, "无法保存新事项草稿，请重试", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openEvent(event: TaskEvent) {
        scope.launch {
            try {
                when (val recovery = eventStore.recoverEventDraft(event.id)) {
                    is EventDraftRecovery.Recovered -> {
                        if (recovery.stage == EventEditStage.NAMING) {
                            pendingNewEvent = recovery.event
                        } else {
                            editingEvent = recovery.event
                        }
                    }
                    is EventDraftRecovery.Conflict -> {
                        eventConflict = recovery
                        Toast.makeText(
                            context,
                            "该事项已在其他设备修改，请选择如何处理已保留的草稿",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    EventDraftRecovery.None -> {
                        val current = eventWriteMutex.withLock {
                            eventStore.beginEventEdit(event)
                        }
                        editingEvent = current
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(context, "无法打开事项草稿，请重试", Toast.LENGTH_LONG).show()
            }
        }
    }
    val chromeHidden = showScheduleSheet ||
        showListSheet ||
        taskWidgetModalVisible ||
        timelineModalVisible ||
        pendingNewEvent != null ||
        editingEvent != null ||
        eventConflict != null

    LaunchedEffect(chromeHidden) {
        if (chromeHidden) chromeMode = ChromeMode.Minimal
    }
    LaunchedEffect(
        chromeMode,
        phoneTab,
        primaryNavigationGeneration,
        chromeHidden,
    ) {
        if (!chromeHidden && chromeMode == ChromeMode.PrimaryNavigation) {
            delay(PRIMARY_NAVIGATION_VISIBLE_MILLIS)
            chromeMode = ChromeMode.Minimal
        }
    }

    val taskCardContent: @Composable () -> Unit = {
        TaskWidget(
            events   = events,
            list     = selectedList,
            onToggle = { eventId, completed ->
                val nextEvents = events.map {
                    if (it.id == eventId) it.copy(completed = completed, updatedAt = System.currentTimeMillis()) else it
                }
                events = nextEvents
                nextEvents.firstOrNull { it.id == eventId }?.let { updated ->
                    scope.launch {
                        eventWriteMutex.withLock { eventStore.save(updated) }
                        events = manager.fetchEvents(serverUrl)
                    }
                }
            },
            onAddEvent = ::beginNewEvent,
            onDelete = { eventId ->
                val now = System.currentTimeMillis()
                val nextEvents = events.map {
                    if (it.id == eventId) it.copy(deletedAt = now, updatedAt = now) else it
                }
                events = nextEvents
                nextEvents.firstOrNull { it.id == eventId }?.let { updated ->
                    scope.launch {
                        eventWriteMutex.withLock { eventStore.save(updated) }
                        events = manager.fetchEvents(serverUrl)
                    }
                }
            },
            onItemClick = ::openEvent,
            onListFilterClick = { showListSheet = true },
            onTypeChange = { eventId, newType ->
                val nextEvents = events.map {
                    if (it.id == eventId) {
                        it.copy(
                            type = newType,
                            completed = if (newType == "task") it.completed else false,
                            checklist = if (newType == "task") it.checklist else emptyList(),
                            updatedAt = System.currentTimeMillis()
                        )
                    } else it
                }
                events = nextEvents
                nextEvents.firstOrNull { it.id == eventId }?.let { updated ->
                    scope.launch {
                        eventWriteMutex.withLock { eventStore.save(updated) }
                        events = manager.fetchEvents(serverUrl)
                    }
                }
            },
            onModalVisibilityChange = { taskWidgetModalVisible = it },
        )
    }

    val timelineCardContent: @Composable () -> Unit = {
        TimelineScreen(
            events = events,
            onEventClick = ::openEvent,
            onAddEvent = ::beginNewEvent,
            onEventMove = { event, newStart, newEnd ->
                val updated = event.copy(
                    start = newStart,
                    end = newEnd,
                    updatedAt = System.currentTimeMillis(),
                )
                val nextEvents = events.map { current ->
                    if (current.id == updated.id) updated else current
                }
                events = nextEvents
                scope.launch {
                    eventWriteMutex.withLock { eventStore.save(updated) }
                    WatchScheduleSync.push(context, nextEvents)
                }
            },
            allowExpandedNavigation =
                !chromeHidden && chromeMode != ChromeMode.PrimaryNavigation,
            onNavigationExpandedChange = { expanded ->
                when {
                    expanded -> chromeMode = ChromeMode.TimelineNavigation
                    chromeMode == ChromeMode.TimelineNavigation ->
                        chromeMode = ChromeMode.Minimal
                }
            },
            onModalVisibilityChange = { timelineModalVisible = it },
        )
    }

    // ── Schedule extraction flow ────────────────────────────────────────

    val submitForExtraction: (String) -> Unit = { text ->
        val originalRequestId = sheetRequestId
        val changedSinceSubmitted = untangleRequiresFreshSubmission ||
            (untangleJournalLine.isNotBlank() && untangleSubmissionInput != text)
        val requestId = when {
            changedSinceSubmitted -> "ui-${UUID.randomUUID()}"
            sheetRequestId.isBlank() -> "ui-${UUID.randomUUID()}"
            else -> sheetRequestId
        }
        sheetRequestId = requestId
        untangleInput = text
        untangleRequiresFreshSubmission = false
        val now = System.currentTimeMillis()
        val isExactRetry = !changedSinceSubmitted &&
            untangleJournalLine.isNotBlank() &&
            untangleJournalDate.isNotBlank()
        val stamp = if (isExactRetry) {
            untangleSubmissionStamp
        } else {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply {
                timeZone = appLegacyTimeZone()
            }.format(java.util.Date(now))
        }
        val loc = if (isExactRetry) untangleSubmissionLocation else prefillLocation.ifBlank { "" }
        val journalDate = if (isExactRetry) untangleJournalDate else appToday().toString()
        val locationPart = if (loc.isNotBlank()) " · $loc" else ""
        // appendTodayOnce adds one separator newline and its own hidden idempotency marker.
        val entryLine = if (isExactRetry) {
            untangleJournalLine
        } else {
            "\n---\n\n### $stamp$locationPart\n\n$text"
        }
        untangleJournalDate = journalDate
        untangleJournalLine = entryLine
        untangleSubmissionInput = text
        untangleSubmissionStamp = stamp
        untangleSubmissionLocation = loc
        val thinkingState = UntangleRecoveryState(
            requestId = requestId,
            inputText = text,
            location = loc,
            lat = gpsLat,
            lng = gpsLng,
            phase = UntanglePhase.THINKING,
            journalDate = journalDate,
            journalLine = entryLine,
            submissionInput = text,
            submissionStamp = stamp,
            submissionLocation = loc,
        )

        Log.i(
            LLM_LOG_TAG,
            "request=$requestId phase=submit inputChars=${text.length} locationProvided=${loc.isNotBlank()} " +
                "serviceConfigured=${deepseekService != null}",
        )
        thinking = true
        sheetAction = null
        scope.launch {
            try {
                // Persist the request before any irreversible side effect. On recovery THINKING is
                // presented as EDITING, so retry uses the same requestId.
                untangleWriteMutex.withLock {
                    if (changedSinceSubmitted && originalRequestId.isNotBlank()) {
                        untangleStore.replace(originalRequestId, thinkingState)
                    } else {
                        untangleStore.save(thinkingState)
                    }
                }

                val (durableText, journalConflictDetails) = journalWriteMutex.withLock {
                    val recoveredDraft = store.getDraft(journalDate)
                    if (recoveredDraft == null) {
                        store.appendOnce(journalDate, requestId, entryLine)
                        store.get(journalDate) to null
                    } else {
                        val marker = journalOnceMarker(requestId)
                        val candidate = if (marker in recoveredDraft) {
                            recoveredDraft
                        } else {
                            recoveredDraft.trimEnd() + "\n" + entryLine + "\n" + marker
                        }
                        store.saveDraft(journalDate, candidate)
                        val commit = store.commitDraft(journalDate, candidate)
                        candidate to (commit as? DraftCommitResult.Conflict)?.details
                    }
                }
                val hasConflict = journalConflictDetails != null
                if (journalDate == journalDateKey) {
                    content = durableText
                    journalHasDraft = hasConflict
                }
                if (hasConflict) {
                    journalConflict = JournalConflictPrompt(requireNotNull(journalConflictDetails))
                    Toast.makeText(
                        context,
                        "日记已在其他设备修改；本次记录和原草稿均已保留，未自动覆盖",
                        Toast.LENGTH_LONG,
                    ).show()
                }

                if (sheetRequestId != requestId || !showScheduleSheet) {
                    untangleWriteMutex.withLock { untangleStore.delete(requestId) }
                    return@launch
                }
                val action = deepseekService?.extractSchedule(text, stamp, loc, requestId)
                if (sheetRequestId != requestId || !showScheduleSheet) {
                    untangleWriteMutex.withLock { untangleStore.delete(requestId) }
                    return@launch
                }

                if (action != null) {
                    Log.i(
                        LLM_LOG_TAG,
                        "request=${action.requestId} phase=route result=proposal type=${action.type} " +
                            "titlePresent=${action.title.isNotBlank()} checklistCount=${action.checklist.size} " +
                            "alarmEnabled=${action.alarmEnabled}",
                    )
                    // The proposal must be durable before its confirm button becomes clickable.
                    untangleWriteMutex.withLock {
                        untangleStore.save(thinkingState.copy(
                            phase = UntanglePhase.PROPOSAL,
                            proposal = action,
                            updatedAt = System.currentTimeMillis(),
                        ))
                    }
                    if (sheetRequestId != requestId || !showScheduleSheet) {
                        untangleWriteMutex.withLock { untangleStore.delete(requestId) }
                        return@launch
                    }
                    sheetAction = action
                    thinking = false
                } else {
                    Log.w(
                        LLM_LOG_TAG,
                        "request=$requestId phase=route result=unavailable " +
                            "serviceConfigured=${deepseekService != null}",
                    )
                    untangleWriteMutex.withLock {
                        untangleStore.save(thinkingState.copy(
                            phase = UntanglePhase.EDITING,
                            updatedAt = System.currentTimeMillis(),
                        ))
                    }
                    if (sheetRequestId == requestId && showScheduleSheet) {
                        thinking = false
                        Toast.makeText(
                            context,
                            R.string.ai_service_unavailable,
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        untangleWriteMutex.withLock { untangleStore.delete(requestId) }
                    }
                }
            } catch (error: Exception) {
                Log.e(
                    LLM_LOG_TAG,
                    "request=$requestId phase=submit result=failed " +
                        "errorType=${error.javaClass.simpleName} at=${error.locationForLog()}",
                )
                runCatching {
                    untangleWriteMutex.withLock {
                        if (showScheduleSheet && sheetRequestId == requestId) {
                            untangleStore.save(thinkingState.copy(
                                phase = UntanglePhase.EDITING,
                                updatedAt = System.currentTimeMillis(),
                            ))
                        } else {
                            untangleStore.delete(requestId)
                        }
                    }
                }
                if (showScheduleSheet && sheetRequestId == requestId) {
                    thinking = false
                    Toast.makeText(
                        context,
                        R.string.schedule_create_failed_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    fun confirmAction(act: DeepSeekAnalysisService.ProposedAction) {
        val requestId = act.requestId.ifBlank { sheetRequestId }
        if (requestId.isBlank()) {
            Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val start = parseAgentDatetime(act.startIso)
        val end = parseAgentDatetime(act.endIso)
        if (start == null || end == null || !end.isAfter(start)) {
            Log.w(
                LLM_LOG_TAG,
                "request=$requestId phase=tool_validate tool=create_schedule result=invalid_datetime " +
                    "startParsed=${start != null} endParsed=${end != null} " +
                    "endAfterStart=${start != null && end != null && end.isAfter(start)}",
            )
            Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
            sheetAction = null
            saveUntangleState(UntanglePhase.EDITING, proposal = null)
            return
        }
        Log.i(
            LLM_LOG_TAG,
            "request=$requestId phase=tool_validate tool=create_schedule result=accepted type=${act.type} " +
                "titlePresent=${act.title.isNotBlank()} checklistCount=${act.checklist.size} " +
                "alarmEnabled=${act.alarmEnabled} alarmOffsetMinutes=${act.alarmOffsetMinutes}",
        )
        val ev = TaskEvent(
            id = stableUntangleId("event", requestId),
            title = act.title,
            type = act.type,
            start = start,
            end = end,
            completed = false,
            checklist = act.checklist.mapIndexed { index, item ->
                CheckItem(stableUntangleId("check:$index", requestId), item, false)
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
        // Hide the confirmation controls immediately; the database CAS below still protects
        // against two taps delivered before this state change is recomposed.
        thinking = true
        scope.launch {
            try {
                val commitResult = untangleWriteMutex.withLock {
                    eventWriteMutex.withLock {
                        eventStore.saveAndClearPendingAction(ev, requestId)
                    }
                }
                Log.i(
                    LLM_LOG_TAG,
                    "request=$requestId phase=tool_execute tool=create_schedule " +
                        "result=${commitResult.name.lowercase()} " +
                        "alarmEnabled=${act.alarmEnabled}",
                )
                if (commitResult == PendingActionCommitResult.INVALID_STATE) {
                    if (showScheduleSheet && sheetRequestId == requestId) {
                        thinking = false
                        Toast.makeText(
                            context,
                            R.string.schedule_create_failed_toast,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                if (showScheduleSheet && sheetRequestId == requestId) {
                    if (commitResult == PendingActionCommitResult.SAVED) {
                        val toastMessage = when {
                            !act.alarmEnabled ->
                                context.getString(R.string.schedule_created_toast, act.title)
                            TaskAlarmScheduler.canScheduleExactAlarms(context) ->
                                context.getString(R.string.schedule_created_with_alarm_toast, act.title)
                            else -> context.getString(
                                R.string.schedule_created_with_fallback_alarm_toast,
                                act.title,
                            )
                        }
                        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
                    }
                    // Room already committed the event and consumed the proposal. Close this
                    // generation before best-effort network/watch work, so transport failure can
                    // never be reported as a failed creation or expose a dead confirm button.
                    showScheduleSheet = false
                    thinking = false
                    sheetAction = null
                    sheetRequestId = ""
                    untangleInput = ""
                    prefillLocation = ""
                    gpsLat = 0.0
                    gpsLng = 0.0
                    untangleJournalDate = ""
                    untangleJournalLine = ""
                    untangleSubmissionInput = ""
                    untangleSubmissionStamp = ""
                    untangleSubmissionLocation = ""
                    untangleRequiresFreshSubmission = false
                }
                runCatching { manager.fetchEvents(serverUrl) }
                    .onSuccess { refreshed -> events = refreshed }
                    .onFailure { error ->
                        Log.w(LLM_LOG_TAG, "request=$requestId post-commit sync failed", error)
                    }
                runCatching { WatchScheduleSync.push(context, events) }
                    .onFailure { error ->
                        Log.w(LLM_LOG_TAG, "request=$requestId watch push failed", error)
                    }
                Log.i(
                    LLM_LOG_TAG,
                    "request=$requestId phase=tool_execute tool=create_schedule " +
                        "result=completed visibleEventCount=${events.size}",
                )
            } catch (e: Exception) {
                Log.e(
                    LLM_LOG_TAG,
                    "request=$requestId phase=tool_execute tool=create_schedule result=failed " +
                        "errorType=${e.javaClass.simpleName} at=${e.locationForLog()}",
                )
                if (showScheduleSheet && sheetRequestId == requestId) {
                    thinking = false
                    Toast.makeText(
                        context,
                        R.string.schedule_create_failed_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // ── Main layout ──────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(BG).windowInsetsPadding(WindowInsets.systemBars)) {
        if (showScheduleSheet) {
            UntangleSheet(
                requestId = sheetRequestId,
                prefillLocation = prefillLocation,
                initialText = untangleInput,
                thinking = thinking,
                action = sheetAction,
                onTextChange = { text ->
                    untangleInput = text
                    saveUntangleState(UntanglePhase.EDITING, proposal = null)
                },
                onDismiss = {
                    val dismissedRequestId = sheetRequestId
                    Log.d(
                        LLM_LOG_TAG,
                        "request=${sheetAction?.requestId ?: sheetRequestId.ifBlank { "none" }} " +
                            "phase=sheet_close reason=dismissed",
                    )
                    showScheduleSheet = false
                    thinking = false
                    sheetAction = null
                    sheetRequestId = ""
                    untangleInput = ""
                    discardUntangleState(dismissedRequestId)
                },
                onSubmit = submitForExtraction,
                onConfirmAction = ::confirmAction,
                onDeclineAction = {
                    val declinedRequestId = sheetRequestId
                    Log.d(
                        LLM_LOG_TAG,
                        "request=${sheetAction?.requestId ?: sheetRequestId.ifBlank { "none" }} " +
                            "phase=sheet_close reason=proposal_declined",
                    )
                    showScheduleSheet = false
                    thinking = false
                    sheetAction = null
                    sheetRequestId = ""
                    untangleInput = ""
                    discardUntangleState(declinedRequestId)
                },
            )
        } else if (isPhone) {
            Box(Modifier.fillMaxSize().imePadding()) {
                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 10.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SURFACE),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    phoneTabStateHolder.SaveableStateProvider(phoneTab) {
                        when (phoneTab) {
                            0 -> notesCardContent()
                            1 -> taskCardContent()
                            2 -> timelineCardContent()
                            else -> notesCardContent()
                        }
                    }
                }

                PhoneTabBar(
                    selected = phoneTab,
                    onSelect = { selected ->
                        if (selected == 1 && phoneTab == 1) showListSheet = true
                        phoneTab = selected
                        primaryNavigationGeneration++
                        chromeMode = ChromeMode.PrimaryNavigation
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    presentation = when {
                        chromeHidden -> PhoneTabBarPresentation.Hidden
                        chromeMode == ChromeMode.PrimaryNavigation ->
                            PhoneTabBarPresentation.Expanded
                        else -> PhoneTabBarPresentation.HandleOnly
                    },
                    onExpandRequest = {
                        if (!chromeHidden) {
                            primaryNavigationGeneration++
                            chromeMode = ChromeMode.PrimaryNavigation
                        }
                    },
                )
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
                            selectedListKey = item.key; showListSheet = false
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
    pendingNewEvent?.let { draftEvent ->
        NameInputSheet(
            type = draftEvent.type,
            initialText = draftEvent.title,
            onDraftChange = { name ->
                val updated = draftEvent.copy(title = name)
                pendingNewEvent = updated
                eventStore.enqueueEventDraft(updated, EventEditStage.NAMING)
            },
            onCancel = {
                // Unmount first so no later naming callback can enqueue after the ordered delete.
                pendingNewEvent = null
                scope.launch {
                    try {
                        eventWriteMutex.withLock { eventStore.discardEventDraft(draftEvent.id) }
                    } catch (_: Exception) {
                        pendingNewEvent = draftEvent
                        Toast.makeText(context, "无法丢弃草稿，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onConfirm = { name ->
                val updated = draftEvent.copy(title = name)
                // Latch the naming UI before its DETAIL transition enters the same draft queue.
                pendingNewEvent = null
                scope.launch {
                    try {
                        eventWriteMutex.withLock {
                            eventStore.saveEventDraft(updated, EventEditStage.DETAIL)
                        }
                        editingEvent = updated
                    } catch (_: Exception) {
                        pendingNewEvent = updated
                        Toast.makeText(context, "无法保存事项名称，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    editingEvent?.let { ev ->
        EventDetailScreen(
            event = ev,
            onDraftChange = { snapshot ->
                eventStore.enqueueEventDraft(snapshot, EventEditStage.DETAIL)
            },
            onSave = { updated, onFinished ->
                val nextEvents = upsertEventPreservingOrder(events, updated)
                // Queue the exact final snapshot before starting the Activity-scoped commit.
                eventStore.enqueueEventDraft(updated, EventEditStage.DETAIL)
                scope.launch {
                    try {
                        when (val result = eventWriteMutex.withLock {
                            eventStore.saveAndClearEventDraft(updated)
                        }) {
                            DraftCommitResult.Saved,
                            DraftCommitResult.AlreadySaved,
                            -> {
                                events = nextEvents
                                editingEvent = null
                                onFinished(true)
                            }
                            is DraftCommitResult.Conflict -> {
                                eventConflict = EventDraftRecovery.Conflict(
                                    details = result.details,
                                    event = updated,
                                    stage = EventEditStage.DETAIL,
                                )
                                editingEvent = null
                                Toast.makeText(
                                    context,
                                    "事项已在其他设备修改；草稿已保留，请选择处理方式",
                                    Toast.LENGTH_LONG,
                                ).show()
                                onFinished(true)
                            }
                        }
                    } catch (_: Exception) {
                        Toast.makeText(context, "保存失败，草稿仍已保留", Toast.LENGTH_LONG).show()
                        onFinished(false)
                    }
                }
            },
            onNoteSave = { updated, onFinished ->
                val nextEvents = upsertEventPreservingOrder(events, updated)
                eventStore.enqueueEventDraft(updated, EventEditStage.DETAIL)
                scope.launch {
                    try {
                        when (val result = eventWriteMutex.withLock {
                            eventStore.saveAndClearEventDraft(updated)
                        }) {
                            DraftCommitResult.Saved,
                            DraftCommitResult.AlreadySaved,
                            -> {
                                events = nextEvents
                                editingEvent = updated
                                onFinished(true)
                            }
                            is DraftCommitResult.Conflict -> {
                                eventConflict = EventDraftRecovery.Conflict(
                                    details = result.details,
                                    event = updated,
                                    stage = EventEditStage.DETAIL,
                                )
                                editingEvent = null
                                Toast.makeText(
                                    context,
                                    "事项已在其他设备修改；备注草稿已保留，请选择处理方式",
                                    Toast.LENGTH_LONG,
                                ).show()
                                onFinished(true)
                            }
                        }
                    } catch (_: Exception) {
                        Toast.makeText(context, "保存失败，备注草稿仍已保留", Toast.LENGTH_LONG).show()
                        onFinished(false)
                    }
                }
            }
        )
    }

    journalConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { journalConflict = null },
            title = { Text("日记内容冲突") },
            text = { Text("其他设备已修改当天内容。草稿不会丢失；请选择保留草稿、使用当前版本，或明确覆盖当前版本。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val overwritten = journalWriteMutex.withLock {
                                store.overwriteDraft(conflict.details)
                            }
                            if (overwritten) {
                                if (conflict.date == journalDateKey) {
                                    content = conflict.draftText
                                    journalHasDraft = false
                                }
                                journalConflict = null
                            } else {
                                refreshJournalRecovery(conflict.date)
                            }
                        } catch (_: Exception) {
                            Toast.makeText(context, "覆盖失败，草稿仍已保留", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("覆盖当前") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { journalConflict = null }) {
                        Text("保留草稿")
                    }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                val discarded = journalWriteMutex.withLock {
                                    store.discardDraft(conflict.details)
                                }
                                if (!discarded) {
                                    refreshJournalRecovery(conflict.date)
                                    return@launch
                                }
                                if (conflict.date == journalDateKey) {
                                    content = store.get(journalDateKey)
                                    journalHasDraft = false
                                }
                                journalConflict = null
                            } catch (_: Exception) {
                                Toast.makeText(context, "读取当前版本失败", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("使用当前版本") }
                }
            },
        )
    }

    eventConflict?.let { conflict ->
        val conflictDraft = conflict.event
        AlertDialog(
            onDismissRequest = { eventConflict = null },
            title = { Text("事项草稿冲突") },
            text = {
                Text(
                    if (conflictDraft == null) {
                        "原事项已删除或缺失。草稿仍保存在本机，你可以继续保留或使用当前状态。"
                    } else {
                        "其他设备已修改或删除原事项。可保留草稿、使用当前版本，或把草稿另存为新事项。"
                    }
                )
            },
            confirmButton = {
                if (conflictDraft != null) {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                val saved = eventWriteMutex.withLock {
                                    eventStore.saveConflictAsCopy(conflictDraft, conflict.details)
                                } != null
                                if (!saved) {
                                    eventConflict = null
                                    revealNextEventDraft()
                                    return@launch
                                }
                                eventConflict = null
                                revealNextEventDraft()
                                Toast.makeText(context, "草稿已另存为冲突副本", Toast.LENGTH_LONG).show()
                            } catch (_: Exception) {
                                Toast.makeText(context, "另存失败，原草稿仍已保留", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("另存副本") }
                } else {
                    TextButton(onClick = { eventConflict = null }) { Text("保留草稿") }
                }
            },
            dismissButton = {
                Row {
                    if (conflictDraft != null) {
                        TextButton(onClick = { eventConflict = null }) { Text("保留草稿") }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                val discarded = eventWriteMutex.withLock {
                                    eventStore.discardEventDraft(
                                        conflict.details.target.entityId,
                                        conflict.details,
                                    )
                                }
                                if (!discarded) {
                                    eventConflict = null
                                    revealNextEventDraft()
                                    return@launch
                                }
                                eventConflict = null
                                revealNextEventDraft()
                            } catch (_: Exception) {
                                Toast.makeText(context, "读取当前版本失败", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("使用当前版本") }
                }
            },
        )
    }
}

private fun parseAgentDatetime(iso: String): Instant? = try {
    java.time.LocalDateTime.parse(iso).atZone(APP_ZONE).toInstant()
} catch (_: Exception) { null }

private fun stableUntangleId(namespace: String, requestId: String): String =
    UUID.nameUUIDFromBytes(
        "tplanner:untangle:$namespace:$requestId".toByteArray(Charsets.UTF_8)
    ).toString()
