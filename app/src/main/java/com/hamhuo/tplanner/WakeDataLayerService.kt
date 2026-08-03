package com.hamhuo.tplanner

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Phone-side receiver for durable Watch -> Phone wake requests.
 *
 * The watch publishes a requestId DataItem and may send the same payload through
 * MessageClient as a fast path. Both paths are de-duplicated. The request is ACKed only
 * after MainActivity consumes the requestId, not merely after a proxy launch is accepted.
 */
class WakeDataLayerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WAKE_PATH) {
            Log.d(TAG, "onMessageReceived: ignoring path=${messageEvent.path}")
            return
        }
        val request = parseWakeRequest(
            messageEvent.data,
            fallbackId = "legacy-${messageEvent.sourceNodeId}-${System.currentTimeMillis()}",
        ) ?: return
        Log.d(
            TAG,
            "onMessageReceived: request=${request.requestId} source=${messageEvent.sourceNodeId}",
        )
        processWakeRequest(request, "message")
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != WAKE_REQUEST_PATH) continue
            val bytes = event.dataItem.data ?: continue
            val request = parseWakeRequest(bytes, fallbackId = null) ?: continue
            Log.d(TAG, "onDataChanged: request=${request.requestId}")
            processWakeRequest(request, "data_item")
        }
    }

    private fun parseWakeRequest(bytes: ByteArray, fallbackId: String?): WakeRequest? {
        return try {
            if (bytes.isEmpty()) {
                fallbackId?.let { WakeRequest(it, System.currentTimeMillis()) }
            } else {
                val payload = JSONObject(String(bytes, Charsets.UTF_8))
                val schemaVersion = payload.optInt("schemaVersion", -1)
                if (schemaVersion != WAKE_SCHEMA_VERSION) {
                    Log.w(TAG, "parseWakeRequest: unsupported schema=$schemaVersion")
                    return null
                }
                val requestId = payload.optString("requestId").takeIf { it.isNotBlank() }
                    ?: fallbackId
                    ?: return null
                WakeRequest(
                    requestId = requestId.take(MAX_REQUEST_ID_LENGTH),
                    createdAtEpochMs = payload.optLong("createdAtEpochMs", 0L),
                    expired = isExpired(payload.optLong("createdAtEpochMs", 0L)),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseWakeRequest: invalid payload", e)
            null
        }
    }

    private fun processWakeRequest(request: WakeRequest, source: String) {
        if (request.expired) {
            Log.w(
                TAG,
                "processWakeRequest: expired/invalid request=${request.requestId} " +
                    "createdAt=${request.createdAtEpochMs} source=$source",
            )
            publishAck(applicationContext, request.requestId, "expired")
            return
        }
        val alreadyProcessed = synchronized(requestLock) {
            when {
                isProcessedLocked(applicationContext, request.requestId) -> true
                !processingRequestIds.add(request.requestId) -> return
                else -> false
            }
        }
        if (alreadyProcessed) {
            Log.d(TAG, "processWakeRequest: duplicate request=${request.requestId} source=$source")
            publishAck(applicationContext, request.requestId, "duplicate")
            return
        }

        val launched = try {
            launchWakeProxy(request.requestId, source)
        } catch (e: Exception) {
            Log.e(TAG, "processWakeRequest: unexpected launch failure request=${request.requestId}", e)
            false
        }
        if (launched) {
            // MainActivity is the consumer. Keep a short in-flight guard against the
            // MessageClient/DataItem double delivery, but do not ACK merely because
            // ActivityManager accepted the proxy launch.
            scheduleProcessingTimeout(request.requestId)
        } else {
            synchronized(requestLock) { processingRequestIds.remove(request.requestId) }
        }
    }

    private fun isExpired(createdAtEpochMs: Long): Boolean {
        if (createdAtEpochMs <= 0L) return true
        val now = System.currentTimeMillis()
        if (createdAtEpochMs > now + MAX_FUTURE_CLOCK_SKEW_MS) return true
        return now - createdAtEpochMs > WAKE_REQUEST_TTL_MS
    }

    private fun scheduleProcessingTimeout(requestId: String) {
        wakeHandler.postDelayed(
            {
                val removed = synchronized(requestLock) {
                    processingRequestIds.remove(requestId)
                }
                if (removed) {
                    Log.w(TAG, "processing timeout; request may retry=$requestId")
                }
            },
            PROCESSING_GUARD_MS,
        )
    }

    private fun launchWakeProxy(requestId: String, source: String): Boolean {
        Log.d(TAG, "launchWakeProxy: request=$requestId source=$source")

        // Attach the invisible overlay before startActivity so Samsung's BAL check sees
        // a visible-window process.
        attachOverlay()

        // Only prime a qualified last-known cache here. MainScreen starts active location
        // listeners after it is visible.
        LocationCapture.primeFreshCache(this)

        val intent = Intent(this, WakeProxyActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(WakeProxyActivity.EXTRA_WAKE_FROM_WATCH, true)
            putExtra(WakeProxyActivity.EXTRA_WAKE_REQUEST_ID, requestId)
        }
        return try {
            startActivity(intent)
            Log.d(TAG, "launchWakeProxy: proxy accepted request=$requestId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "launchWakeProxy: failed request=$requestId", e)
            detachOverlay()
            false
        }
    }

    private fun attachOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "attachOverlay: overlay permission not granted, BAL may block")
            return
        }
        synchronized(overlayLock) {
            if (overlayView != null) return
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val view = android.view.View(this)
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    type,
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
                try {
                    overlayWm?.removeViewImmediate(view)
                } catch (_: Exception) {
                }
                overlayView = null
                overlayWm = null
                Log.d(TAG, "detachOverlay: removed")
            }
        }
    }

    companion object {
        private const val TAG = "TplannerDataLayer"
        const val WAKE_PATH = "/tplanner/wake"
        private const val WAKE_REQUEST_PATH = "/tplanner/wake/request"
        private const val WAKE_ACK_PATH = "/tplanner/wake/ack"
        private const val WAKE_SCHEMA_VERSION = 1
        private const val WAKE_PREFS = "tplanner_processed_wake_requests"
        private const val KEY_PROCESSED_REQUESTS = "processed_requests"
        private const val MAX_PROCESSED_REQUESTS = 64
        private const val MAX_REQUEST_ID_LENGTH = 128
        private const val PROCESSING_GUARD_MS = 10_000L
        private const val WAKE_REQUEST_TTL_MS = 30 * 60_000L
        private const val MAX_FUTURE_CLOCK_SKEW_MS = 5 * 60_000L

        private data class WakeRequest(
            val requestId: String,
            val createdAtEpochMs: Long,
            val expired: Boolean = false,
        )

        private val requestLock = Any()
        private val processingRequestIds = mutableSetOf<String>()
        private val wakeHandler = Handler(Looper.getMainLooper())

        // Shared overlay state; WakeProxyActivity removes it after MainActivity is visible.
        private val overlayLock = Any()
        private var overlayView: android.view.View? = null
        private var overlayWm: WindowManager? = null

        /** Returns false for a request MainActivity already consumed, and re-publishes its ACK. */
        fun shouldConsumeWakeRequest(context: Context, requestId: String): Boolean {
            if (requestId.isBlank()) return true
            val normalizedId = requestId.take(MAX_REQUEST_ID_LENGTH)
            val alreadyProcessed = synchronized(requestLock) {
                isProcessedLocked(context.applicationContext, normalizedId)
            }
            if (alreadyProcessed) {
                publishAck(context.applicationContext, normalizedId, "duplicate")
            }
            return !alreadyProcessed
        }

        /** Commits consumption before publishing an ACK; commit failure deliberately leaves retry open. */
        fun completeWakeRequest(context: Context, requestId: String): Boolean {
            if (requestId.isBlank()) return false
            val appContext = context.applicationContext
            val normalizedId = requestId.take(MAX_REQUEST_ID_LENGTH)
            val status = synchronized(requestLock) {
                processingRequestIds.remove(normalizedId)
                if (isProcessedLocked(appContext, normalizedId)) {
                    "duplicate"
                } else if (rememberProcessedLocked(appContext, normalizedId)) {
                    "accepted"
                } else {
                    null
                }
            }
            if (status == null) {
                Log.e(TAG, "completeWakeRequest: persistence failed request=$normalizedId")
                return false
            }
            publishAck(appContext, normalizedId, status)
            return true
        }

        private fun publishAck(context: Context, requestId: String, status: String) {
            Thread({
                try {
                    val payload = JSONObject().apply {
                        put("schemaVersion", WAKE_SCHEMA_VERSION)
                        put("requestId", requestId)
                        put("status", status)
                        put("ackedAtEpochMs", System.currentTimeMillis())
                    }
                    val request = PutDataRequest.create(WAKE_ACK_PATH).setUrgent().apply {
                        data = payload.toString().toByteArray(Charsets.UTF_8)
                    }
                    Tasks.await(
                        Wearable.getDataClient(context).putDataItem(request),
                        10,
                        TimeUnit.SECONDS,
                    )
                    Log.d(TAG, "publishAck: request=$requestId status=$status")
                } catch (e: Exception) {
                    // The watch keeps retrying this requestId; the processed record lets the
                    // next delivery publish the same terminal ACK without reopening the UI.
                    Log.e(TAG, "publishAck: failed request=$requestId", e)
                }
            }, "tplanner-wake-ack").apply { isDaemon = true }.start()
        }

        private fun isProcessedLocked(context: Context, requestId: String): Boolean {
            val raw = context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROCESSED_REQUESTS, null)
                ?: return false
            return runCatching { JSONObject(raw).has(requestId) }.getOrDefault(false)
        }

        private fun rememberProcessedLocked(context: Context, requestId: String): Boolean {
            val prefs = context.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
            val processed = prefs.getString(KEY_PROCESSED_REQUESTS, null)
                ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                ?: JSONObject()
            processed.put(requestId, System.currentTimeMillis())
            if (processed.length() > MAX_PROCESSED_REQUESTS) {
                val oldestIds = buildList {
                    val keys = processed.keys()
                    while (keys.hasNext()) add(keys.next())
                }.sortedBy { id -> processed.optLong(id, Long.MAX_VALUE) }
                oldestIds.take(processed.length() - MAX_PROCESSED_REQUESTS)
                    .forEach { id -> processed.remove(id) }
            }
            val committed = prefs.edit()
                .putString(KEY_PROCESSED_REQUESTS, processed.toString())
                .commit()
            if (!committed) {
                Log.e(TAG, "rememberProcessedLocked: commit failed request=$requestId")
            }
            return committed
        }

        fun detachOverlayFromProxy() {
            synchronized(overlayLock) {
                overlayView?.let { view ->
                    try {
                        overlayWm?.removeViewImmediate(view)
                    } catch (_: Exception) {
                    }
                    overlayView = null
                    overlayWm = null
                }
            }
        }
    }
}
