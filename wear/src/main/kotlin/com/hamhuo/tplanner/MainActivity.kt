package com.hamhuo.tplanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Minimal launcher placeholder.  The app is primarily accessed through
 * its Tide watch face; this Activity exists only so the APK has a
 * valid launcher entry point.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BluetoothScheduleBridgeService.hasBluetoothConnectPermission(this)
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_CONNECT,
            )
            return
        }
        BluetoothScheduleBridgeService.startIfAllowed(this)
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_CONNECT) return

        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            BluetoothScheduleBridgeService.startIfAllowed(this)
        }
        Toast.makeText(
            this,
            if (granted) R.string.bt_permission_ok else R.string.bt_permission_denied,
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    private companion object {
        const val REQUEST_BLUETOOTH_CONNECT = 1001
    }
}
