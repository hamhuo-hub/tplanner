package com.hamhuo.tplanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Classic Bluetooth RFCOMM receiver for phone -> watch schedule delivery. */
class BluetoothScheduleBridgeService : Service() {
    @Volatile
    private var running = false
    @Volatile
    private var serverGeneration = 0L
    private var serverThread: Thread? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var receiverRegistered = false
    private var foregroundStarted = false

    /**
     * Keep the foreground service alive while Bluetooth is off. Manifest receivers for
     * ACTION_STATE_CHANGED are not a reliable cold-start mechanism on modern Android, so this
     * process-local receiver only opens/closes the server socket and remains registered.
     */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (
                intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR,
                )
            ) {
                BluetoothAdapter.STATE_ON -> startServer()
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF,
                -> stopServer()
            }
        }
    }
    private val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "tplanner-bt-watchdog").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }

    override fun onCreate() {
        super.onCreate()
        if (!hasBluetoothConnectPermission(this)) {
            Log.w(TAG, "onCreate: BLUETOOTH_CONNECT not granted")
            stopSelf()
            return
        }
        if (!enterForeground()) {
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (foregroundStarted) enterForeground()
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.bt_bridge_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.bt_bridge_channel_description)
                setShowBadge(false)
            },
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_schedule)
            .setContentTitle(getString(R.string.bt_bridge_notification_title))
            .setContentText(getString(R.string.bt_bridge_notification_text))
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
    } catch (e: Exception) {
        Log.e(TAG, "enterForeground: unable to start connected-device foreground service", e)
        false
    }

    @Synchronized
    private fun startServer() {
        if (running || serverThread?.isAlive == true) return
        if (!hasBluetoothConnectPermission(this)) {
            Log.w(TAG, "startServer: BLUETOOTH_CONNECT not granted")
            return
        }
        val adapter = bluetoothAdapter(this)
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) {
            Log.d(TAG, "startServer: Bluetooth not available or disabled")
            return
        }

        running = true
        val generation = serverGeneration + 1L
        serverGeneration = generation
        serverThread = Thread({ acceptLoop(adapter, generation) }, "tplanner-bt-bridge").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "startServer: listening on $RFCOMM_UUID")
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
        Log.d(TAG, "stopServer: stopped")
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(adapter: BluetoothAdapter, generation: Long) {
        // startServer checks BLUETOOTH_CONNECT before creating this thread. Permission revocation
        // races are contained by the try/catch blocks around every privileged socket operation.
        while (isServerGenerationActive(generation)) {
            val listeningSocket = try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, RFCOMM_UUID)
            } catch (e: Exception) {
                if (isServerGenerationActive(generation)) {
                    Log.e(TAG, "acceptLoop: failed to create server socket", e)
                }
                delayBetweenRetries()
                continue
            }

            synchronized(this) {
                if (!isServerGenerationActive(generation)) {
                    runCatching { listeningSocket.close() }
                    return
                }
                serverSocket = listeningSocket
            }

            try {
                while (isServerGenerationActive(generation)) {
                    Log.d(TAG, "acceptLoop: waiting for connection")
                    val socket = listeningSocket.accept()
                    synchronized(this) {
                        if (!isServerGenerationActive(generation)) {
                            runCatching { socket.close() }
                            return
                        }
                        activeSocket = socket
                    }
                    try {
                        val peer = runCatching {
                            socket.remoteDevice.name ?: socket.remoteDevice.address
                        }.getOrDefault("unknown")
                        Log.d(TAG, "acceptLoop: connected from $peer")
                        handleConnection(socket)
                    } finally {
                        runCatching { socket.close() }
                        synchronized(this) {
                            if (activeSocket === socket) activeSocket = null
                        }
                    }
                }
            } catch (e: Exception) {
                if (isServerGenerationActive(generation)) {
                    Log.w(TAG, "acceptLoop: accept failed, retrying", e)
                }
            } finally {
                runCatching { listeningSocket.close() }
                synchronized(this) {
                    if (serverSocket === listeningSocket) serverSocket = null
                }
            }
        }
    }

    private fun isServerGenerationActive(generation: Long): Boolean =
        running && serverGeneration == generation

    private fun handleConnection(socket: BluetoothSocket) {
        val timeout = watchdog.schedule(
            {
                Log.w(TAG, "handleConnection: timed out; closing socket")
                runCatching { socket.close() }
            },
            CONNECTION_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        var response = ScheduleRfcommProtocol.NAK_BYTE
        try {
            val payload = ScheduleRfcommProtocol.readFrame(socket.inputStream)
            val result = ScheduleStore.store(this, payload, ScheduleStore.SOURCE_BLUETOOTH)
            response = if (result.shouldAcknowledge) {
                ScheduleRfcommProtocol.ACK_BYTE
            } else {
                ScheduleRfcommProtocol.NAK_BYTE
            }
            Log.d(
                TAG,
                "handleConnection: result=$result response=$response",
            )
        } catch (e: Exception) {
            Log.w(TAG, "handleConnection: rejected malformed or incomplete frame", e)
        } finally {
            timeout.cancel(false)
            runCatching {
                socket.outputStream.write(response)
                socket.outputStream.flush()
            }.onFailure { error ->
                Log.w(TAG, "handleConnection: failed to send response=$response", error)
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
        private const val TAG = "TplannerBtBridge"
        private const val SERVICE_NAME = "tPlanner Schedule"
        private const val NOTIFICATION_CHANNEL_ID = "schedule_bridge"
        private const val NOTIFICATION_ID = 0x5450
        private const val RETRY_DELAY_MS = 5_000L
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        val RFCOMM_UUID: UUID = UUID.fromString("7f8a9b2c-3d4e-5f6a-7b8c-9d0e1f2a3b4c")

        internal fun hasBluetoothConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED

        internal fun startIfAllowed(context: Context): Boolean {
            val appContext = context.applicationContext
            if (!hasBluetoothConnectPermission(appContext)) {
                return false
            }
            return runCatching {
                val intent = Intent(appContext, BluetoothScheduleBridgeService::class.java)
                ContextCompat.startForegroundService(appContext, intent)
                true
            }.getOrElse { error ->
                Log.w(TAG, "startIfAllowed: service start rejected", error)
                false
            }
        }

        private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter
    }
}
