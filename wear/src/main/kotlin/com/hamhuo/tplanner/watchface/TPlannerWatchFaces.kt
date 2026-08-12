package com.hamhuo.tplanner

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
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
// 两款表盘共享同步和点击震动逻辑，仅 Renderer 负责各自的视觉表达。
//
// 动画为事件驱动：入场 800ms 期间通过 invalidate() 请求连续帧；平时按各表盘的
// interactiveDelayMs 低频重绘。息屏（ambient）下只画
// 暗化的极简内容，无动画、无大面积亮色（防烧屏 + 省电）。
//
// 绘制逻辑分别位于 FaceTide 与 FaceNext。
// ═══════════════════════════════════════════════════════════════════════════

abstract class TPlannerFaceService : WatchFaceService() {
    @Volatile private var activeRenderer: FaceBase? = null
    private val vibrator: Vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val openDashboardIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            OPEN_DASHBOARD_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

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
        val renderer = createRenderer(surfaceHolder, watchState, currentUserStyleRepository)
        activeRenderer = renderer
        return WatchFace(WatchFaceType.DIGITAL, renderer)
            .setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
                    if (tapType != TapType.UP || complicationSlot != null) return
                    if (renderer.isOnAppLaunchRegion(tapEvent.xPos, tapEvent.yPos)) {
                        vibrator.cancel()
                        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                        try {
                            openDashboardIntent.send()
                        } catch (error: PendingIntent.CanceledException) {
                            Log.e(TAG, "Unable to open the Wear dashboard", error)
                        }
                    }
                }
            })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        activeRenderer?.invalidate()
    }

    override fun onDestroy() {
        activeRenderer = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TplannerWatchFace"
        const val OPEN_DASHBOARD_REQUEST_CODE = 0x54504C
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
