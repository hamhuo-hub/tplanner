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
// tPlanner 潮汐（Tide）与下一项（Next）表盘。
// 两款表盘共享同步、点击震动和唤醒手机逻辑，仅 Renderer 负责各自的视觉表达。
//
// 动画为事件驱动：入场 800ms、点按涟漪/光晕 600-800ms，动画期间通过
// invalidate() 请求连续帧；平时每 100ms 低频重绘。息屏（ambient）下只画
// 暗化的极简内容，无动画、无大面积亮色（防烧屏 + 省电）。
//
// 绘制逻辑分别位于 FaceTide 与 FaceNext。
// ═══════════════════════════════════════════════════════════════════════════

abstract class TPlannerFaceService : WatchFaceService() {
    private val vibrator: Vibrator by lazy { getSystemService(Vibrator::class.java) }

    protected abstract fun createRenderer(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): FaceBase

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        BluetoothScheduleBridgeService.startIfAllowed(applicationContext)
        PhoneWaker.resumePending(applicationContext)
        val renderer = createRenderer(surfaceHolder, watchState, currentUserStyleRepository)
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

class WatchFaceTideService : TPlannerFaceService() {
    override fun createRenderer(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): FaceBase = FaceTide(
        applicationContext,
        surfaceHolder,
        currentUserStyleRepository,
        watchState,
    )
}

class WatchFaceNextService : TPlannerFaceService() {
    override fun createRenderer(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): FaceBase = FaceNext(
        applicationContext,
        surfaceHolder,
        currentUserStyleRepository,
        watchState,
    )
}
