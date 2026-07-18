package com.hamhuo.tplanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-scoped one-shot location coordinator.
 *
 * It deliberately outlives a Service callback and a Compose effect. Only one capture generation
 * may own platform listeners at a time; a newer wake invalidates and removes every older listener.
 */
object LocationCapture {
    private const val TAG = "TplannerLocation"
    private const val CAPTURE_TIMEOUT_MS = 20_000L
    private const val UPDATE_INTERVAL_MS = 1_000L
    private const val CACHE_MAX_AGE_MS = 120_000L
    private const val CACHE_MAX_ACCURACY_METERS = 500f
    private const val UPDATE_MAX_AGE_MS = 30_000L
    private const val UPDATE_MAX_ACCURACY_METERS = 2_000f
    private const val COMPLETE_MAX_AGE_MS = 15_000L
    private const val COMPLETE_MAX_ACCURACY_METERS = 100f
    // Network / fused providers typically deliver in < 3 s.  If none of them are available,
    // don't wait the full 20 s — GPS is the only option and a cold start needs most of it.
    private const val NETWORK_ONLY_DEADLINE_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generationCounter = AtomicLong(0L)
    private val generationLock = Any()

    @Volatile private var latestGeneration = 0L
    @Volatile private var activeGeneration = 0L
    private var active: ActiveCapture? = null // main thread only

    data class Handle internal constructor(
        val generation: Long,
        val requestId: String,
    )

    private data class PermissionState(val coarse: Boolean, val fine: Boolean)

    private class ActiveCapture(
        val handle: Handle,
        val context: Context,
        val locationManager: LocationManager,
        val finePermission: Boolean,
        var best: Location?,
    ) {
        val listeners = linkedMapOf<String, LocationListener>()
        var timeoutRunnable: Runnable? = null
    }

    /** Starts a fresh foreground capture and supersedes any prior generation. */
    fun start(context: Context): Handle {
        val handle = newHandle()
        val appContext = context.applicationContext
        runOnMain { beginCapture(appContext, handle) }
        return handle
    }

    /**
     * Background-safe, sensor-free warm-up used by WakeDataLayerService. It only evaluates
     * last-known fixes and writes one when its real fix age and accuracy pass strict limits.
     */
    fun primeFreshCache(context: Context) {
        val handle = newHandle()
        val appContext = context.applicationContext
        runOnMain {
            if (!isLatest(handle)) return@runOnMain
            cancelActiveInternal("new wake cache prime")
            val locationManager = appContext.getSystemService(LocationManager::class.java)
                ?: return@runOnMain
            val permission = permissionState(appContext)
            val cached = bestFreshLastKnown(locationManager, permission)
            if (cached != null && isLatest(handle)) {
                WatchLocationStore.save(appContext, cached, handle.requestId, fromCache = true)
                logAccepted(handle, cached, fromCache = true)
            }
        }
    }

    fun isActive(handle: Handle): Boolean =
        latestGeneration == handle.generation && activeGeneration == handle.generation

    fun isLatest(handle: Handle): Boolean = latestGeneration == handle.generation

    fun cancel(handle: Handle) {
        runOnMain {
            if (activeGeneration == handle.generation) {
                cancelActiveInternal("caller cancelled")
            }
        }
    }

