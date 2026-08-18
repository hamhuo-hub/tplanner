package com.hamhuo.tplanner

import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit

/** Phone endpoint for live watch refresh requests and watch-side delivery receipts. */
class WatchScheduleRefreshService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when {
            messageEvent.path == WatchScheduleRefreshProtocol.REQUEST_MESSAGE_PATH ->
                handleRefreshRequest(messageEvent)

            WatchScheduleRefreshProtocol.requestIdFromPath(
                messageEvent.path,
                WatchScheduleRefreshProtocol.RECEIPT_MESSAGE_PATH_PREFIX,
            ) != null -> handleReceipt(
                raw = String(messageEvent.data, Charsets.UTF_8),
                expectedRequestId = WatchScheduleRefreshProtocol.requestIdFromPath(
                    messageEvent.path,
                    WatchScheduleRefreshProtocol.RECEIPT_MESSAGE_PATH_PREFIX,
                ),
            )
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path?.startsWith(
                    WatchScheduleRefreshProtocol.DELIVERY_ACK_PATH_PREFIX,
                ) != true
            ) continue
            val raw = event.dataItem.data?.let { String(it, Charsets.UTF_8) } ?: continue
            handleReceipt(raw, expectedRequestId = null)
            runCatching {
                Tasks.await(
                    Wearable.getDataClient(applicationContext).deleteDataItems(
                        Uri.parse(event.dataItem.uri.toString()),
                    ),
                    IO_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }.onFailure { error -> Log.w(TAG, "Unable to remove processed schedule ACK", error) }
        }
    }

    private fun handleRefreshRequest(messageEvent: MessageEvent) {
        val request = try {
            WatchScheduleRefreshProtocol.decodeRequest(
                String(messageEvent.data, Charsets.UTF_8),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Rejected malformed refresh request", error)
            return
        }
        val snapshot = runCatching {
            WatchScheduleSnapshotProvider.queueCurrent(applicationContext)
        }.onFailure { error ->
            Log.e(TAG, "Unable to prepare refresh snapshot request=${request.requestId}", error)
        }.getOrNull()
        val response = WatchScheduleRefreshProtocol.Response(
            requestId = request.requestId,
            snapshot = snapshot,
            errorCode = if (snapshot == null) "SNAPSHOT_UNAVAILABLE" else null,
        )
        try {
            Tasks.await(
                Wearable.getMessageClient(applicationContext).sendMessage(
                    messageEvent.sourceNodeId,
                    WatchScheduleRefreshProtocol.responseMessagePath(request.requestId),
                    WatchScheduleRefreshProtocol.encodeResponse(response)
                        .toByteArray(Charsets.UTF_8),
                ),
                IO_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            Log.d(TAG, "Sent live schedule response request=${request.requestId}")
        } catch (error: Exception) {
            Log.w(TAG, "Unable to send refresh response request=${request.requestId}", error)
        }
    }

    private fun handleReceipt(raw: String, expectedRequestId: String?) {
        try {
            val receipt = WatchScheduleRefreshProtocol.decodeReceipt(raw)
            if (expectedRequestId != null && receipt.requestId != expectedRequestId) {
                Log.w(TAG, "Receipt path/request mismatch")
                return
            }
            val cleared = WatchScheduleSync.acknowledgeSnapshot(
                applicationContext,
                receipt.version,
                receipt.hash,
            )
            Log.d(
                TAG,
                "Received watch receipt request=${receipt.requestId} " +
                    "version=${receipt.version} pendingCleared=$cleared",
            )
        } catch (error: Exception) {
            Log.w(TAG, "Rejected malformed schedule receipt", error)
        }
    }

    private companion object {
        const val TAG = "TplannerScheduleCtl"
        const val IO_TIMEOUT_SECONDS = 10L
    }
}
