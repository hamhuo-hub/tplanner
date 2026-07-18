package com.hamhuo.tplanner

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * Watch → phone wake-up signal via Wearable Data Layer.
 *
 * Google Play Services (system process) relays the message to the phone-side
 * [WakeDataLayerService], which delegates through [WakeProxyActivity] to
 * [MainActivity] with REORDER_TO_FRONT — the user's state is preserved.
 *
 * This is the only channel.  No RFCOMM, no foreground service, no notification.
 * Works on all Wear OS devices with Google Play Services (international Galaxy
 * Watch, Pixel Watch, etc.).
 */
object PhoneWaker {
    private const val TAG = "TplannerWear"
    private const val WAKE_PATH = "/tplanner/wake"

    fun wakeUpPhone(context: Context) {
        Thread {
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = Tasks.await(nodeClient.connectedNodes, 3, TimeUnit.SECONDS)
                if (nodes.isNullOrEmpty()) {
                    Log.w(TAG, "wakeUpPhone: no connected Wear OS nodes")
                    return@Thread
                }
                val phoneNode = nodes.firstOrNull { !it.isNearby } ?: nodes.first()
                Log.d(TAG, "wakeUpPhone: sending to node=${phoneNode.displayName}")
                val messageClient = Wearable.getMessageClient(context)
                Tasks.await(messageClient.sendMessage(phoneNode.id, WAKE_PATH, ByteArray(0)))
                Log.d(TAG, "wakeUpPhone: signal sent via Data Layer")
            } catch (e: Exception) {
                Log.e(TAG, "wakeUpPhone: Data Layer unavailable", e)
            }
        }.start()
    }
}
