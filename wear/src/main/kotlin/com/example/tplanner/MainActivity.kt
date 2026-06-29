package com.example.tplanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var vibrator: Vibrator

    private val requestBtPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> Log.d(TAG, "BLUETOOTH_CONNECT permission granted=$granted") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vibrator = getSystemService(Vibrator::class.java)
        ensureBluetoothPermission()

        val vibrateButton: Button = findViewById(R.id.vibrate_button)
        vibrateButton.setOnClickListener {
            Log.d(TAG, "vibrateButton clicked")
            performVibration()
            wakeUpPhone()
        }
    }

    private fun ensureBluetoothPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBtPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun performVibration() {
        val effect = VibrationEffect.createOneShot(
            3000,  // 3秒 = 3000ms
            VibrationEffect.DEFAULT_AMPLITUDE
        )
        vibrator.cancel()  // 取消之前的震动
        vibrator.vibrate(effect)
    }

    // 直接走经典蓝牙 RFCOMM 连接手机端 BluetoothWakeService，完全绕开
    // Google Wearable Data Layer——已用日志确认，在这台国行三星设备上 GMS
    // 的跨设备消息中继不可用（MessageClient/DataClient 本端都一直报告
    // "成功"，但手机端 GMS 相关组件完全没有任何反应），所以改为应用自己
    // 管理的蓝牙连接，两端本就已通过系统蓝牙配对过。
    private fun wakeUpPhone() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "wakeUpPhone: missing BLUETOOTH_CONNECT permission")
            ensureBluetoothPermission()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "wakeUpPhone: Bluetooth adapter unavailable or disabled")
            Toast.makeText(this, getString(R.string.wake_phone_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val bonded = adapter.bondedDevices
        val phone = bonded?.firstOrNull { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
            ?: bonded?.firstOrNull()
        if (phone == null) {
            Log.e(TAG, "wakeUpPhone: no bonded phone device found")
            Toast.makeText(this, getString(R.string.wake_phone_no_node), Toast.LENGTH_SHORT).show()
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
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.wake_phone_failed), Toast.LENGTH_SHORT).show()
                }
            } finally {
                try { socket?.close() } catch (_: IOException) {}
            }
        }.start()
    }

    companion object {
        private const val TAG = "TplannerWear"
        val SERVICE_UUID: UUID = UUID.fromString("8b9f1e2a-7c4d-4a3b-9e5f-6d2c1a8b4f3e")
    }
}
