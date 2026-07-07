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

    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("TplannerMain", "notification permission granted=$granted")
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

    private fun requestWakeSetup() {
        if (requestBatteryOptimizationExemption()) return
        if (requestSamsungNeverSleepingAppExemption()) return
        if (requestSamsungBackgroundActivity()) return
        if (requestOverlayPermission()) return
        if (requestExactAlarmPermission()) return
        if (requestNotificationPermission()) return
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

    /**
     * Opens Samsung's per-app "Background activity" toggle.
     *
     * Even with battery-optimization exemption and "Never sleeping" status,
     * Samsung One UI has a separate per-app "Allow background activity"
     * toggle (Settings → Apps → [app] → Battery).  If this is off, the
     * system blocks ALL background activity starts regardless of CDM or
     * overlay permission.
     */
    private fun requestSamsungBackgroundActivity(): Boolean {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return false
        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_SAMSUNG_BG_ACTIVITY_PROMPTED, false)) return false
        if (isFinishing || isDestroyed) return false

        // Try Samsung Device Care → Battery → App power management first,
        // then fall back to the generic app details page.
        val opened = try {
            startActivity(Intent("com.samsung.android.sm.ACTION_BATTERY_OPTIMIZATION_SETTINGS"))
            true
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
                true
            } catch (_: Exception) { false }
        }
        if (opened) {
            prefs.edit().putBoolean(PREF_SAMSUNG_BG_ACTIVITY_PROMPTED, true).apply()
        }
        return opened
    }

    /**
     * Requests SCHEDULE_EXACT_ALARM on Android 12+.
     *
     * KeepAliveReceiver uses exact alarms to survive Samsung's process killing.
     * On Android 12+ the user must grant this via system settings; on 14+
     * it's auto-granted for apps targeting SDK 34+.
     */
    private fun requestExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val am = getSystemService(android.app.AlarmManager::class.java) ?: return false
        if (am.canScheduleExactAlarms()) return false

        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_EXACT_ALARM_PROMPTED, false)) return false
        if (isFinishing || isDestroyed) return false

        return try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            })
            prefs.edit().putBoolean(PREF_EXACT_ALARM_PROMPTED, true).apply()
            true
        } catch (e: Exception) {
            Log.e("TplannerMain", "requestExactAlarmPermission: failed", e)
            false
        }
    }

    /**
     * Requests POST_NOTIFICATIONS on Android 13+.
     *
     * Without notification permission the foreground-service notification is
     * suppressed by the system (see logcat: "Suppressing notification … by
     * user request").  Samsung then treats the service as a regular background
     * service and kills it aggressively.
     */
    private fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false

        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_NOTIF_PROMPTED, false)) return false
        if (isFinishing || isDestroyed) return false

        prefs.edit().putBoolean(PREF_NOTIF_PROMPTED, true).apply()
        requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        return true
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
        private const val PREF_SAMSUNG_BG_ACTIVITY_PROMPTED = "samsung_bg_activity_prompted"
        private const val PREF_OVERLAY_PROMPTED = "overlay_prompted"
        private const val PREF_BG_LOCATION_PROMPTED = "bg_location_prompted"
        private const val PREF_EXACT_ALARM_PROMPTED = "exact_alarm_prompted"
        private const val PREF_NOTIF_PROMPTED = "notif_prompted"
        private const val PREF_HIBERNATION_PROMPTED = "hibernation_prompted"
    }
}
