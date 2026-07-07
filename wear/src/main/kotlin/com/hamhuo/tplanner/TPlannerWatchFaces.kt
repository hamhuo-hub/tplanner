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
// tPlanner 七款表盘：时环（Ring）· 星轨（Orbit）· 余烬（Ember）· 潮汐（Tide）· 脉动（Pulse）· 光弦（Lumina）· 月相（Moon）。
// 设计语言与桌面端一致：暗底 #0D0D0D、金 #C9A84C、米白衬线数字、青色事件点。
// 点击表盘下方的金色按钮/短划 → 震动并经典蓝牙唤醒手机（PhoneWaker）。
//
// 动画均为事件驱动：入场 800ms、点按涟漪/光晕 600-800ms，动画期间通过
// invalidate() 请求连续帧；平时按各自的 interactiveDrawModeUpdateDelayMillis
// 低频重绘（余烬/潮汐因呼吸光环用 100ms，其余 1000ms）。息屏（ambient）下只画
// 暗化的极简内容，无动画、无大面积亮色（防烧屏 + 省电）。
//
// 各表盘的具体绘制逻辑已拆分到 FaceRing / FaceOrbit / FaceEmber / FaceTide，
// 共享基类在 FaceBase，枚举与颜色常量在 FaceDesign，事件刻度在 WatchEventMarks。
// ═══════════════════════════════════════════════════════════════════════════

abstract class TPlannerFaceService : WatchFaceService() {

    protected abstract val design: FaceDesign

    private val vibrator: Vibrator by lazy { getSystemService(Vibrator::class.java) }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = when (design) {
            FaceDesign.RING   -> FaceRing(applicationContext, surfaceHolder, currentUserStyleRepository, watchState)
            FaceDesign.ORBIT  -> FaceOrbit(applicationContext, surfaceHolder, currentUserStyleRepository, watchState)
            FaceDesign.EMBER  -> FaceEmber(applicationContext, surfaceHolder, currentUserStyleRepository, watchState)
            FaceDesign.TIDE   -> FaceTide(applicationContext, surfaceHolder, currentUserStyleRepository, watchState)
            FaceDesign.PULSE  -> FacePulse(applicationContext, surfaceHolder, currentUserStyleRepository, watchState)
            FaceDesign.LUMINA -> FaceLumina(applicationContext, surfaceHolder, currentUserStyleRepository, watchState)
            FaceDesign.MOON   -> FaceMoon(applicationContext, surfaceHolder, currentUserStyleRepository, watchState)
        }
        return WatchFace(WatchFaceType.DIGITAL, renderer)
            .setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
                    if (tapType != TapType.UP) return
                    if (renderer.isOnWakeButton(tapEvent.xPos, tapEvent.yPos)) {
                        vibrator.cancel()
                        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                        renderer.startTapAnimation()
                        PhoneWaker.wakeUpPhone(applicationContext)
                    }
                }
            })
    }
}

class WatchFaceRingService  : TPlannerFaceService() { override val design = FaceDesign.RING }
class WatchFaceOrbitService : TPlannerFaceService() { override val design = FaceDesign.ORBIT }
class WatchFaceEmberService : TPlannerFaceService() { override val design = FaceDesign.EMBER }
class WatchFaceTideService  : TPlannerFaceService() { override val design = FaceDesign.TIDE }
class WatchFacePulseService  : TPlannerFaceService() { override val design = FaceDesign.PULSE }
class WatchFaceLuminaService : TPlannerFaceService() { override val design = FaceDesign.LUMINA }
class WatchFaceMoonService   : TPlannerFaceService() { override val design = FaceDesign.MOON }
