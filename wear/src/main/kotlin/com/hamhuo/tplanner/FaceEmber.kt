package com.hamhuo.tplanner

import android.graphics.Canvas
import android.graphics.Paint
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.sin

// 余烬：纯排印时分堆叠 + 呼吸光环 + 底部短划
class FaceEmber(
    context: android.content.Context,
    surfaceHolder: android.view.SurfaceHolder,
    currentUserStyleRepository: androidx.wear.watchface.style.CurrentUserStyleRepository,
    watchState: androidx.wear.watchface.WatchState,
) : FaceBase(context, surfaceHolder, currentUserStyleRepository, watchState, FaceDesign.EMBER) {

    override fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        // 呼吸：4 秒正弦周期；点按后光晕爆发并在 600ms 内衰减回落
        val breath = 0.7f + 0.6f * (0.5f + 0.5f * sin(2.0 * PI * (now % 4000L) / 4000.0).toFloat())
        val flare  = 1f + 2.5f * (1f - (tapElapsed.coerceIn(0, TAP_MS) / TAP_MS.toFloat()))
        val glow   = breath * (if (tapElapsed < TAP_MS) flare else 1f)

        val hcy = cy - s * 0.0105f
        p.setStroke(GOLD, s * 0.0421f); p.alpha = (255 * 0.08f * glow).toInt().coerceAtMost(255)
        canvas.drawCircle(cx, hcy, s * 0.2947f, p)
        p.setStroke(GOLD, s * 0.0263f); p.alpha = (255 * 0.12f * glow).toInt().coerceAtMost(255)
        canvas.drawCircle(cx, hcy, s * 0.2526f, p)

        p.setText(CREAM, s * 0.221f, serif)
        canvas.drawText("%02d".format(t.hour), cx, cy - s * 0.0632f, p)
        p.setStroke(GOLD, s * 0.0053f)
        canvas.drawLine(cx - s * 0.1158f, cy - s * 0.0053f, cx + s * 0.1158f, cy - s * 0.0053f, p)
        p.setText(GOLD, s * 0.221f, serif)
        canvas.drawText("%02d".format(t.minute), cx, cy + s * 0.2f, p)
        p.setText(DIM, s * 0.0526f)
        canvas.drawText(t.format(dateFmt), cx, cy + s * 0.3053f, p)

        // ── 日程事件：屏幕底部一行蓝色半透明小点 ──────────────────────────
        val dotY = cy + s * 0.38f
        val dotLeft = cx - s * 0.32f
        val dotWidth = s * 0.64f
        for (m in marks.minutes) {
            val x = dotLeft + (m / 1440f) * dotWidth
            p.setFill(EVENT_DOT)
            canvas.drawCircle(x, dotY, s * 0.012f, p)
        }

    }

    override fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        p.setText(AMB_TEXT, s * 0.221f, serif)
        canvas.drawText("%02d".format(t.hour), cx, cy - s * 0.0632f, p)
        p.setStroke(AMB_GOLD, s * 0.0053f)
        canvas.drawLine(cx - s * 0.1158f, cy - s * 0.0053f, cx + s * 0.1158f, cy - s * 0.0053f, p)
        p.setText(AMB_TEXT, s * 0.221f, serif)
        canvas.drawText("%02d".format(t.minute), cx, cy + s * 0.2f, p)
    }
}
