package com.hamhuo.tplanner

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * Classic Bluetooth RFCOMM bridge for phone -> watch schedule delivery.
 *
 * Used as a fallback when Google Play Services (and thus the Wearable Data Layer) is
 * unavailable on the paired phone — e.g. Chinese Samsung devices without GMS.
 *
 * The service binds a [BluetoothServerSocket] on a known UUID and accepts connections
 * in a background loop. Incoming payloads are validated and stored through
 * [ScheduleStore], which applies the same checks as the GMS path:
 * schema version, hash, version monotonicity.
 */
class BluetoothScheduleBridgeService : Service() {
    private var serverThread: Thread? = null
    private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var running = false

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR,
                    )
                    when (state) {
                        BluetoothAdapter.STATE_ON -> startServer()
                        BluetoothAdapter.STATE_TURNING_OFF,
                        BluetoothAdapter.STATE_OFF,
                        -> stopServer()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btStateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(btStateReceiver, filter)
        }
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) startServer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        runCatching { unregisterReceiver(btStateReceiver) }
        super.onDestroy()
    }

    private fun startServer() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.d(TAG, "startServer: Bluetooth not available or disabled")
            return
        }
        if (running) return
        running = true
        serverThread = Thread({
            acceptLoop(adapter)
        }, "tplanner-bt-bridge").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "startServer: listening on $RFCOMM_UUID")
    }

    private fun stopServer() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread = null
        Log.d(TAG, "stopServer: stopped")
    }

    private fun acceptLoop(adapter: BluetoothAdapter) {
        while (running) {
            try {
                serverSocket?.close()
            } catch (_: Exception) { /* ignore close errors */ }
            serverSocket = try {
                adapter.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME,
                    RFCOMM_UUID,
                )
            } catch (e: Exception) {
                Log.e(TAG, "acceptLoop: failed to create server socket", e)
                delayBetweenRetries()
                continue
            }

            var socket: BluetoothSocket? = null
            try {
                Log.d(TAG, "acceptLoop: waiting for connection")
                socket = serverSocket!!.accept()
                Log.d(
                    TAG,
                    "acceptLoop: connected from ${socket.remoteDevice.name ?: socket.remoteDevice.address}",
                )
                handleConnection(socket)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "acceptLoop: accept failed, retrying", e)
                }
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val payload = reader.readText()
            if (payload.isNotBlank()) {
                Log.d(TAG, "handleConnection: received ${payload.length} bytes")
                ScheduleStore.store(this, payload, ScheduleStore.SOURCE_BLUETOOTH)
                // Send single-byte ACK so the phone side knows the transfer completed.
                socket.outputStream.write(ACK_BYTE)
                socket.outputStream.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleConnection: read failed", e)
        } finally {
            runCatching { socket.close() }
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
        private const val RETRY_DELAY_MS = 5_000L
        private const val ACK_BYTE: Int = 0x06

        /**
         * Shared RFCOMM UUID — must match the phone-side value in
         * [com.hamhuo.tplanner.WatchScheduleSync.Companion.RFCOMM_UUID].
         */
        val RFCOMM_UUID: UUID = UUID.fromString("7f8a9b2c-3d4e-5f6a-7b8c-9d0e1f2a3b4c")
    }
}
