package com.hamhuo.tplanner

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import java.time.ZonedDateTime
import kotlin.math.cos
import kotlin.math.sin

// 星轨：事件星座 + 虚线连线 + 单针 24 时 + 小字时间
class FaceOrbit(
    context: android.content.Context,
    surfaceHolder: android.view.SurfaceHolder,
    currentUserStyleRepository: androidx.wear.watchface.style.CurrentUserStyleRepository,
    watchState: androidx.wear.watchface.WatchState,
) : FaceBase(context, surfaceHolder, currentUserStyleRepository, watchState, FaceDesign.ORBIT) {

    override fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        val boot = bootAlpha

        // 8 个方位刻度
        p.setStroke(TICK, s * 0.0053f)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0 - 90.0)
            canvas.drawLine(
                cx + s * 0.4526f * cos(a).toFloat(), cy + s * 0.4526f * sin(a).toFloat(),
                cx + s * 0.4842f * cos(a).toFloat(), cy + s * 0.4842f * sin(a).toFloat(), p,
            )
        }

        // 事件星座
        val orbitR = s * 0.4105f
        val ordered = marks.minutes.sorted()
        if (ordered.size >= 2) {
            p.setStroke(LINE, s * 0.004f)
            p.pathEffect = DashPathEffect(floatArrayOf(s * 0.0158f, s * 0.0158f), 0f)
            p.alpha = (255 * boot).toInt()
            for (i in 0 until ordered.size - 1) {
                val a1 = Math.toRadians(ordered[i] / 1440.0 * 360.0 - 90.0)
                val a2 = Math.toRadians(ordered[i + 1] / 1440.0 * 360.0 - 90.0)
                canvas.drawLine(
                    cx + orbitR * cos(a1).toFloat(), cy + orbitR * sin(a1).toFloat(),
                    cx + orbitR * cos(a2).toFloat(), cy + orbitR * sin(a2).toFloat(), p,
                )
            }
            p.pathEffect = null
        }
        ordered.forEachIndexed { i, m ->
            val a = Math.toRadians(m / 1440.0 * 360.0 - 90.0)
            val stagger = (((now - bootStart) - i * 100L).coerceIn(0, 300) / 300f)
            val isNext = m == marks.nextMinute
            p.setFill(if (isNext) GOLD else TEAL, stagger)
            canvas.drawCircle(
                cx + orbitR * cos(a).toFloat(), cy + orbitR * sin(a).toFloat(),
                s * (if (isNext) 0.021f else 0.0158f), p,
            )
        }

        // 单针 24 时
        val dayFrac = (t.hour * 3600 + t.minute * 60 + t.second) / 86400f
        val handA   = Math.toRadians(dayFrac * 360.0 * boot - 90.0)
        p.setStroke(GOLD, s * 0.0079f, Paint.Cap.ROUND)
        canvas.drawLine(cx, cy, cx + s * 0.3684f * cos(handA).toFloat(), cy + s * 0.3684f * sin(handA).toFloat(), p)
        p.setFill(GOLD); canvas.drawCircle(cx, cy, s * 0.0158f, p)

        p.setText(DIM, s * 0.05f)
        canvas.drawText(t.format(dateFmt), cx, cy - s * 0.1895f, p)
        p.setText(CREAM, s * 0.105f, serif)
        canvas.drawText(timeStr(t), cx, cy - s * 0.0947f, p)

        drawWakeButton(canvas, s, cx, cy + s * 0.326f)
    }

    override fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        p.setStroke(AMB_TRACK, s * 0.0053f)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0 - 90.0)
            canvas.drawLine(
                cx + s * 0.4526f * cos(a).toFloat(), cy + s * 0.4526f * sin(a).toFloat(),
                cx + s * 0.4842f * cos(a).toFloat(), cy + s * 0.4842f * sin(a).toFloat(), p,
            )
        }
        val dayFrac = (t.hour * 60 + t.minute) / 1440f
        val handA   = Math.toRadians(dayFrac * 360.0 - 90.0)
        p.setStroke(AMB_GOLD, s * 0.0079f, Paint.Cap.ROUND)
        canvas.drawLine(cx, cy, cx + s * 0.3684f * cos(handA).toFloat(), cy + s * 0.3684f * sin(handA).toFloat(), p)
        p.setText(AMB_TEXT, s * 0.105f, serif)
        canvas.drawText(timeStr(t), cx, cy - s * 0.0947f, p)
    }
}