    private fun newHandle(): Handle = synchronized(generationLock) {
        val generation = generationCounter.incrementAndGet()
        latestGeneration = generation
        Handle(generation, UUID.randomUUID().toString())
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun beginCapture(context: Context, handle: Handle) {
        if (!isLatest(handle)) return
        cancelActiveInternal("superseded")

        val locationManager = context.getSystemService(LocationManager::class.java) ?: return
        val permission = permissionState(context)
        val cached = bestFreshLastKnown(locationManager, permission)
        val state = ActiveCapture(
            handle = handle,
            context = context,
            locationManager = locationManager,
            finePermission = permission.fine,
            best = cached?.let(::Location),
        )
        active = state
        activeGeneration = handle.generation

        if (cached != null) {
            WatchLocationStore.save(context, cached, handle.requestId, fromCache = true)
            logAccepted(handle, cached, fromCache = true)
        }

        if (!permission.coarse && !permission.fine) {
            finishCapture(state, "no foreground location permission")
            return
        }
        if (!isLocationEnabled(locationManager)) {
            finishCapture(state, "location disabled")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            isProviderUsable(locationManager, LocationManager.FUSED_PROVIDER)) {
            registerProvider(state, LocationManager.FUSED_PROVIDER)
        }
        if (isProviderUsable(locationManager, LocationManager.NETWORK_PROVIDER)) {
            registerProvider(state, LocationManager.NETWORK_PROVIDER)
        }

        val gpsAvailable = permission.fine &&
            isProviderUsable(locationManager, LocationManager.GPS_PROVIDER)
        if (gpsAvailable) {
            registerProvider(state, LocationManager.GPS_PROVIDER)
        }

        if (state.listeners.isEmpty()) {
            finishCapture(state, "no enabled provider")
            return
        }

        // If only GPS is available (no network / fused), a cold start takes most of the budget;
        // a network provider delivers within a few seconds — cut the wait short once it's clear
        // the network path has nothing new to offer.
        val hasNetworkProvider = state.listeners.keys.any {
            it == LocationManager.FUSED_PROVIDER || it == LocationManager.NETWORK_PROVIDER
        }
        state.timeoutRunnable = Runnable {
            if (isCurrent(state)) finishCapture(state, "deadline")
        }.also {
            mainHandler.postDelayed(
                it,
                if (hasNetworkProvider) NETWORK_ONLY_DEADLINE_MS else CAPTURE_TIMEOUT_MS,
            )
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun registerProvider(state: ActiveCapture, provider: String) {
        if (!isCurrent(state) || state.listeners.containsKey(provider)) return

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                considerLocation(state, location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        state.listeners[provider] = listener
        try {
            state.locationManager.requestLocationUpdates(
                provider,
                UPDATE_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            Log.d(TAG, "generation=${state.handle.generation} registered provider=$provider")
        } catch (e: Exception) {
            state.listeners.remove(provider)
            Log.w(TAG, "generation=${state.handle.generation} provider=$provider failed", e)
        }
    }

    private fun considerLocation(state: ActiveCapture, incoming: Location) {
        if (!isCurrent(state) || !isUsableUpdate(incoming)) return
        val candidate = Location(incoming)
        val previous = state.best
        if (previous != null && locationScore(candidate) >= locationScore(previous)) return

        state.best = candidate
        val fromCache = locationAgeMs(candidate) > 5_000L
        WatchLocationStore.save(
            state.context,
            candidate,
            state.handle.requestId,
            fromCache = fromCache,
        )
        logAccepted(state.handle, candidate, fromCache = fromCache)

        if (locationAgeMs(candidate) <= COMPLETE_MAX_AGE_MS &&
            candidate.accuracy <= COMPLETE_MAX_ACCURACY_METERS) {
            finishCapture(state, "good fix")
        }
    }

    private fun finishCapture(state: ActiveCapture, reason: String) {
        if (!isCurrent(state)) return
        cancelActiveInternal(reason)
    }

    private fun cancelActiveInternal(reason: String) {
        val state = active
        active = null
        activeGeneration = 0L
        if (state == null) return

        state.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        state.listeners.values.forEach { listener ->
            runCatching { state.locationManager.removeUpdates(listener) }
        }
        state.listeners.clear()
        Log.d(TAG, "generation=${state.handle.generation} finished reason=$reason")
    }

    private fun isCurrent(state: ActiveCapture): Boolean =
        active === state &&
            activeGeneration == state.handle.generation &&
            latestGeneration == state.handle.generation

    private fun permissionState(context: Context): PermissionState {
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return PermissionState(coarse = coarse, fine = fine)
    }

    private fun isLocationEnabled(locationManager: LocationManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching { locationManager.isLocationEnabled }.getOrDefault(false)
        }
        return isProviderUsable(locationManager, LocationManager.NETWORK_PROVIDER) ||
            isProviderUsable(locationManager, LocationManager.GPS_PROVIDER)
    }

    private fun isProviderUsable(locationManager: LocationManager, provider: String): Boolean {
        val exists = runCatching { locationManager.allProviders.contains(provider) }
            .getOrDefault(false)
        return exists && runCatching { locationManager.isProviderEnabled(provider) }
            .getOrDefault(false)
    }

    @Suppress("MissingPermission")
    private fun bestFreshLastKnown(
        locationManager: LocationManager,
        permission: PermissionState,
    ): Location? {
        if (!permission.coarse && !permission.fine) return null
        val available = runCatching { locationManager.allProviders.toSet() }
            .getOrDefault(emptySet())
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                LocationManager.FUSED_PROVIDER in available) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (LocationManager.NETWORK_PROVIDER in available) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            if (permission.fine && LocationManager.GPS_PROVIDER in available) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (LocationManager.PASSIVE_PROVIDER in available) {
                add(LocationManager.PASSIVE_PROVIDER)
            }
        }.distinct()

        return providers.asSequence()
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter(::isFreshCache)
            .minByOrNull(::locationScore)
            ?.let(::Location)
    }

    private fun isFreshCache(location: Location): Boolean =
        isSane(location) &&
            locationAgeMs(location) <= CACHE_MAX_AGE_MS &&
            location.accuracy <= CACHE_MAX_ACCURACY_METERS

    private fun isUsableUpdate(location: Location): Boolean =
        isSane(location) &&
            locationAgeMs(location) <= UPDATE_MAX_AGE_MS &&
            location.accuracy <= UPDATE_MAX_ACCURACY_METERS

    private fun isSane(location: Location): Boolean {
        if (location.time <= 0L || !location.hasAccuracy()) return false
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) {
            return false
        }
        val accuracy = location.accuracy
        return !accuracy.isNaN() && !accuracy.isInfinite() && accuracy >= 0f
    }

    private fun locationAgeMs(location: Location): Long {
        val fixElapsedNanos = location.elapsedRealtimeNanos
        if (fixElapsedNanos > 0L) {
            val delta = SystemClock.elapsedRealtimeNanos() - fixElapsedNanos
            return if (delta >= 0L) delta / 1_000_000L else Long.MAX_VALUE
        }

        val now = System.currentTimeMillis()
        val fixTime = location.time
        if (fixTime <= 0L || fixTime > now + 60_000L) return Long.MAX_VALUE
        return (now - fixTime).coerceAtLeast(0L)
    }

    private fun locationScore(location: Location): Long =
        locationAgeMs(location) + (location.accuracy * 200f).toLong()

    private fun logAccepted(handle: Handle, location: Location, fromCache: Boolean) {
        Log.d(
            TAG,
            "generation=${handle.generation} accepted provider=${location.provider} " +
                "ageMs=${locationAgeMs(location)} accuracy=${location.accuracy} " +
                "cache=$fromCache",
        )
    }
}
