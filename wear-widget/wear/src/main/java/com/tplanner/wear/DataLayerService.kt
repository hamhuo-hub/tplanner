package com.tplanner.wear

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.*

/** Receives events/journal pushed from the phone companion app. */
class DataLayerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            val path = event.dataItem.uri.path ?: return@forEach
            if (!path.startsWith("/tplanner")) return@forEach

            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val eventsJson  = dataMap.getString("events",  "[]")
            val journalJson = dataMap.getString("journal", "{}")

            saveEvents(eventsJson)
            saveJournals(journalJson)
            Log.d("DataLayer", "Data updated from phone")

            // Notify Tile to refresh
            scope.launch {
                try { EventTileService.requestUpdate(this@DataLayerService) }
                catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
