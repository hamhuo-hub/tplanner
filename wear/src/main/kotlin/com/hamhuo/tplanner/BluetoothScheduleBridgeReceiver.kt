package com.hamhuo.tplanner

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the bridge after boot/update and follows Bluetooth power state changes. */
class BluetoothScheduleBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                BluetoothScheduleBridgeService.startIfAllowed(context)
                WatchTaskOutbox.resumePending(context)
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                when (
                    intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR,
                    )
                ) {
                    BluetoothAdapter.STATE_ON -> {
                        BluetoothScheduleBridgeService.startIfAllowed(context)
                        WatchTaskOutbox.resumePending(context)
                    }

                    // A running service owns a dynamically registered receiver that closes its
                    // socket while remaining alive for the next STATE_ON transition. Do not stop
                    // the service here: this implicit broadcast cannot reliably cold-start it on
                    // modern Android after Bluetooth is enabled again.
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF,
                    -> Unit

                    else -> Unit
                }
            }

            else -> Log.d(TAG, "Ignoring action=${intent.action}")
        }
    }

    private companion object {
        const val TAG = "TplannerBtReceiver"
    }
}
