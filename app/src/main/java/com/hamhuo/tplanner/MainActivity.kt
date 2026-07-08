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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * GMS Data Layer wakes [WakeDataLayerService], which attaches a 1×1
 * invisible overlay (bypassing Samsung BAL), then delegates through
 * [WakeProxyActivity] to here with EXTRA_WAKE_FROM_WATCH.
 */
class MainActivity : ComponentActivity() {

    // Watch trigger counter: increments on each watch wake-up,
    // MainScreen observes changes to show the anxiety panel.
    var anxietyTriggerCount by mutableIntStateOf(0)

    // Multi-permission launcher for foreground location + notifications
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d(TAG, "permissions result: $results")
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION]
            ?: hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (locationGranted) {
            // Foreground location granted → chain to background location
            maybeRequestBackgroundLocation()
        }
    }

    // Background location must be requested separately after foreground is granted
    private val requestBgLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "background location granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWakeIntent(intent)
        checkAndRequestPermissions()
        val store       = JournalStore(this)
        val eventStore  = EventStore(this)
        val insightStore = InsightStore(this)
        val manager     = LanSyncManager(this, store, eventStore, insightStore)
        val deepseekKey = BuildConfig.DEEPSEEK_API_KEY
        val amapKey     = BuildConfig.AMAP_API_KEY
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

    // ── Permissions ─────────────────────────────────────────────

    /**
     * Check all required permissions / exemptions on startup in dependency order:
     *   1. SYSTEM_ALERT_WINDOW (special – settings intent)
     *   2. Battery optimization exemption (settings intent, keeps process alive)
     *   3. Foreground location + notifications (bundled runtime permission)
     *   4. Background location (chained after foreground)
     */
    private fun checkAndRequestPermissions() {
        if (requestOverlayPermissionIfNeeded()) return
        if (requestBatteryOptimizationExemption()) return

        val missing = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        } else {
            // All foreground permissions already granted → check background
            maybeRequestBackgroundLocation()
        }
    }

    /**
     * SYSTEM_ALERT_WINDOW is required for the invisible overlay that
     * [WakeDataLayerService] attaches before launching the proxy Activity.
     * Without it, Samsung BAL blocks every watch→phone wake-up.
     * The user must grant this once in system settings.
     */
    private fun requestOverlayPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (Settings.canDrawOverlays(this)) return false
        if (isFinishing || isDestroyed) return false

        return try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "requestOverlayPermission: failed", e)
            false
        }
    }

    /**
     * Battery optimization exemption keeps the process alive so GMS can deliver
     * Data Layer messages.  On Samsung / Chinese ROMs the process gets killed
     * within minutes of going to background without this.
     */
    private fun requestBatteryOptimizationExemption(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return false
        if (isFinishing || isDestroyed) return false

        return try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "requestBatteryOptimization: failed", e)
            false
        }
    }

    private fun maybeRequestBackgroundLocation() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return
        requestBgLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_WAKE_FROM_WATCH = "wake_from_watch"
        private const val TAG = "TplannerMain"
    }
}
