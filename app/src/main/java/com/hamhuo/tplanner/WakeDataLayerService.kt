package com.hamhuo.tplanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side Data Layer listener — the sole watch→phone channel.
 *
 * When GMS delivers a [/tplanner/wake] message from the watch:
 *   1. Attach a 1×1 invisible overlay — puts the process into a "has visible
 *      window" state, bypassing Samsung's BAL checker (same trick the old
 *      BluetoothWakeService used, confirmed working).
 *   2. Fetch current location (fire-and-forget, saved to [WatchLocationStore]).
 *   3. Launch [WakeProxyActivity], which delegates to [MainActivity] with
 *      REORDER_TO_FRONT.
 *
 * The overlay is detached by [WakeProxyActivity] once MainActivity is visible
 * (or by the 5s safety timeout in this service).
 */
class WakeDataLayerService : WearableListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WAKE_PATH) {
            Log.d(TAG, "onMessageReceived: ignoring unknown path=${messageEvent.path}")
            return
        }

        Log.d(TAG, "onMessageReceived: watch wake via Data Layer (source=${messageEvent.sourceNodeId})")

        // Step 1: attach invisible overlay — prevents Samsung BAL block.
        // Must happen BEFORE startActivity; BAL checks hasVisibleWindow at call time.
        attachOverlay()

        // Step 2: fetch location (fire-and-forget).
        fetchCurrentLocation()

        // Step 3: launch proxy.
        val intent = Intent(this, WakeProxyActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(WakeProxyActivity.EXTRA_WAKE_FROM_WATCH, true)
        }

        try {
            startActivity(intent)
            Log.d(TAG, "onMessageReceived: WakeProxyActivity launched")
        } catch (e: Exception) {
            Log.e(TAG, "onMessageReceived: startActivity failed", e)
            detachOverlay() // clean up on failure
        }

        // Safety timeout: if WakeProxyActivity doesn't detach the overlay within
        // 5 seconds (crashed / killed before delegation), remove it here.
        handler.postDelayed({ detachOverlay() }, 5_000L)
    }

    // ── BAL bypass overlay ────────────────────────────────────────────────

    private fun attachOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "attachOverlay: overlay permission not granted, BAL may block")
            return
        }
        synchronized(overlayLock) {
            if (overlayView != null) return // already attached
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val view = android.view.View(this)
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    1, 1, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                )
                wm.addView(view, params)
                overlayView = view
                overlayWm = wm
                Log.d(TAG, "attachOverlay: attached successfully")
            } catch (e: Exception) {
                Log.e(TAG, "attachOverlay: failed", e)
            }
        }
    }

    private fun detachOverlay() {
        synchronized(overlayLock) {
            overlayView?.let { view ->
                try { overlayWm?.removeView(view) } catch (_: Exception) {}
                overlayView = null
                overlayWm = null
                Log.d(TAG, "detachOverlay: removed")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detachOverlay()
        handler.removeCallbacksAndMessages(null)
    }

    // ── Location (same cascading logic as old BluetoothWakeService) ────────

    private fun fetchCurrentLocation() {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            Log.w(TAG, "fetchCurrentLocation: no location permission")
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return

        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.allProviders.contains(LocationManager.FUSED_PROVIDER))
                add(LocationManager.FUSED_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                add(LocationManager.NETWORK_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                add(LocationManager.GPS_PROVIDER)
        }
        if (candidates.isEmpty()) {
            saveLastKnown(lm)
            return
        }
        tryNext(lm, candidates, 0)
    }

    private fun tryNext(lm: LocationManager, candidates: List<String>, idx: Int) {
        if (idx >= candidates.size) { saveLastKnown(lm); return }
        val provider = candidates[idx]
        val done = AtomicBoolean(false)
        val timeout = if (provider == LocationManager.FUSED_PROVIDER) 6_000L else 10_000L

        val onTimeout = Runnable {
            if (done.compareAndSet(false, true)) tryNext(lm, candidates, idx + 1)
        }
        handler.postDelayed(onTimeout, timeout)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(this)) { loc ->
                    if (done.compareAndSet(false, true)) {
                        handler.removeCallbacks(onTimeout)
                        if (loc != null) save(loc) else tryNext(lm, candidates, idx + 1)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (done.compareAndSet(false, true)) {
                            handler.removeCallbacks(onTimeout)
                            save(loc)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchCurrentLocation: $provider failed", e)
            handler.removeCallbacks(onTimeout)
            if (done.compareAndSet(false, true)) tryNext(lm, candidates, idx + 1)
        }
    }

    private fun saveLastKnown(lm: LocationManager) {
        try {
            val best = lm.allProviders.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (best != null) WatchLocationStore.save(this, best.latitude, best.longitude)
        } catch (_: SecurityException) {}
    }

    private fun save(loc: Location) {
        WatchLocationStore.save(this, loc.latitude, loc.longitude)
    }

    companion object {
        private const val TAG = "TplannerDataLayer"
        const val WAKE_PATH = "/tplanner/wake"

        // Shared overlay state — WakeProxyActivity reads this to detach the
        // overlay once MainActivity is visible (it runs in the same process).
        private val overlayLock = Any()
        private var overlayView: android.view.View? = null
        private var overlayWm: WindowManager? = null

        /** Called by [WakeProxyActivity] after MainActivity is brought to front. */
        fun detachOverlayFromProxy() {
            synchronized(overlayLock) {
                overlayView?.let { view ->
                    try { overlayWm?.removeView(view) } catch (_: Exception) {}
                    overlayView = null
                    overlayWm = null
                }
            }
        }
    }
}
