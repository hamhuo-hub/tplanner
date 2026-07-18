package com.hamhuo.tplanner

import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Phone-side Data Layer listener — the sole watch→phone channel.
 *
 * When GMS delivers a [/tplanner/wake] message from the watch:
 *   1. Attach a 1×1 invisible overlay — puts the process into a "has visible
 *      window" state, bypassing Samsung's BAL checker (same trick the old
 *      BluetoothWakeService used, confirmed working).
 *   2. Save only a qualified last-known cache; do not start background sensors here.
 *   3. Launch [WakeProxyActivity]; MainScreen starts the foreground capture.
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

        // Step 2: sensor-free cache prime. The singleton also invalidates an older capture;
        // active fused/network/GPS listeners are started only after MainScreen is visible.
        LocationCapture.primeFreshCache(this)

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
                // removeViewImmediate is synchronous — no Handler.post involved.
                // removeView would go through ViewRootImpl.die() which posts to
                // a Handler that may already be dead (onDestroy / process shutdown).
                try { overlayWm?.removeViewImmediate(view) } catch (_: Exception) {}
                overlayView = null
                overlayWm = null
                Log.d(TAG, "detachOverlay: removed")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT detach the overlay here. The system stops this service immediately
        // after onMessageReceived returns (~1.4 s), which is before WakeProxyActivity's
        // 2 s transition window.  WakeProxyActivity handles the detach via
        // detachOverlayFromProxy() posted to the process-scoped main Looper.
        handler.removeCallbacksAndMessages(null)
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
                    try { overlayWm?.removeViewImmediate(view) } catch (_: Exception) {}
                    overlayView = null
                    overlayWm = null
                }
            }
        }
    }
}
