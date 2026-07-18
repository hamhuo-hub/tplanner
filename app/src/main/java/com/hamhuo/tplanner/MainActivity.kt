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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * GMS Data Layer wakes [WakeDataLayerService], which attaches a 1×1
 * invisible overlay (bypassing Samsung BAL), then delegates through
 * [WakeProxyActivity] to here with EXTRA_WAKE_FROM_WATCH.
 */
class MainActivity : ComponentActivity() {

    private lateinit var eventStore: EventStore

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

    // Foreground location (coarse + fine) and notifications share one runtime request.
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d(TAG, "permissions result: $results")
        permissionLauncherInFlight = false
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
        val store       = JournalStore(this)
        eventStore      = EventStore(this)
        val manager     = LanSyncManager(this, store, eventStore)
        val deepseekKey = BuildConfig.DEEPSEEK_API_KEY
        val deepseekService = deepseekKey
            .takeIf { it.isNotBlank() }
            ?.let(::DeepSeekAnalysisService)
        TaskAlarmScheduler.reconcile(this, eventStore.getAll())
        setContent { MainScreen(
            store = store, eventStore = eventStore, manager = manager,
            deepseekService = deepseekService,
            scheduleTriggerCount = scheduleTriggerCount,
        ) }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been granted via system settings while the app was paused
        // (e.g. background location "Allow all the time", overlay toggle, battery exemption).
        advancePermissionSetup()
        if (::eventStore.isInitialized) {
            TaskAlarmScheduler.reconcile(this, eventStore.getAll())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_FROM_WATCH, false) == true) {
            scheduleTriggerCount++
            setShowWhenLocked(true)
            setTurnScreenOn(true)
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
        private const val TAG = "TplannerMain"

        // Process-scoped attempt history survives Activity recreation without retaining launcher state.
        private val attemptedPermissionSteps = mutableSetOf<PermissionStep>()
    }
}
