package com.hamhuo.tplanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Performs a live phone request and reports success only after both endpoints acknowledge it. */
internal object WatchManualSync {
    internal enum class Result {
        COMPLETED,
        WAITING_FOR_PHONE,
        FAILED,
    }

    private enum class AttemptResult {
        COMPLETED,
        UNAVAILABLE,
        FAILED,
    }

    private data class LiveResponse(
        val sourceNodeId: String,
        val response: WatchScheduleRefreshProtocol.Response,
    )

    private const val TAG = "TplannerManualSync"
    private const val IO_TIMEOUT_SECONDS = 10L
    private const val RESPONSE_TIMEOUT_SECONDS = 12L
    private const val RFCOMM_CONNECT_TIMEOUT_MS = 10_000L
    private const val RFCOMM_RESPONSE_TIMEOUT_MS = 12_000L
    private const val RFCOMM_ACK_TIMEOUT_MS = 5_000L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-manual-sync").apply { isDaemon = true }
    }
    private val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "tplanner-manual-sync-watchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun request(context: Context, onComplete: (Result) -> Unit) {
        val appContext = context.applicationContext
        WatchTaskOutbox.resumePending(appContext)
        BluetoothScheduleBridgeService.startIfAllowed(appContext)

        worker.execute {
            val request = WatchScheduleRefreshProtocol.Request(
                requestId = UUID.randomUUID().toString(),
                requestedAtEpochMs = System.currentTimeMillis(),
            )
            val dataLayerResult = requestViaDataLayer(appContext, request)
            val finalResult = if (dataLayerResult == AttemptResult.COMPLETED) {
                Result.COMPLETED
            } else {
                when (val bluetoothResult = requestViaBluetooth(appContext, request)) {
                    AttemptResult.COMPLETED -> Result.COMPLETED
                    AttemptResult.FAILED -> Result.FAILED
                    AttemptResult.UNAVAILABLE -> if (dataLayerResult == AttemptResult.FAILED) {
                        Result.FAILED
                    } else {
                        Result.WAITING_FOR_PHONE
                    }
                }
            }
            mainHandler.post { onComplete(finalResult) }
        }
    }

    private fun requestViaDataLayer(
        context: Context,
        request: WatchScheduleRefreshProtocol.Request,
    ): AttemptResult {
        val messageClient = Wearable.getMessageClient(context)
        val responseRef = AtomicReference<LiveResponse?>()
        val responseLatch = CountDownLatch(1)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            val pathRequestId = WatchScheduleRefreshProtocol.requestIdFromPath(
                event.path,
                WatchScheduleRefreshProtocol.RESPONSE_MESSAGE_PATH_PREFIX,
            ) ?: return@OnMessageReceivedListener
            if (pathRequestId != request.requestId) return@OnMessageReceivedListener
            val response = runCatching {
                WatchScheduleRefreshProtocol.decodeResponse(
                    String(event.data, Charsets.UTF_8),
                )
            }.onFailure { error ->
                Log.w(TAG, "Rejected live refresh response", error)
            }.getOrNull() ?: return@OnMessageReceivedListener
            if (response.requestId != request.requestId) return@OnMessageReceivedListener
            if (responseRef.compareAndSet(null, LiveResponse(event.sourceNodeId, response))) {
                responseLatch.countDown()
            }
        }

        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                IO_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            if (nodes.isEmpty()) return AttemptResult.UNAVAILABLE
            Tasks.await(
                messageClient.addListener(listener),
                IO_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            val requestBytes = WatchScheduleRefreshProtocol.encodeRequest(request)
                .toByteArray(Charsets.UTF_8)
            val sent = nodes.any { node ->
                runCatching {
                    Tasks.await(
                        messageClient.sendMessage(
                            node.id,
                            WatchScheduleRefreshProtocol.REQUEST_MESSAGE_PATH,
                            requestBytes,
                        ),
                        IO_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    true
                }.onFailure { error ->
                    Log.w(TAG, "Unable to send refresh request to node=${node.id}", error)
                }.getOrDefault(false)
            }
            if (!sent) return AttemptResult.UNAVAILABLE
            if (!responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Phone did not answer live refresh request=${request.requestId}")
                return AttemptResult.UNAVAILABLE
            }
            val live = responseRef.get() ?: return AttemptResult.FAILED
            val snapshot = live.response.snapshot ?: return AttemptResult.FAILED
            if (!storeSnapshot(context, snapshot)) return AttemptResult.FAILED

            val receipt = WatchScheduleRefreshProtocol.receiptFor(
                requestId = request.requestId,
                snapshot = snapshot,
                acceptedAtEpochMs = System.currentTimeMillis(),
            )
            Tasks.await(
                messageClient.sendMessage(
                    live.sourceNodeId,
                    WatchScheduleRefreshProtocol.receiptMessagePath(request.requestId),
                    WatchScheduleRefreshProtocol.encodeReceipt(receipt)
                        .toByteArray(Charsets.UTF_8),
                ),
                IO_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            return AttemptResult.COMPLETED
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return AttemptResult.FAILED
        } catch (error: Exception) {
            Log.w(TAG, "Live Data Layer refresh failed", error)
            return AttemptResult.UNAVAILABLE
        } finally {
            runCatching {
                Tasks.await(
                    messageClient.removeListener(listener),
                    IO_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestViaBluetooth(
        context: Context,
        request: WatchScheduleRefreshProtocol.Request,
    ): AttemptResult {
        if (!hasBluetoothConnectPermission(context)) return AttemptResult.UNAVAILABLE
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return AttemptResult.UNAVAILABLE
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            return AttemptResult.UNAVAILABLE
        }
        val phone = findPairedPhone(adapter) ?: return AttemptResult.UNAVAILABLE
        var socket: BluetoothSocket? = null
        return try {
            val connected = phone.createRfcommSocketToServiceRecord(WatchTaskProtocol.RFCOMM_UUID)
            socket = connected
            withSocketWatchdog(connected, RFCOMM_CONNECT_TIMEOUT_MS, "connect") {
                connected.connect()
            }
            ScheduleRfcommProtocol.writeFrame(
                connected.outputStream,
                WatchScheduleRefreshProtocol.encodeRequest(request)
                    .toByteArray(Charsets.UTF_8),
            )
            val response = WatchScheduleRefreshProtocol.decodeResponse(
                withSocketWatchdog(connected, RFCOMM_RESPONSE_TIMEOUT_MS, "response") {
                    ScheduleRfcommProtocol.readFrame(connected.inputStream)
                },
            )
            if (response.requestId != request.requestId || response.snapshot == null) {
                return AttemptResult.FAILED
            }
            if (!storeSnapshot(context, response.snapshot)) return AttemptResult.FAILED
            val receipt = WatchScheduleRefreshProtocol.receiptFor(
                requestId = request.requestId,
                snapshot = response.snapshot,
                acceptedAtEpochMs = System.currentTimeMillis(),
            )
            ScheduleRfcommProtocol.writeFrame(
                connected.outputStream,
                WatchScheduleRefreshProtocol.encodeReceipt(receipt)
                    .toByteArray(Charsets.UTF_8),
            )
            val ack = withSocketWatchdog(connected, RFCOMM_ACK_TIMEOUT_MS, "ACK") {
                connected.inputStream.read()
            }
            if (ack == ScheduleRfcommProtocol.ACK_BYTE) {
                AttemptResult.COMPLETED
            } else {
                Log.w(TAG, "Phone rejected refresh receipt byte=$ack")
                AttemptResult.FAILED
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            AttemptResult.FAILED
        } catch (error: Exception) {
            Log.w(TAG, "Bluetooth refresh failed", error)
            AttemptResult.UNAVAILABLE
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun storeSnapshot(context: Context, snapshot: String): Boolean = when (
        ScheduleStore.store(context, snapshot)
    ) {
        ScheduleStore.StoreResult.STORED,
        ScheduleStore.StoreResult.ALREADY_CURRENT,
        ScheduleStore.StoreResult.STALE,
        -> true

        ScheduleStore.StoreResult.REJECTED,
        ScheduleStore.StoreResult.COMMIT_FAILED,
        -> false
    }

    @SuppressLint("MissingPermission")
    private fun findPairedPhone(adapter: BluetoothAdapter): BluetoothDevice? = runCatching {
        adapter.bondedDevices.firstOrNull { device ->
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
        }
    }.getOrNull()

    private fun hasBluetoothConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun <T> withSocketWatchdog(
        socket: BluetoothSocket,
        timeoutMs: Long,
        operation: String,
        block: () -> T,
    ): T {
        val expired = AtomicBoolean(false)
        val timeout = watchdog.schedule(
            {
                expired.set(true)
                runCatching { socket.close() }
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )
        return try {
            block()
        } catch (error: Exception) {
            if (expired.get()) {
                throw SocketTimeoutException("RFCOMM $operation timed out").apply {
                    initCause(error)
                }
            }
            throw error
        } finally {
            timeout.cancel(false)
        }
    }
}
