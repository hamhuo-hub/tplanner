package com.hamhuo.tplanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * 手表端唯一的 Activity 入口，职责只有一个：申请 BLUETOOTH_CONNECT 运行时权限。
 *
 * 表盘服务（TPlannerWatchFaces.kt）不是 Activity，无法弹权限框——Wear OS 4/5
 * (API 33+) 上 BLUETOOTH_CONNECT 是运行时权限，全新安装后若从未授予，
 * PhoneWaker 会在第一步就静默失败（点按只有震动、手机毫无反应）。
 * 所以装好表盘后需要打开一次本 App 完成授权。
 *
 * 使用方式：长按当前表盘 → 选择「tPlanner 时环 / 星轨 / 余烬」→
 * 打开一次 TPlanner 应用授予蓝牙权限 → 点按表盘底部金钮唤醒手机。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        } else {
            Toast.makeText(this, getString(R.string.bt_permission_ok), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                getString(if (granted) R.string.bt_permission_ok else R.string.bt_permission_denied),
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }

    companion object { private const val REQ_BT = 1 }
}
