package com.example.tplanner

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.sqrt

// 基于 androidx.wear.watchface 的现代表盘：在 Wear OS 3+/三星 Galaxy Watch 的
// 表盘选择器中可正常出现。绘制黑底时间 + 中央红色按钮，点击按钮震动 3 秒。
class TPlanner2024WatchFaceService : WatchFaceService() {

    private val vibrator: Vibrator by lazy { getSystemService(Vibrator::class.java) }

    private fun vibrate3s() {
        val effect = VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.cancel()
        vibrator.vibrate(effect)
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = TPlannerRenderer(surfaceHolder, currentUserStyleRepository, watchState)
        return WatchFace(WatchFaceType.DIGITAL, renderer)
            .setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(
                    tapType: Int,
                    tapEvent: TapEvent,
                    complicationSlot: ComplicationSlot?
                ) {
                    if (tapType == TapType.UP && renderer.isOnButton(tapEvent.xPos, tapEvent.yPos)) {
                        vibrate3s()
                    }
                }
            })
    }

    private class TPlannerSharedAssets : Renderer.SharedAssets {
        override fun onDestroy() {}
    }

    private inner class TPlannerRenderer(
        surfaceHolder: SurfaceHolder,
        currentUserStyleRepository: CurrentUserStyleRepository,
        watchState: WatchState
    ) : Renderer.CanvasRenderer2<TPlannerSharedAssets>(
        surfaceHolder,
        currentUserStyleRepository,
        watchState,
        CanvasType.HARDWARE,
        1000L,   // 交互模式每秒重绘一次以刷新秒数
        false
    ) {
        private val backgroundPaint = Paint().apply { color = Color.BLACK }
        private val timePaint = Paint().apply {
            color = Color.WHITE; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }
        private val buttonPaint = Paint().apply {
            color = Color.parseColor("#FF6B6B"); isAntiAlias = true
        }
        private val buttonTextPaint = Paint().apply {
            color = Color.WHITE; isAntiAlias = true; textAlign = Paint.Align.CENTER
        }

        // 记录最近一次绘制的表盘尺寸，供点击命中测试复用（坐标系与 render 一致，原点 0,0）。
        @Volatile private var faceW = 0
        @Volatile private var faceH = 0

        fun isOnButton(x: Int, y: Int): Boolean {
            if (faceW == 0) return false
            val cx = faceW / 2f
            val cy = faceH / 2f + faceH * 0.18f
            val r = faceW * 0.13f
            val dx = x - cx
            val dy = y - cy
            return sqrt((dx * dx + dy * dy).toDouble()) <= r
        }

        override suspend fun createSharedAssets(): TPlannerSharedAssets = TPlannerSharedAssets()

        override fun render(
            canvas: Canvas,
            bounds: Rect,
            zonedDateTime: ZonedDateTime,
            sharedAssets: TPlannerSharedAssets
        ) {
            faceW = bounds.width()
            faceH = bounds.height()

            canvas.drawRect(bounds, backgroundPaint)

            val centerX = bounds.exactCenterX()
            val centerY = bounds.exactCenterY()

            // 时间 HH:mm:ss（环境/低电模式下框架自动降低刷新频率）
            timePaint.textSize = bounds.width() * 0.16f
            val timeStr = String.format(
                Locale.US, "%02d:%02d:%02d",
                zonedDateTime.hour, zonedDateTime.minute, zonedDateTime.second
            )
            canvas.drawText(timeStr, centerX, centerY - bounds.height() * 0.10f, timePaint)

            // 中央震动按钮
            val r = bounds.width() * 0.13f
            val by = centerY + bounds.height() * 0.18f
            canvas.drawCircle(centerX, by, r, buttonPaint)
            buttonTextPaint.textSize = bounds.width() * 0.07f
            canvas.drawText("震动", centerX, by + buttonTextPaint.textSize / 3f, buttonTextPaint)
        }

        override fun renderHighlightLayer(
            canvas: Canvas,
            bounds: Rect,
            zonedDateTime: ZonedDateTime,
            sharedAssets: TPlannerSharedAssets
        ) {
            // 无复杂功能高亮层
        }
    }
}
