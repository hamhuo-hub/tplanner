package com.hamhuo.tplanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hamhuo.tplanner.timeline.TimelineScreen
import com.hamhuo.tplanner.ui.components.TPlannerPullToSync
import com.hamhuo.tplanner.ui.components.TPlannerSyncFeedback
import com.hamhuo.tplanner.ui.components.TPlannerSyncFeedbackPresentation
import com.hamhuo.tplanner.designsystem.TPlannerSyncFeedbackTone
import com.hamhuo.tplanner.persistence.DraftCommitResult
import com.hamhuo.tplanner.persistence.EventDraftRecovery
import com.hamhuo.tplanner.persistence.EventEditStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

private const val LLM_LOG_TAG = "TplannerLLM"
private const val PRIMARY_NAVIGATION_VISIBLE_MILLIS = 2_500L
private const val SYNC_SPINNER_VISIBLE_MILLIS = 1_500L
private const val LOCATION_CAPTURE_WAIT_MILLIS = 12_000L
private const val JOURNAL_DAY_POLL_MILLIS = 30_000L

private enum class PhoneLocationState {
    IDLE,
    LOCATING,
    READY,
    UNAVAILABLE,
}

private fun hasPhoneLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

internal enum class ChromeMode {
    Minimal,
    PrimaryNavigation,
    TimelineNavigation,
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
    eventStore: ScheduleItemStore,
    manager: LanSyncManager,
    deepseekService: DeepSeekAnalysisService?,
    amapApiKey: String,
    initialContent: String,
    initialEvents: List<ScheduleItem>,
    initialJournalDate: String,
    initialJournalRecovery: JournalDraftRecovery,
    initialServerUrl: String,
    initialEventRecovery: EventDraftRecovery?,
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
    val currentJournalDateKey by rememberUpdatedState(journalDateKey)

