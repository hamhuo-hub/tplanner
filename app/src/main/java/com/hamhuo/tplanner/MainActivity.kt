package com.hamhuo.tplanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class MainActivity : ComponentActivity() {

    // Watch trigger counter: increments on each watch wake-up, MainScreen observes changes to show the anxiety panel
    var anxietyTriggerCount by mutableIntStateOf(0)

    private val requestBtPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d("TplannerMain", "requestBtPermissions result: $results")
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (results[Manifest.permission.BLUETOOTH_CONNECT] == true) startBluetoothWakeService()
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) maybeRequestBackgroundLocation()
    }

    private val requestBgLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("TplannerMain", "background location granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWakeIntent(intent)
        ensureBluetoothWakeService()
        requestWakeSetup()
        val store       = JournalStore(this)
        val eventStore  = EventStore(this)
        val insightStore = InsightStore(this)
        val manager     = LanSyncManager(this, store, eventStore, insightStore)
        // Personal use, hardcoded. Remember to remove before publishing.
        val deepseekKey = "sk-f96de0cfd34d4f4c95ee2e004c4c800f"
        val amapKey     = "d7201bb04a81eb33da8d7f8e7d6ed523"
        AmapGeocoder.setApiKey(amapKey)
        val deepseekService = DeepSeekAnalysisService(deepseekKey)
        setContent { MainScreen(
            store = store, eventStore = eventStore, manager = manager,
            insightStore = insightStore, deepseekService = deepseekService,
            amapApiKey = amapKey, anxietyTriggerCount = anxietyTriggerCount,
        ) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_FROM_WATCH, false) == true) {
            anxietyTriggerCount++
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun requestWakeSetup() {
        if (requestBatteryOptimizationExemption()) return
        if (requestSamsungNeverSleepingAppExemption()) return
        if (requestOverlayPermission()) return
        requestDisableAppHibernation()
    }

    private fun requestDisableAppHibernation() {
        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_HIBERNATION_PROMPTED, false)) return
        try {
            val future = androidx.core.content.PackageManagerCompat
                .getUnusedAppRestrictionsStatus(this)
            future.addListener({
                try {
                    val status = future.get()
                    val enabled = status == androidx.core.content.UnusedAppRestrictionsConstants.API_30_BACKPORT ||
                        status == androidx.core.content.UnusedAppRestrictionsConstants.API_30 ||
                        status == androidx.core.content.UnusedAppRestrictionsConstants.API_31
                    if (enabled) {
                        prefs.edit().putBoolean(PREF_HIBERNATION_PROMPTED, true).apply()
                        if (isFinishing || isDestroyed) {
                            Log.d("TplannerMain", "hibernation: activity destroyed, skipping startActivity")
                            return@addListener
                        }
                        startActivity(
                            androidx.core.content.IntentCompat
                                .createManageUnusedAppRestrictionsIntent(this, packageName)
                        )
                    }
                } catch (e: Exception) {
                    Log.d("TplannerMain", "hibernation status check failed", e)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.d("TplannerMain", "hibernation API unavailable", e)
        }
    }

    private fun requestBatteryOptimizationExemption(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        val alreadyIgnoring = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
        Log.d("TplannerMain", "requestBatteryOptimizationExemption: alreadyIgnoring=$alreadyIgnoring")
        if (alreadyIgnoring) return false
        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BATTERY_PROMPTED, false)) return false
        if (isFinishing || isDestroyed) return false
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
            prefs.edit().putBoolean(PREF_BATTERY_PROMPTED, true).apply()
            return true
        } catch (e: Exception) {
            Log.e("TplannerMain", "requestBatteryOptimizationExemption: failed to launch", e)
            return false
        }
    }

    private fun requestSamsungNeverSleepingAppExemption(): Boolean {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return false

        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_SAMSUNG_NEVER_SLEEP_PROMPTED, false)) return false

        val opened = listOf("com.samsung.android.lool", "com.samsung.android.sm", null)
            .any { settingsPackage -> openSamsungNeverSleepingApps(settingsPackage) }
        if (opened) {
            prefs.edit().putBoolean(PREF_SAMSUNG_NEVER_SLEEP_PROMPTED, true).apply()
        }
        return opened
    }

    private fun openSamsungNeverSleepingApps(settingsPackage: String?): Boolean {
        if (isFinishing || isDestroyed) return false
        val intent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").apply {
            settingsPackage?.let { setPackage(it) }
            putExtra("activity_type", 2)
        }
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d("TplannerMain", "openSamsungNeverSleepingApps: failed for package=$settingsPackage", e)
            false
        }
    }

    private fun requestOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) return false

        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_OVERLAY_PROMPTED, false)) return false
        if (isFinishing || isDestroyed) return false

        return try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
            prefs.edit().putBoolean(PREF_OVERLAY_PROMPTED, true).apply()
            true
        } catch (e: Exception) {
            Log.e("TplannerMain", "requestOverlayPermission: failed to launch", e)
            false
        }
    }

    private fun ensureBluetoothWakeService() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needed.isEmpty()) {
            startBluetoothWakeService()
            maybeRequestBackgroundLocation()
        } else {
            requestBtPermissions.launch(needed.toTypedArray())
        }
    }

    private fun maybeRequestBackgroundLocation() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BG_LOCATION_PROMPTED, false)) return
        prefs.edit().putBoolean(PREF_BG_LOCATION_PROMPTED, true).apply()
        requestBgLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun startBluetoothWakeService() {
        if (isFinishing || isDestroyed) return
        ContextCompat.startForegroundService(this, Intent(this, BluetoothWakeService::class.java))
    }

    companion object {
        const val EXTRA_WAKE_FROM_WATCH = "wake_from_watch"
        private const val WAKE_SETUP_PREFS = "wake_setup"
        private const val PREF_BATTERY_PROMPTED = "battery_prompted"
        private const val PREF_SAMSUNG_NEVER_SLEEP_PROMPTED = "samsung_never_sleep_prompted"
        private const val PREF_OVERLAY_PROMPTED = "overlay_prompted"
        private const val PREF_BG_LOCATION_PROMPTED = "bg_location_prompted"
        private const val PREF_HIBERNATION_PROMPTED = "hibernation_prompted"
    }
}

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
    val context = androidx.compose.ui.platform.LocalContext.current
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

    // ── Watch-triggered anxiety recording ─────────────────────────────────
    var showAnxietySheet by remember { mutableStateOf(false) }
    var prefillLocation by remember { mutableStateOf("") }
    // 手表打点定位（唯一真相源来自 WatchLocationStore，由蓝牙服务异步写入），
    // submit 时直接用这两个值存进 InsightStore，不再从随笔文本正则抠坐标
    var watchLat by remember { mutableStateOf(0.0) }
    var watchLng by remember { mutableStateOf(0.0) }
    var insightRefreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(anxietyTriggerCount) {
        if (anxietyTriggerCount > 0) {
            showAnxietySheet = true
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

            fix?.let {
                watchLat = it.lat; watchLng = it.lng
                if (amapApiKey.isNotBlank()) {
                    // 逆地理编码失败会回退坐标串——面板从"Locating..."更新为地名/坐标
                    prefillLocation = AmapGeocoder.reverseGeocode(it.lat, it.lng, amapApiKey)
                }
            }
        }
    }

    // End-of-day auto analysis
    LaunchedEffect(content) {
        val today = java.time.LocalDate.now().toString()
        val existingReport = insightStore.getDayReport(today)
        if (existingReport == null) {
            val dayEntries = insightStore.getTodayEvents()
            if (dayEntries.isNotEmpty() && deepseekService != null) {
                val report = deepseekService.synthesizeDay(dayEntries, today)
                if (report != null) insightStore.saveDayReport(report)
                insightRefreshTrigger++
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
            onItemClick = { event -> editingEvent = event }
        )
    }

    // ── Main layout ──────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(BG).windowInsetsPadding(WindowInsets.systemBars)) {
        if (showAnxietySheet) {
            // Anxiety log panel fullscreen overlay
            val submitAnxiety: (String, Int, List<String>, List<String>) -> Unit =
                { text, intensity, emotions, symptoms ->
                    val now = System.currentTimeMillis()
                    val entryLine = buildString {
                        append("\n\n---\n\n### ")
                        append(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()))
                        append(" · ").append(prefillLocation.ifBlank { "Unknown" })
                        if (intensity > 0) append("\n*Intensity ${intensity}%*")
                        if (emotions.isNotEmpty()) append("\n*Emotions: ${emotions.joinToString(" · ")}*")
                        if (symptoms.isNotEmpty()) append("\n*Physical: ${symptoms.joinToString(" · ")}*")
                        append("\n\n").append(text)
                    }
                    val updated = content + entryLine
                    content = updated
                    store.saveToday(updated)
                    showAnxietySheet = false

                    if (deepseekService != null && text.isNotBlank()) {
                        scope.launch {
                            val result = deepseekService.restructureEntry(
                                text = text,
                                timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                                    .format(java.util.Date(now)),
                                location = prefillLocation.ifBlank { "Unknown" },
                                emotions = emotions,
                                symptoms = symptoms,
                                userIntensity = intensity,
                            )
                            if (result != null) {
                                val threeColumnBlock = buildString {
                                    append("\n\n> **Automatic Thought**\n> ${result.autoThought} (confidence ${result.thoughtConfidence}%)\n>\n")
                                    append("> **Distortions**\n> ${result.distortions.joinToString(" · ")}\n>\n")
                                    append("> **Rational Response**\n> ${result.rationalResponse}")
                                }
                                val final = store.getToday() + threeColumnBlock
                                store.saveToday(final)
                                content = final

                                insightStore.addEvent(StructuredEntry(
                                    id = UUID.randomUUID().toString(),
                                    timestamp = now,
                                    text = text,
                                    location = prefillLocation.ifBlank { "Unknown" },
                                    lat = watchLat, lng = watchLng,
                                    intensity = if (result.intensity > 0) result.intensity else intensity,
                                    distortions = result.distortions,
                                    autoThought = result.autoThought,
                                    thoughtConfidence = result.thoughtConfidence,
                                    rationalResponse = result.rationalResponse,
                                    emotion = result.emotion.ifBlank { emotions.firstOrNull() ?: "" },
                                ))
                            } else {
                                insightStore.addEvent(StructuredEntry(
                                    id = UUID.randomUUID().toString(),
                                    timestamp = now,
                                    text = text,
                                    location = prefillLocation.ifBlank { "Unknown" },
                                    lat = watchLat, lng = watchLng,
                                    intensity = intensity,
                                    distortions = emptyList(),
                                    autoThought = "", thoughtConfidence = 0,
                                    rationalResponse = "",
                                    emotion = emotions.firstOrNull() ?: "",
                                ))
                            }
                            insightRefreshTrigger++
                        }
                    }
                }

            AnxietyInputSheet(
                prefillLocation = prefillLocation,
                onDismiss = { showAnxietySheet = false },
                onSubmit = submitAnxiety,
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
                    else InsightPanel(store = insightStore, amapApiKey = amapApiKey, onRefresh = { insightRefreshTrigger++ })
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
