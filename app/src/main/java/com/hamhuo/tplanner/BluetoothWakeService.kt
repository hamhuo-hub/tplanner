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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothWakeService : Service() {

    private val running = AtomicBoolean(false)
    private var listenerThread: Thread? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null

    // All timeouts and lifecycle callbacks use this single main-thread handler
    // so they can be cleaned up together in onDestroy().
    private val mainHandler = Handler(Looper.getMainLooper())

    // Track the wake lock so onDestroy() can release it early (the
    // acquire(timeout) fallback still fires, but this avoids holding the
    // screen on during the destroy-and-restart window).
    @Volatile private var pendingWakeLock: PowerManager.WakeLock? = null
    private val wakeLockLock = Any()

    // LocationListener for API < R must be unregistered, or the
    // LocationManager will keep a reference and continue delivering
    // updates after the service is destroyed.
    @Volatile private var pendingLocationListener: LocationListener? = null

    override fun onCreate() {
        super.onCreate()
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "onCreate: missing BLUETOOTH_CONNECT permission")
            stopSelf()
            return
        }
        // Only start listening if the foreground notification was posted
        // successfully.  Without it the system will kill the service
        // (especially on API 26+), and the listener thread would run in a
        // doomed process.
        if (startAsForegroundService()) {
            startListening()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)

        // --- WakeLock cleanup ---
        releaseWakeLock()

        // --- Location / timeout cleanup ---
        mainHandler.removeCallbacksAndMessages(null)
        pendingLocationListener?.let { listener ->
            try {
                getSystemService(LocationManager::class.java)?.removeUpdates(listener)
            } catch (_: Exception) {
                // Best-effort cleanup; nothing to recover from.
            }
            pendingLocationListener = null
        }

        // --- Bluetooth listener cleanup ---
        closeServerSocket()
        listenerThread?.interrupt()
        listenerThread = null

        super.onDestroy()
    }

    // ---------- Foreground service lifecycle ----------

    /** @return true if the foreground notification was posted successfully. */
    private fun startAsForegroundService(): Boolean {
        createNotificationChannel()
        val notification = buildServiceNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Android 12+ 已知问题（issuetracker 214253535）：START_STICKY 被系统杀后
            // 重启的瞬间进程可能仍算后台，startForeground 会抛
            // ForegroundServiceStartNotAllowedException。吞掉并停止，等下一次
            // BOOT_COMPLETED / 用户打开 App / 手表连接时再拉起。
            Log.e(TAG, "startAsForegroundService: startForeground failed", e)
            stopSelf()
            false
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

    // ---------- Bluetooth listening ----------

    private fun startListening() {
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "startListening: missing BLUETOOTH_CONNECT permission")
            stopSelf()
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            // 无蓝牙硬件——真正无法工作，才允许自杀
            Log.e(TAG, "startListening: no Bluetooth adapter on this device")
            stopSelf()
            return
        }

        if (!running.compareAndSet(false, true)) return

        listenerThread = Thread {
            // 蓝牙未启用时在这里等待而不是 stopSelf()：BOOT_COMPLETED 往往先于
            // 蓝牙栈就绪到达，开机自启的服务若见蓝牙没好就自杀，"无感知常驻"
            // 直接失效；用户手动关开蓝牙同理（stopSelf 后 START_STICKY 不会再
            // 拉起）。前台服务挂着等蓝牙恢复才是正确姿势。
            waitForBluetoothOrStop()
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
                        // 如果蓝牙已关闭，等待恢复而非不断创建新 socket 重试
                        if (!adapter.isEnabled) {
                            Log.w(TAG, "startListening: Bluetooth disabled, pausing listener")
                            waitForBluetoothOrStop()
                        } else {
                            sleepBeforeRetry()
                        }
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

    /** Busy-wait until Bluetooth comes back or the service is stopped. */
    private fun waitForBluetoothOrStop() {
        while (running.get()) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) return
            sleepBeforeRetry()
        }
    }

    private fun handleSocket(socket: BluetoothSocket) {
        socket.use {
            val command = it.inputStream.read()
            Log.d(TAG, "handleSocket: command=$command")
            if (command >= 0) {
                logWatchInvocation()
                launchMainActivityFromWatch()
            }
        }
    }

    // ── 手表唤醒打点：时间戳 + 位置写入今日随笔 ──────────────────────────────
    // 时间戳立即落盘（保证必有记录），定位异步获取、到达后补充到同一行——
    // 不阻塞拉起 MainActivity。行格式固定为 "- ⌚ HH:mm 📍lat,lng"，坐标
    // 保留六位小数（约 0.1m 精度），便于之后解析出轨迹在地图上展示。
    private fun logWatchInvocation() {
        val store = JournalStore(this)
        val stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val baseLine = "- ⌚ $stamp"
        store.appendToday(baseLine)
        fetchCurrentLocation { loc ->
            if (loc != null) {
                val coords = String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)
                store.replaceInToday(baseLine, "$baseLine 📍$coords")
            }
        }
    }

    // ---------- Location ----------

    // 无 GMS 设备（国行三星）上不用 FusedLocationProviderClient，直接走框架
    // LocationManager：优先系统 fused provider（API 31+），再网络定位，最后 GPS。
    // 服务在后台取定位需要用户授予"始终允许"（ACCESS_BACKGROUND_LOCATION）；
    // 未授予/超时则回退最近一次已知位置，再不行就只记时间戳，绝不阻塞。
    private fun fetchCurrentLocation(onResult: (Location?) -> Unit) {
        // 服务正在销毁时不发起新定位请求
        if (!running.get()) {
            onResult(null)
            return
        }

        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            Log.w(TAG, "fetchCurrentLocation: no location permission")
            onResult(null); return
        }
        val lm = getSystemService(LocationManager::class.java)
        if (lm == null) { onResult(null); return }

        val provider = pickProvider(lm)
        if (provider == null) { onResult(lastKnownLocation(lm)); return }

        val done = AtomicBoolean(false)

        // ── 前一次 pendingLocationListener 在发起新请求前清理 ──
        cleanupLocationRequest(lm)

        val timeoutRunnable = Runnable {
            Log.w(TAG, "fetchCurrentLocation: timed out, falling back to last known")
            if (done.compareAndSet(false, true)) {
                cleanupLocationRequest(lm)
                onResult(lastKnownLocation(lm))
            }
        }

        val finish: (Location?) -> Unit = { loc ->
            if (done.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeoutRunnable)
                cleanupLocationRequest(lm)
                onResult(loc ?: lastKnownLocation(lm))
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // One-shot callback via the modern API.  We pass null for
                // CancellationSignal because the caller would need API 31+
                // and the AtomicBoolean guard already prevents duplicate
                // callbacks; cleanupLocationRequest is a no-op on R+.
                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(this)) { loc ->
                    finish(loc)
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        finish(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                pendingLocationListener = listener
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
            mainHandler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MS)
        } catch (e: SecurityException) {
            // 后台取定位被系统拒绝（未授予"始终允许"）——回退最近已知位置
            Log.w(TAG, "fetchCurrentLocation: denied in background", e)
            mainHandler.removeCallbacks(timeoutRunnable)
            cleanupLocationRequest(lm)
            onResult(lastKnownLocation(lm))
        } catch (e: Exception) {
            Log.e(TAG, "fetchCurrentLocation: failed", e)
            mainHandler.removeCallbacks(timeoutRunnable)
            cleanupLocationRequest(lm)
            onResult(lastKnownLocation(lm))
        }
    }

    private fun pickProvider(lm: LocationManager): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            lm.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> null
    }

    private fun lastKnownLocation(lm: LocationManager): Location? = try {
        lm.allProviders.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (_: SecurityException) { null }

    /**
     * Unregister [pendingLocationListener] (API < R only).
     *
     * Must be called:
     *  1. before registering a new listener (so the old one doesn't leak), and
     *  2. on every terminal path (success, timeout, exception) so the
     *     LocationManager does not hold a reference to the service after
     *     the request completes.
     *
     * Safe to call on API 30+ (no-op) or when no listener is pending.
     */
    private fun cleanupLocationRequest(lm: LocationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        pendingLocationListener?.let { listener ->
            try {
                lm.removeUpdates(listener)
            } catch (_: Exception) {
                // Best-effort.
            }
            pendingLocationListener = null
        }
    }

    // ---------- Screen wake ----------

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

    /**
     * Acquires a wake lock to briefly turn on the screen.
     *
     * The lock is held for 5 seconds (enough for [MainActivity] to start
     * and acquire its own wakelock) and automatically released by the
     * system after the timeout.  Additionally the lock is tracked in
     * [pendingWakeLock] so that [onDestroy] can release it early if the
     * service is stopped before the timeout fires.
     */
    private fun wakeScreenBriefly() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        synchronized(wakeLockLock) {
            // Release any previous wakelock that hasn't timed out yet.
            releaseWakeLock()
            pendingWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName:WatchWake"
            )
            try {
                pendingWakeLock?.acquire(5_000L)
            } catch (e: SecurityException) {
                // android.Manifest.permission.WAKE_LOCK is automatically
                // granted at install time, but some custom ROMs block it.
                Log.e(TAG, "wakeScreenBriefly: WAKE_LOCK denied", e)
            } catch (e: Exception) {
                Log.e(TAG, "wakeScreenBriefly: failed", e)
            }
        }
    }

    /** Releases the current wakelock, if any.  Idempotent. */
    private fun releaseWakeLock() {
        synchronized(wakeLockLock) {
            pendingWakeLock?.let {
                if (it.isHeld) {
                    try {
                        it.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "releaseWakeLock: failed", e)
                    }
                }
            }
            pendingWakeLock = null
        }
    }

    // ---------- Permissions & utilities ----------

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
        private const val LOCATION_TIMEOUT_MS = 8_000L
        val SERVICE_UUID: UUID = UUID.fromString("8b9f1e2a-7c4d-4a3b-9e5f-6d2c1a8b4f3e")
    }
}
