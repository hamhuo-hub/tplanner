package com.example.tplanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.util.UUID

// 共用逻辑：手表上任何一个入口（独立 App 的按钮、表盘上画的按钮）点击后都
// 直接走经典蓝牙 RFCOMM 连接手机端 BluetoothWakeService，完全绕开 Google
// Wearable Data Layer——已用日志确认，在国行三星设备上 GMS 的跨设备消息
// 中继不可用，所以改为应用自己管理的蓝牙连接，两端本就已通过系统蓝牙配对过。
//
// 表盘（TPlanner2024WatchFaceService）不是 Activity，无法弹出运行时权限
// 申请弹窗——蓝牙权限的申请仍只能在 MainActivity 里做一次；这里若权限缺失
// 就只记日志，不做任何弹窗引导。
object PhoneWaker {
    private const val TAG = "TplannerWear"
    val SERVICE_UUID: UUID = UUID.fromString("8b9f1e2a-7c4d-4a3b-9e5f-6d2c1a8b4f3e")

    fun wakeUpPhone(context: Context) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "wakeUpPhone: missing BLUETOOTH_CONNECT permission")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "wakeUpPhone: Bluetooth adapter unavailable or disabled")
            toast(context, context.getString(R.string.wake_phone_failed))
            return
        }
        val bonded = adapter.bondedDevices
        val phone = bonded?.firstOrNull { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
            ?: bonded?.firstOrNull()
        if (phone == null) {
            Log.e(TAG, "wakeUpPhone: no bonded phone device found")
            toast(context, context.getString(R.string.wake_phone_no_node))
            return
        }

        Thread {
            var socket: BluetoothSocket? = null
            try {
                Log.d(TAG, "wakeUpPhone: connecting to ${phone.name}")
                socket = phone.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                socket.outputStream.write(1)
                socket.outputStream.flush()
                Log.d(TAG, "wakeUpPhone: signal sent successfully")
            } catch (e: IOException) {
                Log.e(TAG, "wakeUpPhone: bluetooth connect failed", e)
                toast(context, context.getString(R.string.wake_phone_failed))
            } finally {
                try { socket?.close() } catch (_: IOException) {}
            }
        }.start()
    }

    private fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
