package com.example.tplanner

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.Wearable

class MainActivity : ComponentActivity() {

    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 三星 Galaxy Watch（Wear OS powered by Samsung）出厂自带 Google Play
        // Services，但版本/可用性仍可能受设备策略影响——这里打日志而不是静默
        // 失败，方便确认 Wearable Data Layer 在这台设备上是否真的可用。
        val gmsCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        Log.d(TAG, "onCreate: GooglePlayServicesAvailable code=$gmsCode (0=SUCCESS)")

        vibrator = getSystemService(Vibrator::class.java)

        val vibrateButton: Button = findViewById(R.id.vibrate_button)
        vibrateButton.setOnClickListener {
            Log.d(TAG, "vibrateButton clicked")
            performVibration()
            wakeUpPhone()
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

    // 通过 Wearable Data Layer 给所有已配对、已连接的手机节点发消息，
    // 手机端 WearOpenListenerService 收到后会唤起 tplanner。
    private fun wakeUpPhone() {
        Log.d(TAG, "wakeUpPhone: querying connected nodes")
        val nodeClient = Wearable.getNodeClient(this)
        val messageClient = Wearable.getMessageClient(this)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                Log.d(TAG, "connectedNodes success: count=${nodes.size} ${nodes.map { "${it.displayName}/${it.id}/nearby=${it.isNearby}" }}")
                if (nodes.isEmpty()) {
                    Toast.makeText(this, getString(R.string.wake_phone_no_node), Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, OPEN_APP_PATH, ByteArray(0))
                        .addOnSuccessListener {
                            Log.d(TAG, "sendMessage success to node=${node.id}")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "sendMessage failed to node=${node.id}", e)
                            Toast.makeText(this, getString(R.string.wake_phone_failed), Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "connectedNodes failed", e)
                Toast.makeText(this, getString(R.string.wake_phone_failed), Toast.LENGTH_SHORT).show()
            }
    }

    companion object {
        const val OPEN_APP_PATH = "/tplanner/open"
        private const val TAG = "TplannerWear"
    }
}
