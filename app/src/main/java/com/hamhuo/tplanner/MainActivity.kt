package com.hamhuo.tplanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
 * No foreground-service infrastructure.  GMS Data Layer wakes
 * [WakeDataLayerService], which delegates through [WakeProxyActivity]
 * to here with EXTRA_WAKE_FROM_WATCH — just like Samsung Health's
 * DeeplinkDelegatorActivity → HomeMainActivity chain.
 */
class MainActivity : ComponentActivity() {

    // Watch trigger counter: increments on each watch wake-up,
    // MainScreen observes changes to show the anxiety panel.
    var anxietyTriggerCount by mutableIntStateOf(0)

    private val requestBgLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("TplannerMain", "background location granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWakeIntent(intent)
        maybeRequestBackgroundLocation()
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

    private fun maybeRequestBackgroundLocation() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(WAKE_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BG_LOCATION_PROMPTED, false)) return
        prefs.edit().putBoolean(PREF_BG_LOCATION_PROMPTED, true).apply()
        requestBgLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    companion object {
        const val EXTRA_WAKE_FROM_WATCH = "wake_from_watch"
        private const val WAKE_SETUP_PREFS = "wake_setup"
        private const val PREF_BG_LOCATION_PROMPTED = "bg_location_prompted"
    }
}
