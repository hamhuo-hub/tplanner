package com.hamhuo.tplanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hamhuo.tplanner.persistence.LegacyImportResult
import com.hamhuo.tplanner.persistence.LegacyPreferencesImporter
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.persistence.DurableWriteQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var eventStore: EventStore

    private enum class PermissionStep {
        RUNTIME,
        EXACT_ALARM,
    }

    private var permissionLauncherInFlight = false
    private var pendingSpecialPermissionStep: PermissionStep? = null

    // Nearby-device Bluetooth and notifications share one runtime request.
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d(TAG, "permissions result: $results")
        permissionLauncherInFlight = false
        WatchTaskImportService.startIfAllowed(this)
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
        advancePermissionSetup()
        WatchTaskImportService.startIfAllowed(this)
        lifecycleScope.launch { initializeStorageAndContent() }
    }

    override fun onResume() {
        super.onResume()
        // Exact-alarm access may have been granted in system settings while paused.
        advancePermissionSetup()
        WatchTaskImportService.startIfAllowed(this)
        if (::eventStore.isInitialized) {
            lifecycleScope.launch {
                TaskAlarmScheduler.reconcile(this@MainActivity, eventStore.getAll())
            }
        }
    }

    override fun onStop() {
        val flushed = DurableWriteQueue.flushAllOnStop()
        if (!flushed) Log.w(TAG, "onStop: recovery writes did not flush successfully")
        super.onStop()
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
                "serviceCreated=${deepseekService != null} " +
                "locationApiConfigured=${amapKey.isNotBlank()} migration=${migration::class.simpleName}",
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
                initialContent = initialContent,
                initialEvents = initialEvents,
                initialJournalDate = initialJournalDate,
                initialJournalRecovery = initialJournalRecovery,
                initialServerUrl = initialServerUrl,
                initialEventRecovery = initialEventRecovery,
                untangleStore = untangleStore,
                initialUntangleState = initialUntangleState,
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

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TplannerMain"
        private const val LLM_LOG_TAG = "TplannerLLM"

        // Process-scoped attempt history survives Activity recreation without retaining launcher state.
        private val attemptedPermissionSteps = mutableSetOf<PermissionStep>()
    }
}
