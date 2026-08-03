package com.hamhuo.tplanner

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/** Removes durable wake requests only after the phone acknowledges them. */
class WakeAckReceiverService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PhoneWaker.ACK_PATH) continue
            try {
                val bytes = event.dataItem.data ?: continue
                val payload = JSONObject(String(bytes, Charsets.UTF_8))
                val schemaVersion = payload.optInt("schemaVersion", -1)
                val requestId = payload.optString("requestId")
                val status = payload.optString("status")
                if (
                    schemaVersion == WAKE_SCHEMA_VERSION &&
                    requestId.isNotBlank() &&
                    status in TERMINAL_STATUSES
                ) {
                    PhoneWaker.acknowledge(applicationContext, requestId.take(MAX_REQUEST_ID_LENGTH))
                } else {
                    Log.w(
                        TAG,
                        "onDataChanged: ignored schema=$schemaVersion status=$status " +
                            "requestPresent=${requestId.isNotBlank()}",
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "onDataChanged: invalid ACK", e)
            }
        }
    }

    private companion object {
        const val TAG = "TplannerWakeAck"
        const val WAKE_SCHEMA_VERSION = 1
        const val MAX_REQUEST_ID_LENGTH = 128
        val TERMINAL_STATUSES = setOf("accepted", "duplicate", "expired")
    }
}
