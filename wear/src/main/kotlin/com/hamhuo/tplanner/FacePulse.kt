package com.hamhuo.tplanner

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.sin

// 脉动/星弦：纵向时空弦轴。左右对称脉动弧分别代表时/分进度，事件如彗星般错落挂在弦上。
class FacePulse(
    context: android.content.Context,
    surfaceHolder: android.view.SurfaceHolder,
    currentUserStyleRepository: androidx.wear.watchface.style.CurrentUserStyleRepository,
    watchState: androidx.wear.watchface.WatchState,
) : FaceBase(context, surfaceHolder, currentUserStyleRepository, watchState, FaceDesign.PULSE) {

    override fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        val boot = bootAlpha

        // ── 1. 中央时空星弦（纵向虚线轴） ──────────────────────────────────────
        val axisTop = cy - s * 0.42f
        val axisBot = cy + s * 0.28f
        p.setStroke(TRACK, s * 0.004f)
        p.pathEffect = DashPathEffect(floatArrayOf(s * 0.01f, s * 0.01f), 0f)
        p.alpha = (255 * 0.8f * boot).toInt()
        canvas.drawLine(cx, axisTop, cx, axisBot, p)
        p.pathEffect = null

        // ── 2. 左右脉动弧（左小时，右分钟） ────────────────────────────────────
        val hourFrac = (t.hour % 12 + t.minute / 60f) / 12f
        val minFrac  = (t.minute + t.second / 60f) / 60f

        val radiusX = s * 0.22f
        val radiusY = s * 0.16f
        val arcRect = RectF(cx - radiusX, cy - radiusY - s * 0.08f, cx + radiusX, cy + radiusY - s * 0.08f)

        // 左侧：小时脉动弧 (180° 逆时针向上下蔓延)
        p.setStroke(DIM, s * 0.008f, Paint.Cap.ROUND)
        p.alpha = (255 * 0.4f * boot).toInt()
        canvas.drawArc(arcRect, 90f, 180f, false, p) // 轨迹暗底
        p.setStroke(CREAM, s * 0.01f, Paint.Cap.ROUND)
        p.alpha = (255 * boot).toInt()
        canvas.drawArc(arcRect, 270f, -180f * hourFrac * boot, false, p)

        // 右侧：分钟脉动弧 (0° 顺时针向上下蔓延)
        p.setStroke(LINE, s * 0.008f, Paint.Cap.ROUND)
        p.alpha = (255 * 0.6f * boot).toInt()
        canvas.drawArc(arcRect, 90f, -180f, false, p) // 轨迹暗底
        p.setStroke(GOLD, s * 0.01f, Paint.Cap.ROUND)
        p.alpha = (255 * boot).toInt()
        canvas.drawArc(arcRect, 270f, 180f * minFrac * boot, false, p)


        // ── 3. 事件星弦刻度（纵向分布） ───────────────────────────────────────
        val dayFrac = (t.hour * 60 + t.minute) / 1440f

        for (m in marks.minutes) {
            val mFrac = m / 1440f
            // 映射到弦轴上的 Y 坐标
            val ey = axisTop + (axisBot - axisTop) * mFrac
            val isNext = m == marks.nextMinute
            val isPast = mFrac < dayFrac

            if (isNext) {
                // 临近事件：展开淡金色彗星引力环
                val breath = 0.6f + 0.4f * sin(2.0 * PI * (now % 3000L) / 3000.0).toFloat()
                p.setStroke(GOLD, s * 0.003f)
                p.alpha = (255 * 0.3f * breath * boot).toInt()
                canvas.drawCircle(cx, ey, s * 0.04f, p)

                p.setFill(GOLD, boot)
                val star = Path().apply {
                    moveTo(cx, ey - s * 0.015f)
                    lineTo(cx + s * 0.01f, ey)
                    lineTo(cx, ey + s * 0.015f)
                    lineTo(cx - s * 0.01f, ey)
                    close()
                }
                canvas.drawPath(star, p)
            } else {
                // 普通事件：青色横向纤细切线
                val lineWidth = s * 0.025f
                val alpha = if (isPast) 0.25f else 0.7f
                p.setStroke(TEAL, s * 0.004f, Paint.Cap.ROUND)
                p.alpha = (255 * alpha * boot).toInt()
                canvas.drawLine(cx - lineWidth, ey, cx + lineWidth, ey, p)
            }
        }

        // ── 4. 核心排印（优雅的叠字艺术） ─────────────────────────────────────
        val textY = cy - s * 0.06f
        p.setText(CREAM, s * 0.13f, serif)
        canvas.drawText("%02d".format(t.hour), cx, textY, p)

        p.setText(GOLD,  s * 0.13f, serif)
        canvas.drawText("%02d".format(t.minute), cx, textY + s * 0.12f, p)

        // 日期
        p.setText(DIM, s * 0.045f)
        canvas.drawText(t.format(dateFmt), cx, textY + s * 0.22f, p)

        // ── 5. 底部热区锚点 ──────────────────────────────────────────────────
        // 呼吸及点按特效
        val pearlY = cy + s * 0.395f
        val pearlR = s * 0.025f
        if (tapElapsed in 0 until TAP_MS) {
            val q = tapElapsed / TAP_MS.toFloat()
            p.setStroke(GOLD, s * 0.004f)
            p.alpha = (255 * 0.5f * (1f - q)).toInt()
            canvas.drawCircle(cx, pearlY, pearlR * 3.5f * q + pearlR, p)
        }
        p.setStroke(GOLD, s * 0.003f)
        p.alpha = (255 * 0.6f).toInt()
        canvas.drawCircle(cx, pearlY, pearlR * 1.6f, p)
        p.setFill(GOLD, boot)
        canvas.drawCircle(cx, pearlY, pearlR, p)
    }

    override fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        // 微光模式：极简线条，防止烧屏
        val axisTop = cy - s * 0.42f
        val axisBot = cy + s * 0.28f

        // 极暗虚线轴
        p.setStroke(AMB_TRACK, s * 0.003f)
        p.pathEffect = DashPathEffect(floatArrayOf(s * 0.01f, s * 0.01f), 0f)
        canvas.drawLine(cx, axisTop, cx, axisBot, p)
        p.pathEffect = null

        // 简化的左右时分环
        val hourFrac = (t.hour % 12) / 12f
        val minFrac  = t.minute / 60f
        val radiusX = s * 0.22f; val radiusY = s * 0.16f
        val arcRect = RectF(cx - radiusX, cy - radiusY - s * 0.08f, cx + radiusX, cy + radiusY - s * 0.08f)

        p.setStroke(AMB_GOLD, s * 0.006f, Paint.Cap.ROUND)
        canvas.drawArc(arcRect, 270f, -180f * hourFrac, false, p)
        canvas.drawArc(arcRect, 270f, 180f * minFrac, false, p)

        // 叠字时间
        val textY = cy - s * 0.06f
        p.setText(AMB_TEXT, s * 0.13f, serif)
        canvas.drawText("%02d".format(t.hour), cx, textY, p)
        canvas.drawText("%02d".format(t.minute), cx, textY + s * 0.12f, p)
    }
}
