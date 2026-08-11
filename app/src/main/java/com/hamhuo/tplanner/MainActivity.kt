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
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.hamhuo.tplanner.persistence.LegacyImportResult
import com.hamhuo.tplanner.persistence.LegacyPreferencesImporter
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.persistence.DurableWriteQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GMS Data Layer wakes [WakeDataLayerService], which attaches a 1×1
 * invisible overlay (bypassing Samsung BAL), then delegates through
 * [WakeProxyActivity] to here with EXTRA_WAKE_FROM_WATCH.
 */
class MainActivity : ComponentActivity() {

    private lateinit var eventStore: EventStore
    private val pendingWakeRequestIds = linkedSetOf<String>()

    // Watch trigger counter: increments on each watch wake-up,
    // MainScreen observes changes to show the schedule extraction sheet.
    var scheduleTriggerCount by mutableIntStateOf(0)

    private enum class PermissionStep {
        RUNTIME,
        BACKGROUND_LOCATION,
        OVERLAY,
        BATTERY_OPTIMIZATION,
        EXACT_ALARM,
    }

    private var permissionLauncherInFlight = false
    private var pendingSpecialPermissionStep: PermissionStep? = null

    // Foreground location, nearby-device Bluetooth, and notifications share one request.
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d(TAG, "permissions result: $results")
        permissionLauncherInFlight = false
        WatchTaskImportService.startIfAllowed(this)
        advancePermissionSetup()
    }

    // Background location must be requested separately after foreground is granted
    private val requestBgLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "background location granted=$granted")
        permissionLauncherInFlight = false
        advancePermissionSetup()
    }

    // Special-access screens don't return a meaningful result code. Always re-enter
    // the pipeline and rely on the platform state checks used by app features.
    private val requestSpecialAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "special access returned: $pendingSpecialPermissionStep")
        pendingSpecialPermissionStep = null
        permissionLauncherInFlight = false
        advancePermissionSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWakeIntent(intent)
        advancePermissionSetup()
        WatchTaskImportService.startIfAllowed(this)
        lifecycleScope.launch { initializeStorageAndContent() }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been granted via system settings while the app was paused
        // (e.g. background location "Allow all the time", overlay toggle, battery exemption).
        advancePermissionSetup()
        WatchTaskImportService.startIfAllowed(this)
        if (::eventStore.isInitialized) {
            lifecycleScope.launch {
                TaskAlarmScheduler.reconcile(this@MainActivity, eventStore.getAll())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_FROM_WATCH, false) == true) {
            val requestId = intent.getStringExtra(EXTRA_WAKE_REQUEST_ID)
            if (requestId != null && requestId in pendingWakeRequestIds) {
                Log.d(TAG, "handleWakeIntent: request already pending=$requestId")
                return
            }
            if (
                requestId != null &&
                !WakeDataLayerService.shouldConsumeWakeRequest(this, requestId)
            ) {
                Log.d(TAG, "handleWakeIntent: duplicate request=$requestId")
                return
            }

            // Mutate the app-visible trigger first. Only actual MainActivity consumption,
            // not ActivityManager accepting the proxy, is allowed to complete the request.
            scheduleTriggerCount++
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                )
            }
            requestId?.let { id ->
                pendingWakeRequestIds += id
            }
        }
    }

    override fun onStop() {
        val flushed = DurableWriteQueue.flushAllOnStop()
        if (!flushed) Log.w(TAG, "onStop: recovery writes did not flush successfully")
        super.onStop()
    }

    private fun completePendingWakeRequest(requestId: String) {
        val persisted = WakeDataLayerService.completeWakeRequest(this, requestId)
        if (persisted) pendingWakeRequestIds.remove(requestId)
        Log.d(TAG, "completePendingWakeRequest: request=$requestId persisted=$persisted")
    }

    private suspend fun initializeStorageAndContent() {
        val database = TPlannerDatabase.get(this)
        val migration = withContext(Dispatchers.IO) {
            LegacyPreferencesImporter(this@MainActivity, database).importIfNeeded()
        }
        if (migration is LegacyImportResult.Blocked) {
            val details = migration.issues.joinToString("; ") { issue ->
                "${issue.source}${issue.key?.let { "/$it" }.orEmpty()}: ${issue.message}"
            }
            Log.e(TAG, "Storage migration blocked: $details")
            Toast.makeText(this, "本地数据迁移失败，原数据未改动：$details", Toast.LENGTH_LONG).show()
            return
        }

        val store = JournalStore(this, database)
        eventStore = EventStore(this, database)
        val manager = LanSyncManager(this, store, eventStore)
        val untangleStore = UntangleStateStore(this, database)
        val deepseekKey = BuildConfig.DEEPSEEK_API_KEY
        val amapKey = BuildConfig.AMAP_API_KEY
        AmapGeocoder.setApiKey(amapKey)
        val deepseekService = deepseekKey.takeIf { it.isNotBlank() }?.let(::DeepSeekAnalysisService)
        Log.i(
            LLM_LOG_TAG,
            "phase=init provider=deepseek keyConfigured=${deepseekKey.isNotBlank()} " +
                "serviceCreated=${deepseekService != null} migration=${migration::class.simpleName}",
        )

        val initialJournalSession = store.latestDraftRecovery()
        val initialJournalDate = initialJournalSession?.date ?: appToday().toString()
        val initialJournalRecovery = initialJournalSession?.recovery
            ?: store.getDraftRecovery(initialJournalDate)
        val initialContent = when (initialJournalRecovery) {
            JournalDraftRecovery.None -> store.get(initialJournalDate)
            is JournalDraftRecovery.Recovered -> initialJournalRecovery.text
            is JournalDraftRecovery.Conflict -> initialJournalRecovery.text
        }
        val initialUntangleState = untangleStore.latest()
        val initialEventRecovery = eventStore.latestEventDraftRecovery()
        val initialEvents = eventStore.getAll()
        val initialServerUrl = manager.getServerUrl()
        runCatching { SyncOutboxScheduler.enqueue(this) }
            .onFailure { Log.w(TAG, "Unable to start sync outbox worker", it) }
        runCatching { TaskAlarmScheduler.reconcile(this, initialEvents) }
            .onFailure { Log.w(TAG, "Unable to reconcile alarms during startup", it) }
        setContent {
            MainScreen(
                store = store,
                eventStore = eventStore,
                manager = manager,
                deepseekService = deepseekService,
                amapApiKey = amapKey,
                scheduleTriggerCount = scheduleTriggerCount,
                initialContent = initialContent,
                initialEvents = initialEvents,
                initialJournalDate = initialJournalDate,
                initialJournalRecovery = initialJournalRecovery,
                initialServerUrl = initialServerUrl,
                initialEventRecovery = initialEventRecovery,
                untangleStore = untangleStore,
                initialUntangleState = initialUntangleState,
                onScheduleSheetReady = {
                    pendingWakeRequestIds.toList().forEach(::completePendingWakeRequest)
                },
            )
        }
    }

    // ── Permissions ─────────────────────────────────────────────

    /**
     * Advances each startup permission step at most once per process. A denial is
     * not a hard gate: launcher callbacks continue to the next independent step.
     * Special-access screens are serialized through [requestSpecialAccess].
     */
    private fun advancePermissionSetup() {
        if (permissionLauncherInFlight || isFinishing || isDestroyed) return

        while (!permissionLauncherInFlight && !isFinishing && !isDestroyed) {
            val step = PermissionStep.entries
                .firstOrNull { it !in attemptedPermissionSteps }
                ?: return
            attemptedPermissionSteps += step

            when (step) {
                PermissionStep.RUNTIME -> {
                    val missing = mutableListOf<String>()
                    if (!hasForegroundLocation()) {
                        // Android 12+ ignores a fine-only request on some releases.
                        missing += Manifest.permission.ACCESS_COARSE_LOCATION
                        missing += Manifest.permission.ACCESS_FINE_LOCATION
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                            missing += Manifest.permission.BLUETOOTH_CONNECT
                        }
                        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                            missing += Manifest.permission.BLUETOOTH_SCAN
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        missing += Manifest.permission.POST_NOTIFICATIONS
                    }
                    if (missing.isNotEmpty() && launchRuntimePermissions(missing)) return
                }

                PermissionStep.BACKGROUND_LOCATION -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                        !hasForegroundLocation() ||
                        hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    ) continue

                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        if (launchBackgroundLocationPermission()) return
                    } else {
                        // Android 11+ exposes "Allow all the time" only in app settings.
                        Toast.makeText(
                            this,
                            getString(R.string.bg_location_guide),
                            Toast.LENGTH_LONG,
                        ).show()
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        if (launchSpecialAccess(PermissionStep.BACKGROUND_LOCATION, intent)) return
                    }
                }

                PermissionStep.OVERLAY -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        Settings.canDrawOverlays(this)
                    ) continue
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    if (launchSpecialAccess(PermissionStep.OVERLAY, intent)) return
                }

                PermissionStep.BATTERY_OPTIMIZATION -> {
                    val powerManager = getSystemService(PowerManager::class.java)
                    if (powerManager == null ||
                        powerManager.isIgnoringBatteryOptimizations(packageName)
                    ) continue
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    if (launchSpecialAccess(PermissionStep.BATTERY_OPTIMIZATION, intent)) return
                }

                PermissionStep.EXACT_ALARM -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        TaskAlarmScheduler.canScheduleExactAlarms(this)
                    ) continue
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    if (launchSpecialAccess(PermissionStep.EXACT_ALARM, intent)) return
                }
            }
        }
    }

    private fun launchRuntimePermissions(missing: List<String>): Boolean {
        permissionLauncherInFlight = true
        return try {
            requestPermissionsLauncher.launch(missing.toTypedArray())
            true
        } catch (e: Exception) {
            permissionLauncherInFlight = false
            Log.e(TAG, "runtime permission request failed", e)
            false
        }
    }

    private fun launchBackgroundLocationPermission(): Boolean {
        permissionLauncherInFlight = true
        return try {
            requestBgLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            true
        } catch (e: Exception) {
            permissionLauncherInFlight = false
            Log.e(TAG, "background location request failed", e)
            false
        }
    }

    private fun launchSpecialAccess(step: PermissionStep, intent: Intent): Boolean {
        permissionLauncherInFlight = true
        pendingSpecialPermissionStep = step
        return try {
            requestSpecialAccess.launch(intent)
            true
        } catch (e: Exception) {
            pendingSpecialPermissionStep = null
            permissionLauncherInFlight = false
            Log.e(TAG, "special access request failed: $step", e)
            false
        }
    }

    private fun hasForegroundLocation(): Boolean =
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_WAKE_FROM_WATCH = "wake_from_watch"
        const val EXTRA_WAKE_REQUEST_ID = "wake_request_id"
        private const val TAG = "TplannerMain"
        private const val LLM_LOG_TAG = "TplannerLLM"

        // Process-scoped attempt history survives Activity recreation without retaining launcher state.
        private val attemptedPermissionSteps = mutableSetOf<PermissionStep>()
    }
}
