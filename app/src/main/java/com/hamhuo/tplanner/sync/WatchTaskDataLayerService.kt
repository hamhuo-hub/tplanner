package com.hamhuo.tplanner

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

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
            WatchTaskAckPublisher.publish(applicationContext, response)
        }
    }
}
