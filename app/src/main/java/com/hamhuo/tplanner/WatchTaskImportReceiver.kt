package com.hamhuo.tplanner

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restores the phone-side RFCOMM listener after boot, update, or Bluetooth power-on. */
class WatchTaskImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> WatchTaskImportService.startIfAllowed(context)

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                if (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                    BluetoothAdapter.STATE_ON
                ) {
                    WatchTaskImportService.startIfAllowed(context)
                }
            }

            else -> Log.d(TAG, "Ignoring action=${intent.action}")
        }
    }

    private companion object {
        const val TAG = "TplannerWatchImportRcv"
    }
}
