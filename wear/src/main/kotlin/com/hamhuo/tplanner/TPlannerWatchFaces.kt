package com.hamhuo.tplanner

import android.os.VibrationEffect
import android.os.Vibrator
import android.view.SurfaceHolder
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository

// ═══════════════════════════════════════════════════════════════════════════
// tPlanner 潮汐（Tide）表盘。
// 设计语言与桌面端一致：暗底 #0D0D0D、金 #C9A84C、米白衬线数字、青色事件点。
// 点击浪尖金球 → 震动并经典蓝牙唤醒手机（PhoneWaker）。
//
// 动画为事件驱动：入场 800ms、点按涟漪/光晕 600-800ms，动画期间通过
// invalidate() 请求连续帧；平时每 100ms 低频重绘。息屏（ambient）下只画
// 暗化的极简内容，无动画、无大面积亮色（防烧屏 + 省电）。
//
// 绘制逻辑位于 FaceTide；调起时间由 WakeInvocationMarks 在手表本地持久化。
// ═══════════════════════════════════════════════════════════════════════════

class WatchFaceTideService : WatchFaceService() {
    private val vibrator: Vibrator by lazy { getSystemService(Vibrator::class.java) }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        PhoneWaker.resumePending(applicationContext)
        val renderer = FaceTide(applicationContext, surfaceHolder, currentUserStyleRepository, watchState)
        return WatchFace(WatchFaceType.DIGITAL, renderer)
            .setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
                    if (tapType != TapType.UP) return
                    if (renderer.isOnWakeButton(tapEvent.xPos, tapEvent.yPos)) {
                        vibrator.cancel()
                        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                        renderer.handleWakeTap()
                        PhoneWaker.wakeUpPhone(applicationContext)
                    }
                }
            })
    }
}
