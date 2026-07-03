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

    private val requestBtPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d("TplannerMain", "requestBtPermissions result: $results")
        if (results[Manifest.permission.BLUETOOTH_CONNECT] == true) startBluetoothWakeService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWakeIntent(intent)
        ensureBluetoothWakeService()
        requestWakeSetup()
        val store       = JournalStore(this)
        val eventStore  = EventStore(this)
        val manager     = LanSyncManager(this, store, eventStore)
        setContent { MainScreen(store = store, eventStore = eventStore, manager = manager) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_FROM_WATCH, false) == true) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun requestWakeSetup() {
        if (requestBatteryOptimizationExemption()) return
        if (requestSamsungNeverSleepingAppExemption()) return
        requestOverlayPermission()
    }

    // 三星手机的"休眠应用"省电策略会冻结后台 Service，导致手表发来的连接
    // 收不到响应。只能在前台 Activity 里弹出系统授权弹窗，所以放在这里
    // （用户打开 App 时）申请一次，而不是在后台 Service 里申请。
    private fun requestBatteryOptimizationExemption(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        val alreadyIgnoring = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
        Log.d("TplannerMain", "requestBatteryOptimizationExemption: alreadyIgnoring=$alreadyIgnoring")
        if (alreadyIgnoring) return false
        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BATTERY_PROMPTED, false)) return false
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

    // BluetoothWakeService 接收手表端直连的蓝牙信号来唤起本 App（见该类注释：
    // 国行设备上 Google Wearable Data Layer 的跨设备消息中继不可用，改为
    // 应用自己管理的经典蓝牙连接）。BLUETOOTH_CONNECT 是运行时权限，必须先
    // 申请到才能启动监听。
    private fun ensureBluetoothWakeService() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (needed.isEmpty()) {
            startBluetoothWakeService()
        } else {
            requestBtPermissions.launch(needed.toTypedArray())
        }
    }

    private fun startBluetoothWakeService() {
        ContextCompat.startForegroundService(this, Intent(this, BluetoothWakeService::class.java))
    }

    companion object {
        const val EXTRA_WAKE_FROM_WATCH = "wake_from_watch"
        private const val WAKE_SETUP_PREFS = "wake_setup"
        private const val PREF_BATTERY_PROMPTED = "battery_prompted"
        private const val PREF_SAMSUNG_NEVER_SLEEP_PROMPTED = "samsung_never_sleep_prompted"
        private const val PREF_OVERLAY_PROMPTED = "overlay_prompted"
    }
}

@Composable
fun MainScreen(store: JournalStore, eventStore: EventStore, manager: LanSyncManager) {
    val scope  = rememberCoroutineScope()
    var content    by remember { mutableStateOf(store.getToday()) }
    var panelOpen  by remember { mutableStateOf(false) }
    var events     by remember { mutableStateOf(eventStore.getAll()) }

    // ── 同步状态（固定服务器地址，无扫描） ────────────────────────────────────
    var serverUrl  by remember { mutableStateOf(manager.getServerUrl()) }
    var syncStatus by remember { mutableStateOf("idle") }   // idle|syncing|success|error
    var syncMsg    by remember { mutableStateOf("") }

    // stringResource() 只能在 composition 中调用，不能在 coroutine/launch 回调里调用，
    // 所以提前在这里解析好，再在回调里用 .format() 套用参数。
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

    // 启动时后台自动与固定服务器同步
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

    // 宽度 < 840dp（Material3 Expanded 断点）视为紧凑布局：单列 + 顶部标签页切换。
    // 平板竖屏宽度通常 700-840dp，仍不足以舒展两栏，需与手机横屏一样走单栏。
    val isPhone = LocalConfiguration.current.screenWidthDp < 840
    var phoneTab by remember { mutableStateOf(0) }   // 0=随手记, 1=日程

    // 共用面板构建块（notes card content）
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
                // 本地优先：先落盘，再尽力做一次全量事件同步把改动推给服务器
                //（本地改动带着更新的 updatedAt，合并时获胜；失败则留待下次同步）。
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
