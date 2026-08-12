package com.hamhuo.tplanner

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit

/** Durable GMS receiver for one DataItem per watch-created task request id. */
class WatchTaskDataLayerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path
            val pathRequestId =
                WatchTaskProtocol.requestIdFromPath(
                    path,
                    WatchTaskProtocol.REQUEST_PATH_PREFIX,
                ) ?: WatchTaskProtocol.requestIdFromPath(
                    path,
                    WatchTaskProtocol.DELETE_REQUEST_PATH_PREFIX,
                ) ?: continue
            val raw = event.dataItem.data?.let { String(it, Charsets.UTF_8) } ?: continue
            val response = WatchTaskImporter.importBlocking(
                applicationContext,
                raw,
                fallbackRequestId = pathRequestId,
            )
            publishAck(response)
        }
    }

    private fun publishAck(response: WatchTaskProtocol.Response) {
        try {
            val payload = WatchTaskProtocol.encodeResponse(
                response.copy(acknowledgedAtEpochMs = System.currentTimeMillis()),
            ).toByteArray(Charsets.UTF_8)
            val request = PutDataRequest.create(
                WatchTaskProtocol.ackPath(response.requestId),
            ).setUrgent().apply {
                data = payload
            }
            Tasks.await(
                Wearable.getDataClient(applicationContext).putDataItem(request),
                ACK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            Log.d(TAG, "Published ACK request=${response.requestId} status=${response.status}")
        } catch (error: Exception) {
            // The watch retains and republishes its request. Idempotent import will then return
            // ALREADY_STORED and publish the terminal ACK again.
            Log.e(TAG, "Unable to publish ACK request=${response.requestId}", error)
        }
    }

    private companion object {
        const val TAG = "TplannerWatchDataRcv"
        const val ACK_TIMEOUT_SECONDS = 10L
    }
}
