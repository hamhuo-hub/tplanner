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
        FAILED,
    }

    private enum class AttemptResult(val errorCode: WatchSyncErrorCode?) {
        COMPLETED(null),
        PHONE_UNREACHABLE(WatchSyncErrorCode.PHONE_UNREACHABLE),
        RESPONSE_TIMEOUT(WatchSyncErrorCode.RESPONSE_TIMEOUT),
        INVALID_RESPONSE(WatchSyncErrorCode.INVALID_RESPONSE),
        BLUETOOTH_UNAVAILABLE(WatchSyncErrorCode.BLUETOOTH_UNAVAILABLE),
        TRANSPORT_FAILURE(WatchSyncErrorCode.TRANSPORT_FAILURE),
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
            val bluetoothResult = if (dataLayerResult == AttemptResult.COMPLETED) {
                AttemptResult.COMPLETED
            } else {
                requestViaBluetooth(appContext, request)
            }
            val finalAttempt = when {
                dataLayerResult == AttemptResult.COMPLETED ||
                    bluetoothResult == AttemptResult.COMPLETED -> AttemptResult.COMPLETED

                bluetoothResult == AttemptResult.BLUETOOTH_UNAVAILABLE -> dataLayerResult
                else -> bluetoothResult
            }
            val finalResult = if (finalAttempt == AttemptResult.COMPLETED) {
                Result.COMPLETED
            } else {
                logFinalFailure(request, finalAttempt)
                Result.FAILED
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
        val responseFailureRef = AtomicReference<AttemptResult?>()
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
                logAttemptFailure(
                    request,
                    AttemptResult.INVALID_RESPONSE,
                    "data-layer response decoding",
                    error,
                )
                responseFailureRef.compareAndSet(null, AttemptResult.INVALID_RESPONSE)
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
            if (nodes.isEmpty()) return AttemptResult.PHONE_UNREACHABLE
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
            if (!sent) {
                logAttemptFailure(request, AttemptResult.TRANSPORT_FAILURE, "data-layer send")
                return AttemptResult.TRANSPORT_FAILURE
            }
            if (!responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                responseFailureRef.get()?.let { return it }
                logAttemptFailure(request, AttemptResult.RESPONSE_TIMEOUT, "data-layer response")
                return AttemptResult.RESPONSE_TIMEOUT
            }
            responseFailureRef.get()?.let { return it }
            val live = responseRef.get() ?: return AttemptResult.INVALID_RESPONSE
            val snapshot = live.response.snapshot ?: return AttemptResult.INVALID_RESPONSE
            if (!storeSnapshot(context, snapshot)) return AttemptResult.INVALID_RESPONSE

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
            logAttemptFailure(request, AttemptResult.TRANSPORT_FAILURE, "data-layer interrupted", error)
            return AttemptResult.TRANSPORT_FAILURE
        } catch (error: Exception) {
            logAttemptFailure(request, AttemptResult.TRANSPORT_FAILURE, "data-layer transport", error)
            return AttemptResult.TRANSPORT_FAILURE
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
        if (!hasBluetoothConnectPermission(context)) return AttemptResult.BLUETOOTH_UNAVAILABLE
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return AttemptResult.BLUETOOTH_UNAVAILABLE
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            return AttemptResult.BLUETOOTH_UNAVAILABLE
        }
        val phone = findPairedPhone(adapter) ?: return AttemptResult.BLUETOOTH_UNAVAILABLE
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
                return AttemptResult.INVALID_RESPONSE
            }
            if (!storeSnapshot(context, response.snapshot)) return AttemptResult.INVALID_RESPONSE
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
                logAttemptFailure(
                    request,
                    AttemptResult.TRANSPORT_FAILURE,
                    "bluetooth receipt ACK byte=$ack",
                )
                AttemptResult.TRANSPORT_FAILURE
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            logAttemptFailure(request, AttemptResult.TRANSPORT_FAILURE, "bluetooth interrupted", error)
            AttemptResult.TRANSPORT_FAILURE
        } catch (error: Exception) {
            logAttemptFailure(request, AttemptResult.TRANSPORT_FAILURE, "bluetooth transport", error)
            AttemptResult.TRANSPORT_FAILURE
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

    private fun logAttemptFailure(
        request: WatchScheduleRefreshProtocol.Request,
        result: AttemptResult,
        stage: String,
        error: Throwable? = null,
    ) {
        val code = result.errorCode ?: return
        val message = "${code.id} ${code.description}; stage=$stage request=${request.requestId}"
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    private fun logFinalFailure(
        request: WatchScheduleRefreshProtocol.Request,
        result: AttemptResult,
    ) {
        val code = result.errorCode ?: WatchSyncErrorCode.TRANSPORT_FAILURE
        Log.e(TAG, "${code.id} ${code.description}; sync failed request=${request.requestId}")
    }

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
