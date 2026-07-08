package com.hamhuo.tplanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side Data Layer listener — the sole watch→phone channel.
 *
 * When GMS delivers a [/tplanner/wake] message from the watch:
 *   1. Fetch current location (fire-and-forget, saved to [WatchLocationStore]
 *      for the anxiety panel).
 *   2. Launch [WakeProxyActivity], which delegates to [MainActivity] with
 *      REORDER_TO_FRONT — preserving user state.
 *
 * No foreground service.  No notification.  No RFCOMM listener.
 * Google Play Services (system process) handles the listening.
 * The trust chain: GMS → this Service → WakeProxyActivity → MainActivity.
 */
class WakeDataLayerService : WearableListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WAKE_PATH) {
            Log.d(TAG, "onMessageReceived: ignoring unknown path=${messageEvent.path}")
            return
        }

        Log.d(TAG, "onMessageReceived: watch wake via Data Layer (source=${messageEvent.sourceNodeId})")

        // Fire-and-forget: fetch location in background for the anxiety panel.
        // Location might arrive after the UI is already shown — MainScreen polls
        // WatchLocationStore so the race condition is handled there.
        fetchCurrentLocation()

        val intent = Intent(this, WakeProxyActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(WakeProxyActivity.EXTRA_WAKE_FROM_WATCH, true)
        }

        try {
            startActivity(intent)
            Log.d(TAG, "onMessageReceived: WakeProxyActivity started")
        } catch (e: Exception) {
            Log.e(TAG, "onMessageReceived: failed to start WakeProxyActivity", e)
        }
    }

    /**
     * Simplified location fetch — try fused provider first (fast on Samsung),
     * then cascade through network → GPS.  Falls back to last-known location.
     */
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
        if (idx >= candidates.size) {
            saveLastKnown(lm)
            return
        }
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
    }
}