    LaunchedEffect(journalEditing, journalDate) {
        while (!journalEditing) {
            val today = appToday()
            val rollover = planJournalDayRollover(
                displayedDate = journalDate,
                today = today,
                isEditing = journalEditing,
                hasDraft = journalHasDraft,
                content = content,
            )
            if (rollover != null) {
                var rolloverConflict: JournalConflictPrompt? = null
                val canAdvance = rollover.draftContent?.let { draft ->
                    val result = try {
                        journalWriteMutex.withLock {
                            store.commitDraft(rollover.previousDate.toString(), draft)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(
                            "TPlannerJournal",
                            "Unable to commit the previous day's note before rollover",
                            error,
                        )
                        null
                    }
                    when (result) {
                        null -> false
                        DraftCommitResult.Saved,
                        DraftCommitResult.AlreadySaved,
                        -> true
                        is DraftCommitResult.Conflict -> {
                            rolloverConflict = JournalConflictPrompt(result.details)
                            true
                        }
                    }
                } ?: true

                if (canAdvance) {
                    val nextContent = store.get(rollover.nextDate.toString())
                    journalDate = rollover.nextDate
                    content = nextContent
                    journalHasDraft = false
                    journalConflict = rolloverConflict
                        ?: journalConflict?.takeUnless {
                            it.date == rollover.previousDate.toString()
                        }
                    break
                }
            }
            delay(JOURNAL_DAY_POLL_MILLIS)
        }
    }
    val journalActions = remember {
        JournalActions(
            scope, context, store, journalWriteMutex,
            { currentJournalDateKey }, { content }, { content = it },
            { journalHasDraft }, { journalHasDraft = it },
            { journalConflict }, { journalConflict = it },
        )
    }

    LaunchedEffect(eventStore) {
        eventStore.observeAll().collect { storedEvents ->
            // Room is the source of truth for the watch. This initial emission also queues the
            // local snapshot when LAN startup sync fails, while later emissions cover every
            // committed mutation without ever publishing an optimistic UI or draft snapshot.
            events = storedEvents
            WatchScheduleSync.push(context, storedEvents)
        }
    }
    LaunchedEffect(store, journalDateKey) {
        store.observe(journalDateKey).collect { entry ->
            if (!journalHasDraft) {
                content = entry?.takeIf { it.deletedAt == 0L }?.text.orEmpty()
            }
        }
    }

    fun saveJournalDraft(text: String) = journalActions.saveDraft(text)
    fun commitJournalDraft(text: String) = journalActions.commitDraft(text)

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
    var syncFeedback by remember { mutableStateOf<TPlannerSyncFeedbackPresentation?>(null) }
    var syncFeedbackGeneration by remember { mutableIntStateOf(0) }
    // 转圈动画与同步结果解耦:固定展示 SYNC_SPINNER_VISIBLE_MILLIS 后收起,
    // 即使同步迟迟不返回结果也不会一直转。generation 防止旧定时器盖掉新动画。
    var syncSpinnerVisible by remember { mutableStateOf(false) }
    var syncSpinnerGeneration by remember { mutableIntStateOf(0) }

    fun showSyncSpinner() {
        val generation = ++syncSpinnerGeneration
        syncSpinnerVisible = true
        scope.launch {
            delay(SYNC_SPINNER_VISIBLE_MILLIS)
            if (syncSpinnerGeneration == generation) {
                syncSpinnerVisible = false
            }
        }
    }
    val eventActions = remember(serverUrl) {
        ScheduleItemActions(scope, context, eventStore, eventWriteMutex, { url -> manager.fetchEvents(url) }, { serverUrl })
    }

    val syncedTemplate = stringResource(R.string.sync_success_with_name)
    val syncCompleteMessage = stringResource(R.string.sync_complete)
    val syncFailedMessage = stringResource(R.string.sync_failed)
    val unknownSyncError = stringResource(R.string.unknown_error)

    fun serverHost(url: String): String =
        try { java.net.URL(LanSyncManager.normalizeServerUrl(url)).host } catch (_: Exception) { url }

    val onSync: () -> Unit = {
        if (syncStatus != "syncing") {
            // Flip the state before launching so rapid taps cannot queue duplicate full syncs.
            syncStatus = "syncing"
            syncMsg = ""
            showSyncSpinner()
            val requestedServerUrl = serverUrl
            scope.launch {
                val result = try {
                    manager.saveServerUrl(requestedServerUrl)
                    val savedServerUrl = manager.getServerUrl()
                    manager.syncAllOrThrow()
                    Triple(
                        "success",
                        syncedTemplate.format(serverHost(savedServerUrl)),
                        TPlannerSyncFeedbackTone.SUCCESS,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e("TplannerSync", "Manual sync failed", error)
                    Triple(
                        "error",
                        error.message ?: unknownSyncError,
                        TPlannerSyncFeedbackTone.ERROR,
                    )
                } finally {
                    // Journal sync may have committed before a later event sync failure.
                    runCatching { refreshJournalRecovery(journalDateKey) }
                }
                // 结果拿到即展示,不再等待转圈动画补足时长。
                syncStatus = result.first
                syncMsg = result.second
                syncFeedbackGeneration++
                syncFeedback = TPlannerSyncFeedbackPresentation(
                    generation = syncFeedbackGeneration,
                    message = if (result.first == "success") syncCompleteMessage else syncFailedMessage,
                    tone = result.third,
                )
            }
        } else {
            // 同步仍在进行:重亮一次转圈提示,避免下拉后界面毫无反应。
            showSyncSpinner()
        }
    }

    LaunchedEffect(Unit) {
        syncStatus = "syncing"
        syncMsg = ""
        showSyncSpinner()
        try {
            manager.syncAllOrThrow(serverUrl)
            syncStatus = "success"
            syncMsg = syncedTemplate.format(serverHost(serverUrl))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            syncStatus = "idle"
        } finally {
            runCatching { refreshJournalRecovery(journalDateKey) }
        }
    }

    val isPhone = LocalConfiguration.current.screenWidthDp < 840
    var phoneTab by rememberSaveable { mutableStateOf(0) } // 0=Notes, 1=Inbox, 2=Timeline
    var chromeMode by remember { mutableStateOf(ChromeMode.PrimaryNavigation) }
    var primaryNavigationGeneration by remember { mutableIntStateOf(0) }
    var selectedViewKey by rememberSaveable { mutableStateOf(TaskView.Inbox.key) }
    var userLists by remember { mutableStateOf(emptyList<UserList>()) }
    LaunchedEffect(eventStore) {
        eventStore.observeUserLists().collect { userLists = it }
    }
    val selectedView = TaskView.fromKey(selectedViewKey, userLists)
    var showListSheet by remember { mutableStateOf(false) }
    var showNewListSheet by remember { mutableStateOf(false) }
    var taskWidgetModalVisible by remember { mutableStateOf(false) }
    var timelineModalVisible by remember { mutableStateOf(false) }
    val listSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // ── Schedule extraction sheet ───────────────────────────────────────
    var showScheduleSheet by remember { mutableStateOf(false) }
    var openingScheduleSheet by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var sheetAction by remember { mutableStateOf<DeepSeekAnalysisService.ProposedAction?>(null) }
    var sheetRequestId by remember { mutableStateOf("") }
    var untangleInput by remember { mutableStateOf("") }
    var prefillLocation by remember { mutableStateOf("") }
    var gpsLat by remember { mutableStateOf(0.0) }
    var gpsLng by remember { mutableStateOf(0.0) }
    var phoneLocationState by remember { mutableStateOf(PhoneLocationState.IDLE) }
    var pendingLocationRequestId by remember { mutableStateOf<String?>(null) }
    var locationPermissionGeneration by remember { mutableIntStateOf(0) }
    var locationPermissionInFlight by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var phoneLocationForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var activeLocationHandle by remember { mutableStateOf<LocationCapture.Handle?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        locationPermissionInFlight = false
        if (hasPhoneLocationPermission(context) && pendingLocationRequestId != null) {
            locationPermissionGeneration++
        } else {
            pendingLocationRequestId = null
            if (phoneLocationState == PhoneLocationState.LOCATING)
                phoneLocationState = PhoneLocationState.UNAVAILABLE
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> phoneLocationForeground = true
                Lifecycle.Event.ON_STOP -> {
                    phoneLocationForeground = false
                    activeLocationHandle?.let(LocationCapture::cancel)
                    activeLocationHandle = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(showScheduleSheet, pendingLocationRequestId, locationPermissionGeneration,
        phoneLocationForeground, thinking, sheetAction) {
        val targetRequestId = pendingLocationRequestId ?: return@LaunchedEffect
        if (!phoneLocationForeground || !showScheduleSheet || thinking || sheetAction != null) return@LaunchedEffect
        if (!hasPhoneLocationPermission(context)) return@LaunchedEffect
        val fix = LocationCapture.capture(context) ?: run {
            phoneLocationState = PhoneLocationState.UNAVAILABLE; return@LaunchedEffect
        }
        val resolvedLocation = AmapGeocoder.reverseGeocode(fix.lat, fix.lng, amapApiKey)
        if (showScheduleSheet && !thinking && sheetAction == null) {
            gpsLat = fix.lat; gpsLng = fix.lng; prefillLocation = resolvedLocation
            phoneLocationState = if (resolvedLocation.isBlank()) PhoneLocationState.UNAVAILABLE else PhoneLocationState.READY
        }
        pendingLocationRequestId = null
    }

    fun startDirectAiExtraction() {
        if (deepseekService == null) {
            Toast.makeText(context, R.string.ai_service_unavailable, Toast.LENGTH_SHORT).show(); return
        }
        if (showScheduleSheet || openingScheduleSheet) return
        openingScheduleSheet = true
        scope.launch {
            try {
                showScheduleSheet = true; thinking = false; sheetAction = null
                val openedRequestId = "direct-${UUID.randomUUID()}"
                sheetRequestId = openedRequestId
                untangleInput = ""; prefillLocation = ""; gpsLat = 0.0; gpsLng = 0.0
                phoneLocationState = PhoneLocationState.LOCATING
                if (showScheduleSheet && sheetRequestId == openedRequestId && phoneLocationForeground) {
                    pendingLocationRequestId = openedRequestId
                    if (!hasPhoneLocationPermission(context)) {
                        locationPermissionInFlight = true
                        runCatching {
                            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        }.onFailure { locationPermissionInFlight = false; pendingLocationRequestId = null; phoneLocationState = PhoneLocationState.UNAVAILABLE }
                    }
                }
            } finally { openingScheduleSheet = false }
        }
    }

    // ── Panel building blocks ────────────────────────────────────────────
    val notesCardContent: @Composable () -> Unit = {
        TPlannerPullToSync(
            isSyncing = syncSpinnerVisible,
            onSync = onSync,
            enabled = isPhone,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    NotesHeader(
                        date = journalDate,
                        onPanelToggle = { panelOpen = !panelOpen },
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
                        onPullRefresh = if (isPhone) onSync else null,
                        placeholder = stringResource(R.string.journal_edit_hint),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (panelOpen) {
                    SyncSettingsPanel(
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 50.dp, end = 8.dp),
                        serverUrl = serverUrl,
                        syncStatus = syncStatus,
                        syncMsg = syncMsg,
                        onUrlChange = { serverUrl = it },
                        onClose = { panelOpen = false },
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
    }

    val initiallyRecoveredEvent = initialEventRecovery as? EventDraftRecovery.Recovered
    val initialEventStage = initiallyRecoveredEvent?.stage ?: EventEditStage.DETAIL
    var pendingNewItem by remember {
        mutableStateOf(
            initiallyRecoveredEvent?.event?.takeIf { initialEventStage == EventEditStage.NAMING }
        )
    }
    var editingItem by remember {
        mutableStateOf(
            initiallyRecoveredEvent?.event?.takeUnless { initialEventStage == EventEditStage.NAMING }
        )
    }
    var itemConflict by remember {
        mutableStateOf(initialEventRecovery as? EventDraftRecovery.Conflict)
    }

    fun revealNextEventDraft() {
        eventActions.revealNextDraft(
            onPending = { pendingNewItem = it },
            onEdit = { editingItem = it },
            onConflict = { itemConflict = it },
        )
    }

    fun beginNewItem(type: String) {
        eventActions.beginNewItem(type, selectedView.listIdForNewItem()) { pendingNewItem = it }
    }

    fun beginTaskAt(start: Instant) {
        eventActions.beginNewItem(
            type = "task",
            listId = selectedView.listIdForNewItem(),
            initialStart = start,
        ) { pendingNewItem = it }
    }

    fun openItem(event: ScheduleItem) {
        eventActions.openItem(
            event = event,
            onPending = { pendingNewItem = it },
            onEdit = { editingItem = it },
            onConflict = { itemConflict = it },
        )
    }
    val chromeHidden = showScheduleSheet ||
        showListSheet ||
        taskWidgetModalVisible ||
        timelineModalVisible ||
        pendingNewItem != null ||
        editingItem != null ||
        itemConflict != null

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
        TPlannerPullToSync(
            isSyncing = syncSpinnerVisible,
            onSync = onSync,
            enabled = isPhone,
            modifier = Modifier.fillMaxSize(),
        ) {
            TaskWidget(
                events = events,
                view = selectedView,
                onAddEvent = ::beginNewItem,
                onDelete = { eventId ->
                    eventActions.softDelete(events, eventId) { events = it }
                },
                onItemClick = ::openItem,
                onViewPickerClick = { showListSheet = true },
                onTypeChange = { eventId, newType ->
                    eventActions.changeType(events, eventId, newType) { events = it }
                },
                onModalVisibilityChange = { taskWidgetModalVisible = it },
            )
        }
    }

    val timelineCardContent: @Composable () -> Unit = {
        TimelineScreen(
            events = events,
            onEventClick = ::openItem,
            onAddEvent = ::beginNewItem,
            onAddTaskAt = ::beginTaskAt,
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

    val submitForExtraction: (String) -> Unit = lambda@{ text ->
        if (thinking) return@lambda
        val requestId = "ui-${UUID.randomUUID()}"
        sheetRequestId = requestId
        untangleInput = text
        val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply {
            timeZone = appLegacyTimeZone()
        }.format(java.util.Date())
        val loc = prefillLocation.ifBlank { "" }

        Log.i(
            LLM_LOG_TAG,
            "request=$requestId phase=submit inputChars=${text.length} locationProvided=${loc.isNotBlank()}",
        )
        thinking = true
        sheetAction = null
        scope.launch {
            try {
                val action = deepseekService?.extractSchedule(text, stamp, loc, requestId)
                if (sheetRequestId != requestId || !showScheduleSheet) return@launch

                if (action != null) {
                    Log.i(LLM_LOG_TAG, "request=$requestId phase=route result=proposal type=${action.type}")
                    sheetAction = action
                    thinking = false
                } else {
                    Log.w(LLM_LOG_TAG, "request=$requestId phase=route result=unavailable")
                    if (sheetRequestId == requestId && showScheduleSheet) {
                        thinking = false
                        Toast.makeText(context, R.string.ai_service_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: Exception) {
                Log.e(LLM_LOG_TAG, "request=$requestId phase=submit result=failed errorType=${error.javaClass.simpleName}", error)
                if (showScheduleSheet && sheetRequestId == requestId) {
                    thinking = false
                    Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun confirmAction(act: DeepSeekAnalysisService.ProposedAction) {
        val requestId = sheetRequestId.ifBlank { return }
        val start = parseAgentDatetime(act.startIso)
        val end = parseAgentDatetime(act.endIso)
        if (start == null || end == null || !end.isAfter(start)) {
            Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
            sheetAction = null
            return
        }
        val ev = ScheduleItem(
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
        if (thinking) return
        thinking = true
        scope.launch {
            try {
                eventWriteMutex.withLock { eventStore.save(ev) }
                if (showScheduleSheet && sheetRequestId == requestId) {
                    showScheduleSheet = false
                    thinking = false
                    sheetAction = null
                    sheetRequestId = ""
                    untangleInput = ""
                    prefillLocation = ""
                    gpsLat = 0.0; gpsLng = 0.0
                    val alarmMsg = when {
                        !act.alarmEnabled -> context.getString(R.string.schedule_created_toast, act.title)
                        TaskAlarmScheduler.canScheduleExactAlarms(context) ->
                            context.getString(R.string.schedule_created_with_alarm_toast, act.title)
                        else -> context.getString(R.string.schedule_created_with_fallback_alarm_toast, act.title)
                    }
                    Toast.makeText(context, alarmMsg, Toast.LENGTH_SHORT).show()
                }
                runCatching { manager.fetchEvents(serverUrl) }
                    .onSuccess { refreshed -> events = refreshed }
            } catch (e: Exception) {
                Log.e(LLM_LOG_TAG, "request=$requestId phase=confirm result=failed", e)
                if (showScheduleSheet && sheetRequestId == requestId) {
                    thinking = false
                    Toast.makeText(context, R.string.schedule_create_failed_toast, Toast.LENGTH_SHORT).show()
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
                locationLoading = phoneLocationState == PhoneLocationState.LOCATING,
                initialText = untangleInput,
                thinking = thinking,
                action = sheetAction,
                onTextChange = { text ->
                    untangleInput = text
                },
                onDismiss = {
                    Log.d(LLM_LOG_TAG, "phase=sheet_close reason=dismissed")
                    showScheduleSheet = false
                    thinking = false
                    sheetAction = null
                    sheetRequestId = ""
                    untangleInput = ""
                },
                onSubmit = submitForExtraction,
                onConfirmAction = ::confirmAction,
            )
        } else {
            MainLayout(
                isPhone = isPhone,
                phoneTab = phoneTab,
                onPhoneTabSelected = { selected ->
                    phoneTab = selected
                    primaryNavigationGeneration++
                    chromeMode = ChromeMode.PrimaryNavigation
                },
                onListSheetRequest = { showListSheet = true },
                chromeHidden = chromeHidden,
                chromeMode = chromeMode,
                onNavigationRequested = {
                    if (!chromeHidden) {
                        primaryNavigationGeneration++
                        chromeMode = ChromeMode.PrimaryNavigation
                    }
                },
                notesCard = notesCardContent,
                taskCard = taskCardContent,
                timelineCard = timelineCardContent,
            )
        }
        if (isPhone) {
            syncFeedback?.let { feedback ->
                TPlannerSyncFeedback(
                    presentation = feedback,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 9.dp),
                )
            }
        }
    }

    // ── List picker ────────────────────────────────────────────────────
    if (showListSheet) {
        ListPickerSheet(
            selectedView = selectedView,
            userLists = userLists,
            listSheetState = listSheetState,
            onSelectView = { key -> selectedViewKey = key; showListSheet = false },
            onDismiss = { showListSheet = false },
            onNewListRequest = { showNewListSheet = true },
            onDeleteList = { id ->
                scope.launch {
                    eventStore.deleteUserList(id)
                    if (selectedViewKey == id) selectedViewKey = TaskView.Inbox.key
                }
            },
        )
    }

    // ── Overlay panels ───────────────────────────────────────────────────
    pendingNewItem?.let { draftEvent ->
        NameInputSheet(
            type = draftEvent.type,
            initialText = draftEvent.title,
            onDraftChange = { name ->
                val updated = draftEvent.copy(title = name)
                pendingNewItem = updated
                eventStore.enqueueEventDraft(updated, EventEditStage.NAMING)
            },
            onCancel = {
                // Unmount first so no later naming callback can enqueue after the ordered delete.
                pendingNewItem = null
                scope.launch {
                    try {
                        eventWriteMutex.withLock { eventStore.discardEventDraft(draftEvent.id) }
                    } catch (_: Exception) {
                        pendingNewItem = draftEvent
                        Toast.makeText(context, "无法丢弃草稿，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onConfirm = { name ->
                val updated = draftEvent.copy(title = name)
                // Latch the naming UI before its DETAIL transition enters the same draft queue.
                pendingNewItem = null
                scope.launch {
                    try {
                        eventWriteMutex.withLock {
                            eventStore.saveEventDraft(updated, EventEditStage.DETAIL)
                        }
                        editingItem = updated
                    } catch (_: Exception) {
                        pendingNewItem = updated
                        Toast.makeText(context, "无法保存事项名称，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    if (showNewListSheet) {
        NameInputSheet(
            type = "task",
            entityLabel = stringResource(R.string.list_entity_name),
            initialText = "",
            onDraftChange = {},
            onCancel = { showNewListSheet = false },
            onConfirm = { name ->
                showNewListSheet = false
                scope.launch {
                    val list = eventStore.createUserList(name.trim())
                    selectedViewKey = list.id
                }
            },
        )
    }

    editingItem?.let { ev ->
        ScheduleItemDetailScreen(
            event = ev,
            userLists = userLists,
            onDraftChange = { snapshot ->
                eventStore.enqueueEventDraft(snapshot, EventEditStage.DETAIL)
            },
            onSave = { updated, onFinished ->
                val updates = if (events.none { it.id == updated.id }) {
                    createRecurringTaskInstances(updated)
                } else {
                    listOf(updated)
                }
                val nextEvents = updates.fold(events, ::upsertEventPreservingOrder)
                scope.launch {
                    try {
                        when (val result = eventWriteMutex.withLock {
                            eventStore.saveAndClearEventDraft(
                                event = updates.first(),
                                additionalEvents = updates.drop(1),
                            )
                        }) {
                            DraftCommitResult.Saved,
                            DraftCommitResult.AlreadySaved,
                            -> {
                                events = nextEvents
                                editingItem = null
                                onFinished(true)
                            }
                            is DraftCommitResult.Conflict -> {
                                itemConflict = EventDraftRecovery.Conflict(
                                    details = result.details,
                                    event = updated,
                                    stage = EventEditStage.DETAIL,
                                )
                                editingItem = null
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
            onDelete = { onFinished ->
                eventActions.deleteFromEditor(
                    events = events,
                    eventId = ev.id,
                    onEventsChanged = { events = it },
                    onFinished = { deleted ->
                        if (deleted) editingItem = null
                        onFinished(deleted)
                    },
                )
            },
            onNoteSave = { updated, onFinished ->
                val nextEvents = upsertEventPreservingOrder(events, updated)
                scope.launch {
                    try {
                        when (val result = eventWriteMutex.withLock {
                            eventStore.saveAndClearEventDraft(updated)
                        }) {
                            DraftCommitResult.Saved,
                            DraftCommitResult.AlreadySaved,
                            -> {
                                events = nextEvents
                                editingItem = updated
                                onFinished(true)
                            }
                            is DraftCommitResult.Conflict -> {
                                itemConflict = EventDraftRecovery.Conflict(
                                    details = result.details,
                                    event = updated,
                                    stage = EventEditStage.DETAIL,
                                )
                                editingItem = null
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
            },
            onCreateList = { name, onCreated ->
                scope.launch {
                    val list = try {
                        eventStore.createUserList(name)
                    } catch (_: Exception) {
                        Toast.makeText(context, "无法创建清单，请重试", Toast.LENGTH_LONG).show()
                        onCreated(null)
                        return@launch
                    }
                    onCreated(list)
                }
            },
        )
    }

    journalConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { journalConflict = null },
            title = { Text("日记内容冲突") },
            text = { Text("其他设备已修改当天内容。草稿不会丢失；请选择保留草稿、使用当前版本，或明确覆盖当前版本。") },
            confirmButton = {
                TextButton(onClick = {
                    journalActions.resolveOverwrite(conflict.details)
                    if (journalConflict != null) {
                        scope.launch { refreshJournalRecovery(conflict.date) }
                    }
                }) { Text("覆盖当前") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { journalConflict = null }) {
                        Text("保留草稿")
                    }
                    TextButton(onClick = {
                        journalActions.resolveDiscard(conflict.details)
                    }) { Text("使用当前版本") }
                }
            },
        )
    }

    itemConflict?.let { conflict ->
        val conflictDraft = conflict.event
        AlertDialog(
            onDismissRequest = { itemConflict = null },
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
                        eventActions.resolveConflictSaveAsCopy(
                            conflict,
                            { itemConflict = null; revealNextEventDraft() },
                            { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() },
                        )
                    }) { Text("另存副本") }
                } else {
                    TextButton(onClick = { itemConflict = null }) { Text("保留草稿") }
                }
            },
            dismissButton = {
                Row {
                    if (conflictDraft != null) {
                        TextButton(onClick = { itemConflict = null }) { Text("保留草稿") }
                    }
                    TextButton(onClick = {
                        eventActions.resolveConflictDiscard(
                            conflict,
                            { itemConflict = null; revealNextEventDraft() },
                            { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() },
                        )
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
