package com.hamhuo.tplanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Phone-side classic Bluetooth server for durable watch -> V3 semantic-command envelopes.
 *
 * Blocking RFCOMM and Room work stays on the server thread. A PHONE_STORED response follows only
 * the atomic command-outbox transaction; completion waits for the central snapshot projection.
 */
class WatchTaskImportService : Service() {
    @Volatile
    private var running = false
    @Volatile
    private var serverGeneration = 0L
    private var serverThread: Thread? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var receiverRegistered = false
    private var foregroundStarted = false

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startServer()
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF,
                -> stopServer()
            }
        }
    }

    private val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "tplanner-watch-import-watchdog").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }

    override fun onCreate() {
        super.onCreate()
        if (!hasBluetoothConnectPermission(this) || !enterForeground()) {
            stopSelf()
            return
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btStateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(btStateReceiver, filter)
        }
        receiverRegistered = true
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasBluetoothConnectPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted && !enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(btStateReceiver) }
            receiverRegistered = false
        }
        watchdog.shutdownNow()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.watch_link_notification),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch_link)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.watch_link_notification))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
        true
    } catch (error: Exception) {
        Log.e(TAG, "Unable to enter foreground", error)
        false
    }

    @Synchronized
    private fun startServer() {
        if (running || serverThread?.isAlive == true) return
        if (!hasBluetoothConnectPermission(this)) return
        val adapter = bluetoothAdapter(this)
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) return

        running = true
        val generation = serverGeneration + 1L
        serverGeneration = generation
        serverThread = Thread(
            { acceptLoop(adapter, generation) },
            "tplanner-watch-task-import",
        ).apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Listening on ${WatchTaskProtocol.RFCOMM_UUID}")
    }

    @Synchronized
    private fun stopServer() {
        running = false
        serverGeneration += 1L
        runCatching { serverSocket?.close() }
        runCatching { activeSocket?.close() }
        serverSocket = null
        activeSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(adapter: BluetoothAdapter, generation: Long) {
        while (isGenerationActive(generation)) {
            val listeningSocket = try {
                adapter.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME,
                    WatchTaskProtocol.RFCOMM_UUID,
                )
            } catch (error: Exception) {
                if (isGenerationActive(generation)) {
                    Log.e(TAG, "Unable to create RFCOMM server socket", error)
                }
                delayBetweenRetries()
                continue
            }

            synchronized(this) {
                if (!isGenerationActive(generation)) {
                    runCatching { listeningSocket.close() }
                    return
                }
                serverSocket = listeningSocket
            }
            try {
                while (isGenerationActive(generation)) {
                    val socket = listeningSocket.accept()
                    synchronized(this) {
                        if (!isGenerationActive(generation)) {
                            runCatching { socket.close() }
                            return
                        }
                        activeSocket = socket
                    }
                    try {
                        handleConnection(socket)
                    } finally {
                        runCatching { socket.close() }
                        synchronized(this) {
                            if (activeSocket === socket) activeSocket = null
                        }
                    }
                }
            } catch (error: Exception) {
                if (isGenerationActive(generation)) {
                    Log.w(TAG, "RFCOMM accept failed; retrying", error)
                }
            } finally {
                runCatching { listeningSocket.close() }
                synchronized(this) {
                    if (serverSocket === listeningSocket) serverSocket = null
                }
            }
        }
    }

    private fun isGenerationActive(generation: Long): Boolean =
        running && serverGeneration == generation

    @SuppressLint("MissingPermission")
    private fun handleConnection(socket: BluetoothSocket) {
        val timeout = watchdog.schedule(
            { runCatching { socket.close() } },
            CONNECTION_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        var responsePayload = WatchTaskProtocol.encodeResponse(WatchTaskProtocol.Response(
            requestId = WatchTaskProtocol.UNKNOWN_REQUEST_ID,
            status = WatchTaskProtocol.Status.RETRY,
            errorCode = "INCOMPLETE_FRAME",
        ))
        var handledScheduleRefresh = false
        try {
            val remote = socket.remoteDevice
            if (remote.bondState != BluetoothDevice.BOND_BONDED) {
                responsePayload = WatchTaskProtocol.encodeResponse(WatchTaskProtocol.Response(
                    requestId = WatchTaskProtocol.UNKNOWN_REQUEST_ID,
                    status = WatchTaskProtocol.Status.REJECTED,
                    errorCode = "UNBONDED_DEVICE",
                ))
            } else {
                val raw = ScheduleRfcommProtocol.readFrame(socket.inputStream)
                if (WatchScheduleRefreshProtocol.isRefreshRequest(raw)) {
                    handledScheduleRefresh = true
                    handleScheduleRefresh(socket, raw)
                } else {
                    responsePayload = if (WatchTaskProtocol.isRequestBatch(raw)) {
                        val batch = WatchTaskProtocol.decodeRequestBatch(raw)
                        val responses = batch.requests.map { request ->
                            WatchTaskImporter.importRequestBlocking(applicationContext, request)
                        }
                        WatchTaskProtocol.encodeResponseBatch(
                            WatchTaskProtocol.ResponseBatch(batch.batchId, responses),
                        )
                    } else {
                        // One-release compatibility for a watch that connected before upgrading.
                        WatchTaskProtocol.encodeResponse(
                            WatchTaskImporter.importBlocking(
                                applicationContext,
                                raw,
                                fallbackRequestId = null,
                            ),
                        )
                    }
                    Log.d(TAG, "Handled Watch command frame peer=${runCatching { remote.address }.getOrDefault("unknown")}")
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Rejected incomplete RFCOMM transfer", error)
        } finally {
            timeout.cancel(false)
            if (!handledScheduleRefresh) {
                runCatching {
                    val payload = ScheduleRfcommProtocol.encodePayload(
                        responsePayload,
                    )
                    ScheduleRfcommProtocol.writeFrame(socket.outputStream, payload)
                }.onFailure { error ->
                    Log.w(TAG, "Unable to send RFCOMM response", error)
                }
            }
        }
    }

    private fun handleScheduleRefresh(socket: BluetoothSocket, raw: String) {
        var finalAck = ScheduleRfcommProtocol.NAK_BYTE
        try {
            val request = WatchScheduleRefreshProtocol.decodeRequest(raw)
            val snapshot = runCatching {
                WatchScheduleSnapshotProvider.queueCurrent(applicationContext)
            }.onFailure { error ->
                Log.e(TAG, "Unable to prepare RFCOMM refresh request=${request.requestId}", error)
            }.getOrNull()
            val response = WatchScheduleRefreshProtocol.Response(
                requestId = request.requestId,
                snapshot = snapshot,
                errorCode = if (snapshot == null) "SNAPSHOT_UNAVAILABLE" else null,
            )
            ScheduleRfcommProtocol.writeFrame(
                socket.outputStream,
                WatchScheduleRefreshProtocol.encodeResponse(response)
                    .toByteArray(Charsets.UTF_8),
            )
            if (snapshot == null) return

            val receipt = WatchScheduleRefreshProtocol.decodeReceipt(
                ScheduleRfcommProtocol.readFrame(socket.inputStream),
            )
            val expected = WatchScheduleRefreshProtocol.receiptFor(
                requestId = request.requestId,
                snapshot = snapshot,
                acceptedAtEpochMs = receipt.acceptedAtEpochMs,
            )
            if (
                receipt.requestId == expected.requestId &&
                receipt.version == expected.version &&
                receipt.hash == expected.hash
            ) {
                val pendingCleared = WatchScheduleSync.acknowledgeSnapshot(
                    applicationContext,
                    receipt.version,
                    receipt.hash,
                )
                finalAck = ScheduleRfcommProtocol.ACK_BYTE
                Log.d(
                    TAG,
                    "RFCOMM refresh acknowledged request=${request.requestId} " +
                        "pendingCleared=$pendingCleared",
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "RFCOMM schedule refresh failed", error)
        } finally {
            runCatching {
                socket.outputStream.write(finalAck)
                socket.outputStream.flush()
            }.onFailure { error ->
                Log.w(TAG, "Unable to send RFCOMM refresh acknowledgement", error)
            }
        }
    }

    private fun delayBetweenRetries() {
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "TplannerWatchImport"
        private const val SERVICE_NAME = "TPlanner Watch Task Import"
        private const val NOTIFICATION_CHANNEL_ID = "watch_task_import"
        private const val NOTIFICATION_ID = 0x5451
        private const val RETRY_DELAY_MS = 5_000L
        private const val CONNECTION_TIMEOUT_MS = 20_000L

        internal fun hasBluetoothConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED

        internal fun startIfAllowed(context: Context): Boolean {
            val appContext = context.applicationContext
            if (!hasBluetoothConnectPermission(appContext)) return false
            return runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, WatchTaskImportService::class.java),
                )
                true
            }.getOrElse { error ->
                Log.w(TAG, "Foreground service start rejected", error)
                false
            }
        }

        private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter
    }
}

