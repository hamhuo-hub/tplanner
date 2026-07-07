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
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException
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

    // Persistent 1×1 invisible overlay — keeps the process in a state where
    // Samsung's BAL checker won't block startActivity().  Attached at service
    // start, removed at service destroy.
    @Volatile private var persistentOverlay: android.view.View? = null

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
            attachPersistentOverlay()
            startListening()
            // Arm the 15-minute keepalive alarm.  If Samsung kills :bluetooth
            // process, AlarmManager will wake us back up and restart the service.
            KeepAliveReceiver.schedule(this)
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

        // --- Persistent overlay cleanup ---
        detachPersistentOverlay()

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
                // 仅取定位存入 WatchLocationStore 供焦虑面板逆地理编码用，
                // 不写入随笔——记录动作延迟到用户点 Done 时才由 UI 层触发。
                fetchCurrentLocation { loc ->
                    if (loc != null) {
                        WatchLocationStore.save(this, loc.latitude, loc.longitude)
                    }
                }
                launchMainActivityFromWatch()
            }
        }
    }

    // ---------- Location ----------

    // 无 GMS 设备（国行三星）上不用 FusedLocationProviderClient，直接走框架
    // LocationManager：优先系统 fused provider（API 31+），再网络定位，最后 GPS。
    // 三星 fused provider 在室内/无 GPS 时常 <1s 返回 null，因此每个 provider
    // 失败后自动级联尝试下一个，全部失败才回退 lastKnownLocation。
    // 服务在后台取定位需要用户授予"始终允许"（ACCESS_BACKGROUND_LOCATION）；
    // 未授予/超时则回退最近一次已知位置，再不行就只记时间戳，绝不阻塞。
    private fun fetchCurrentLocation(onResult: (Location?) -> Unit) {
        if (!running.get()) { onResult(null); return }

        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            Log.w(TAG, "fetchCurrentLocation: no location permission")
            onResult(null); return
        }
        val lm = getSystemService(LocationManager::class.java)
        if (lm == null) { onResult(null); return }

        // Build cascade: [fused, network, gps] — only enabled + available providers
        val allCandidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.allProviders.contains(LocationManager.FUSED_PROVIDER))
                add(LocationManager.FUSED_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                add(LocationManager.NETWORK_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                add(LocationManager.GPS_PROVIDER)
        }
        if (allCandidates.isEmpty()) {
            Log.w(TAG, "fetchCurrentLocation: no provider available")
            onResult(lastKnownLocation(lm)); return
        }

        Log.d(TAG, "fetchCurrentLocation: cascade = $allCandidates")
        tryNextProvider(lm, allCandidates, 0, onResult)
    }

    // 逐个尝试 provider；当前 provider 失败（getCurrentLocation 返回 null）时
    // 自动级联到下一个，全失败则回退 lastKnownLocation。
    private fun tryNextProvider(
        lm: LocationManager,
        candidates: List<String>,
        idx: Int,
        onResult: (Location?) -> Unit,
    ) {
        if (idx >= candidates.size) {
            Log.w(TAG, "fetchCurrentLocation: all providers exhausted, lastKnown")
            onResult(lastKnownLocation(lm))
            return
        }
        val provider = candidates[idx]
        Log.d(TAG, "fetchCurrentLocation: trying provider $provider (${idx + 1}/${candidates.size})")

        val done = AtomicBoolean(false)
        cleanupLocationRequest(lm)

        // Per-provider timeout: 6s for fused (Samsung returns fast anyway),
        // 10s for network/gps (need more time to get a fix).
        val providerTimeout = if (provider == LocationManager.FUSED_PROVIDER) 6000L else 10000L

        val timeoutRunnable = Runnable {
            if (done.compareAndSet(false, true)) {
                cleanupLocationRequest(lm)
                Log.d(TAG, "fetchCurrentLocation: $provider timed out, trying next")
                tryNextProvider(lm, candidates, idx + 1, onResult)
            }
        }

        val finish: (Location?) -> Unit = { loc ->
            if (done.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeoutRunnable)
                cleanupLocationRequest(lm)
                if (loc != null) {
                    Log.d(TAG, "fetchCurrentLocation: got fix from $provider")
                    onResult(loc)
                } else {
                    Log.d(TAG, "fetchCurrentLocation: $provider returned null, trying next")
                    tryNextProvider(lm, candidates, idx + 1, onResult)
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(this)) { loc ->
                    finish(loc)
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) { finish(location) }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                pendingLocationListener = listener
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
            mainHandler.postDelayed(timeoutRunnable, providerTimeout)
        } catch (e: SecurityException) {
            Log.w(TAG, "fetchCurrentLocation: $provider denied in background", e)
            mainHandler.removeCallbacks(timeoutRunnable)
            cleanupLocationRequest(lm)
            tryNextProvider(lm, candidates, idx + 1, onResult)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCurrentLocation: $provider failed", e)
            mainHandler.removeCallbacks(timeoutRunnable)
            cleanupLocationRequest(lm)
            tryNextProvider(lm, candidates, idx + 1, onResult)
        }
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

    // ---------- Persistent overlay (BAL bypass) ----------

    /**
     * Attaches a 1×1 invisible overlay window that persists for the lifetime
     * of this service.
     *
     * Samsung's BAL checker (see logcat: "Background activity launch blocked")
     * blocks [startActivity] from any process that has no visible window,
     * even foreground services.  Keeping a TYPE_APPLICATION_OVERLAY attached
     * puts the process into a "has window" state where the checker allows
     * activity starts — no notification noise, no user-visible artifacts.
     */
    private fun attachPersistentOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "attachPersistentOverlay: overlay permission not granted")
            return
        }
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
                android.graphics.PixelFormat.TRANSLUCENT,
            )
            wm.addView(view, params)
            persistentOverlay = view
            Log.d(TAG, "attachPersistentOverlay: attached")
        } catch (e: Exception) {
            Log.e(TAG, "attachPersistentOverlay: failed", e)
        }
    }

    private fun detachPersistentOverlay() {
        persistentOverlay?.let { view ->
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Exception) {}
            persistentOverlay = null
        }
    }

    // ---------- Screen wake ----------

    /**
     * Launches MainActivity in response to a watch Bluetooth signal.
     *
     * Relies on [attachPersistentOverlay] to keep the process in a "visible
     * window" state where Samsung's BAL checker won't block.  Uses
     * FLAG_ACTIVITY_CLEAR_TASK (not REORDER_TO_FRONT) to avoid Samsung's
     * "balDontBringExistingBackgroundTaskStackToFg" check.
     */
    private fun launchMainActivityFromWatch() {
        wakeScreenBriefly()

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            putExtra(MainActivity.EXTRA_WAKE_FROM_WATCH, true)
        }

        try {
            startActivity(intent)
            Log.d(TAG, "launchMainActivityFromWatch: started")
        } catch (e: Exception) {
            Log.e(TAG, "launchMainActivityFromWatch: failed", e)
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
