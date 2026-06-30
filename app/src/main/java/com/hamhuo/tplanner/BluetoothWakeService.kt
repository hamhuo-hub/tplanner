package com.hamhuo.tplanner

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothWakeService : Service() {

    private val running = AtomicBoolean(false)
    private var listenerThread: Thread? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "onCreate: missing BLUETOOTH_CONNECT permission")
            stopSelf()
            return
        }
        startAsForegroundService()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        closeServerSocket()
        listenerThread?.interrupt()
        listenerThread = null
        super.onDestroy()
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch_link)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.watch_link_notification))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Watch link",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun startListening() {
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "startListening: missing BLUETOOTH_CONNECT permission")
            stopSelf()
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "startListening: Bluetooth adapter unavailable or disabled")
            stopSelf()
            return
        }

        if (!running.compareAndSet(false, true)) return

        listenerThread = Thread {
            while (running.get()) {
                try {
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                    while (running.get()) {
                        val socket = serverSocket?.accept() ?: break
                        handleSocket(socket)
                    }
                } catch (e: IOException) {
                    if (running.get()) {
                        Log.e(TAG, "startListening: Bluetooth accept failed", e)
                        sleepBeforeRetry()
                    }
                } finally {
                    closeServerSocket()
                }
            }
        }.apply {
            name = "TPlannerBluetoothWake"
            isDaemon = true
            start()
        }
    }

    private fun handleSocket(socket: BluetoothSocket) {
        socket.use {
            val command = it.inputStream.read()
            Log.d(TAG, "handleSocket: command=$command")
            if (command >= 0) {
                launchMainActivityFromWatch()
            }
        }
    }

    private fun launchMainActivityFromWatch() {
        val canUseOverlayException =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        Log.d(TAG, "launchMainActivityFromWatch: canUseOverlayException=$canUseOverlayException")

        wakeScreenBriefly()

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_WAKE_FROM_WATCH, true)
        }

        try {
            startActivity(intent)
            Log.d(TAG, "launchMainActivityFromWatch: startActivity requested")
        } catch (e: Exception) {
            Log.e(TAG, "launchMainActivityFromWatch: startActivity failed", e)
        }
    }

    private fun wakeScreenBriefly() {
        val powerManager = getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$packageName:WatchWake"
        )
        try {
            wakeLock.acquire(5_000L)
        } catch (e: Exception) {
            Log.e(TAG, "wakeScreenBriefly: failed", e)
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        } finally {
            serverSocket = null
        }
    }

    private fun sleepBeforeRetry() {
        try {
            Thread.sleep(1_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "TplannerBluetoothWake"
        private const val CHANNEL_ID = "tplanner_watch_link"
        private const val NOTIFICATION_ID = 2048
        private const val SERVICE_NAME = "TPlannerWake"
        val SERVICE_UUID: UUID = UUID.fromString("8b9f1e2a-7c4d-4a3b-9e5f-6d2c1a8b4f3e")
    }
}
