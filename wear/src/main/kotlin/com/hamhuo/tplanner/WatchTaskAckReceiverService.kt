package com.hamhuo.tplanner

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/** Applies business-level acknowledgements after the phone has durably stored a watch task. */
class WatchTaskAckReceiverService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val pathRequestId = WatchTaskCreateProtocol.requestIdFromPath(
                event.dataItem.uri.path,
                WatchTaskCreateProtocol.ACK_PATH_PREFIX,
            ) ?: continue
            val bytes = event.dataItem.data ?: continue
            try {
                val response = WatchTaskCreateProtocol.decodeResponse(
                    String(bytes, Charsets.UTF_8),
                )
                if (response.requestId == pathRequestId) {
                    WatchTaskOutbox.handleResponse(applicationContext, response)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Invalid task ACK path=${event.dataItem.uri.path}", error)
            }
        }
    }

    private companion object {
        const val TAG = "TplannerTaskAck"
    }
}