/** Shared parser used by both transports; all persistence is delegated to WatchCommandBridge. */
internal object WatchTaskImporter {
    private const val TAG = "TplannerWatchImporter"

    fun importBlocking(
        context: Context,
        raw: String,
        fallbackRequestId: String?,
    ): WatchTaskProtocol.Response {
        val request = try {
            WatchTaskProtocol.decodeCompatibleRequest(raw)
        } catch (error: WatchTaskProtocol.ProtocolException) {
            val requestId = fallbackRequestId
                ?: WatchTaskProtocol.bestEffortRequestId(raw)
                ?: WatchTaskProtocol.UNKNOWN_REQUEST_ID
            Log.w(TAG, "Invalid watch task request code=${error.errorCode}", error)
            return WatchTaskProtocol.Response(
                requestId = requestId,
                status = WatchTaskProtocol.Status.REJECTED,
                errorCode = error.errorCode,
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unexpected request parsing failure", error)
            return WatchTaskProtocol.Response(
                requestId = fallbackRequestId ?: WatchTaskProtocol.UNKNOWN_REQUEST_ID,
                status = WatchTaskProtocol.Status.REJECTED,
                errorCode = "MALFORMED_REQUEST",
            )
        }

        if (fallbackRequestId != null && fallbackRequestId != request.requestId) {
            return WatchTaskProtocol.Response(
                requestId = fallbackRequestId,
                status = WatchTaskProtocol.Status.REJECTED,
                errorCode = "PATH_ID_MISMATCH",
            )
        }

        return importRequestBlocking(context, request)
    }

    fun importRequestBlocking(
        context: Context,
        request: WatchTaskProtocol.Request,
    ): WatchTaskProtocol.Response = try {
        WatchCommandBridge.importBlocking(context.applicationContext, request)
    } catch (error: Exception) {
            Log.e(TAG, "Task import failed request=${request.requestId}", error)
            WatchTaskProtocol.Response(
                requestId = request.requestId,
                status = WatchTaskProtocol.Status.RETRY,
                commandIds = request.commands.map(WatchTaskProtocol.SemanticCommand::commandId),
                errorCode = "PERSISTENCE_FAILED",
            )
        }

}
