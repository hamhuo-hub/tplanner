package com.hamhuo.tplanner

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Launcher screen for the watch app. Watch faces remain separate entry points. */
class MainActivity : ComponentActivity() {
    private lateinit var dashboard: NextDashboardView
    private var permissionRequestAttempted = false
    private var selectedFilter = WatchListFilter.INBOX

    private val marksPreferences: SharedPreferences by lazy {
        getSharedPreferences(WATCH_MARKS_PREFS, MODE_PRIVATE)
    }
    private val marksListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == WATCH_MARKS_KEY && ::dashboard.isInitialized) {
            dashboard.post { dashboard.refreshContent(showFeedback = false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedFilter = WatchListFilter.fromKey(savedInstanceState?.getString(KEY_SELECTED_FILTER))
        permissionRequestAttempted = getPreferences(MODE_PRIVATE)
            .getBoolean(KEY_PERMISSION_REQUESTED, false)
        hideSystemUi()

        dashboard = NextDashboardView(this).apply {
            setSelectedFilter(selectedFilter)
            setListSelectionAction {
                openListSelection()
            }
            setNewTaskAction {
                openTaskCreation()
            }
            setPermissionAction {
                if (needsBluetoothPermission()) {
                    showPermissionRequired()
                    handlePermissionAction()
                } else {
                    BluetoothScheduleBridgeService.startIfAllowed(this@MainActivity)
                    clearPermissionRequired()
                }
            }
        }
        setContentView(dashboard)

        if (needsBluetoothPermission()) {
            dashboard.showPermissionRequired()
            if (!permissionRequestAttempted) requestBluetoothPermission()
        } else {
            BluetoothScheduleBridgeService.startIfAllowed(this)
        }
        WatchTaskOutbox.resumePending(this)
    }

    override fun onStart() {
        super.onStart()
        marksPreferences.registerOnSharedPreferenceChangeListener(marksListener)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        dashboard.start()
        if (!needsBluetoothPermission()) {
            dashboard.clearPermissionRequired()
            BluetoothScheduleBridgeService.startIfAllowed(this)
        } else {
            dashboard.showPermissionRequired()
        }
        WatchTaskOutbox.resumePending(this)
    }

    override fun onPause() {
        dashboard.stop()
        super.onPause()
    }

    override fun onStop() {
        marksPreferences.unregisterOnSharedPreferenceChangeListener(marksListener)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SELECTED_FILTER, selectedFilter.key)
        super.onSaveInstanceState(outState)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_LIST_SELECTION -> {
                selectedFilter = WatchListFilter.fromKey(
                    data?.getStringExtra(ListSelectionActivity.EXTRA_SELECTED_FILTER),
                )
                dashboard.setSelectedFilter(selectedFilter)
            }

            REQUEST_TASK_CREATION -> {
                dashboard.refreshContent(showFeedback = false)
                dashboard.announceTaskQueued()
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_CONNECT) return

        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            dashboard.clearPermissionRequired()
            BluetoothScheduleBridgeService.startIfAllowed(this)
            dashboard.refreshContent(showFeedback = true)
        } else {
            dashboard.showPermissionRequired()
        }
    }

    private fun needsBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BluetoothScheduleBridgeService.hasBluetoothConnectPermission(this)

    @Suppress("DEPRECATION")
    private fun openListSelection() {
        startActivityForResult(
            ListSelectionActivity.createIntent(this, selectedFilter),
            REQUEST_LIST_SELECTION,
        )
    }

    @Suppress("DEPRECATION")
    private fun openTaskCreation() {
        startActivityForResult(
            CreateTitleActivity.createIntent(this),
            REQUEST_TASK_CREATION,
        )
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        permissionRequestAttempted = true
        getPreferences(MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PERMISSION_REQUESTED, true)
            .apply()
        requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            REQUEST_BLUETOOTH_CONNECT,
        )
    }

    private fun handlePermissionAction() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (
            !permissionRequestAttempted ||
            shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            requestBluetoothPermission()
        } else {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val REQUEST_BLUETOOTH_CONNECT = 1001
        const val REQUEST_LIST_SELECTION = 1002
        const val REQUEST_TASK_CREATION = 1003
        const val KEY_PERMISSION_REQUESTED = "bluetooth_permission_requested"
        const val KEY_SELECTED_FILTER = "selected_filter"
    }
}
