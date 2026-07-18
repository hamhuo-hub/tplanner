package com.hamhuo.tplanner

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

// ── 极致审美表盘：LUMINA ─────────────────────────────────────────────────
// 极简光韵 · 呼吸光球 · 柔光进度环 · 星芒事件 · 丝绸般丝滑动画
// 灵感：极光、月华、水晶折射。追求「静谧·高级·永恒」的气质。

class FaceLumina(
    context: android.content.Context,
    surfaceHolder: android.view.SurfaceHolder,
    currentUserStyleRepository: androidx.wear.watchface.style.CurrentUserStyleRepository,
    watchState: androidx.wear.watchface.WatchState,
) : FaceBase(context, surfaceHolder, currentUserStyleRepository, watchState, FaceDesign.LUMINA) {

    // 光晕几何
    private fun glowGeo(s: Float, cx: Float, cy: Float) = object {
        val coreR = s * 0.168f
        val midR  = s * 0.247f
        val outerR = s * 0.379f
        val cx = cx
        val cy = cy - s * 0.021f
    }

    override fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        val boot = bootAlpha
        val g = glowGeo(s, cx, cy)

        val dayFrac = (t.hour * 3600f + t.minute * 60f + t.second) / 86400f
        val breath = 0.92f + 0.08f * sin(2.0 * PI * (now % 5200L) / 5200.0).toFloat()
        val flare = if (tapElapsed < TAP_MS) {
            1f + 3.2f * (1f - tapElapsed.toFloat() / TAP_MS)
        } else 1f
        val glow = breath * flare

        // ── 极致柔光背景环 ───────────────────────────────────────────────
        // 外层极淡光晕
        p.setStroke(0xFF1A2A3A.toInt(), s * 0.068f)
        p.alpha = (255 * 0.09f * boot * glow).toInt()
        canvas.drawCircle(g.cx, g.cy, g.outerR * 1.08f, p)

        // 中层光环（带轻微旋转感）
        val rot = (now % 48000L) / 48000f * 12f
        p.setStroke(0xFF4A7A9E.toInt(), s * 0.021f)
        p.alpha = (255 * 0.18f * boot * glow).toInt()
        canvas.drawCircle(g.cx, g.cy, g.midR * (0.98f + 0.04f * sin(rot * PI).toFloat()), p)

        // ── 核心光球 + 折射高光 ───────────────────────────────────────────
        // 最外柔光
        p.setFill(0xFF8AB4FF.toInt())
        p.alpha = (255 * 0.11f * glow * boot).toInt()
        canvas.drawCircle(g.cx, g.cy, g.coreR * 2.35f, p)

        // 中光
        p.alpha = (255 * 0.28f * glow * boot).toInt()
        canvas.drawCircle(g.cx, g.cy, g.coreR * 1.62f, p)

        // 主光球（金白渐变）
        val grad = LinearGradient(
            g.cx - g.coreR * 0.6f, g.cy - g.coreR * 0.8f,
            g.cx + g.coreR * 0.4f, g.cy + g.coreR * 0.6f,
            intArrayOf(0xFFFEF8E8.toInt(), GOLD, 0xFFC9A84C.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        p.shader = grad
        p.setFill(GOLD, boot)
        canvas.drawCircle(g.cx, g.cy, g.coreR, p)
        p.shader = null

        // 晶莹高光点
        p.setFill(0xFFFFFFFF.toInt(), boot * 0.75f * glow)
        canvas.drawCircle(g.cx - g.coreR * 0.38f, g.cy - g.coreR * 0.41f, g.coreR * 0.19f, p)

        // ── 优雅时针（单针24小时，极细光感） ───────────────────────────────
        val handAngle = Math.toRadians(dayFrac * 360.0 - 90.0)
        val handLen = g.midR * 0.81f

        // 光晕尾迹
        p.setStroke(0xFF8AB4FF.toInt(), s * 0.0095f)
        p.alpha = (255 * 0.22f * glow).toInt()
        canvas.drawLine(
            g.cx, g.cy,
            g.cx + handLen * 0.92f * cos(handAngle).toFloat(),
            g.cy + handLen * 0.92f * sin(handAngle).toFloat(),
            p
        )

        // 主指针
        p.setStroke(GOLD, s * 0.0063f, Paint.Cap.ROUND)
        p.alpha = (255 * boot).toInt()
        canvas.drawLine(
            g.cx, g.cy,
            g.cx + handLen * cos(handAngle).toFloat(),
            g.cy + handLen * sin(handAngle).toFloat(),
            p
        )

        // 指针尖端晶点
        val tipX = g.cx + handLen * cos(handAngle).toFloat()
        val tipY = g.cy + handLen * sin(handAngle).toFloat()
        p.setFill(0xFFFEF8E8.toInt())
        canvas.drawCircle(tipX, tipY, s * 0.0095f, p)

        // ── 事件星芒 ──────────────────────────────────────────────────────
        val eventR = g.outerR * 0.89f
        for (m in marks.minutes) {
            val frac = m / 1440f
            val angle = Math.toRadians(frac * 360.0 - 90.0)
            val ex = g.cx + eventR * cos(angle).toFloat()
            val ey = g.cy + eventR * sin(angle).toFloat()

            val isNext = m == marks.nextMinute
            val alpha = if (isNext) 0.95f else 0.55f
            val size = if (isNext) 0.021f else 0.0137f

            // 星芒光晕
            if (isNext) {
                p.setStroke(GOLD, s * 0.0053f)
                p.alpha = (255 * 0.4f * glow * boot).toInt()
                canvas.drawCircle(ex, ey, s * 0.037f, p)
            }

            p.setFill(if (isNext) GOLD else EVENT_DOT, boot * alpha)
            canvas.drawCircle(ex, ey, s * size, p)
        }

        // ── 时间文字（极致衬线 + 微光） ────────────────────────────────────
        p.setText(CREAM, s * 0.168f, serif)
        p.alpha = (255 * boot).toInt()
        canvas.drawText(timeStr(t), cx, cy + s * 0.247f, p)

        p.setText(DIM, s * 0.047f)
        canvas.drawText(t.format(dateFmt), cx, cy + s * 0.319f, p)

        // 次要事件提示
        marks.nextMinute?.let { next ->
            marks.nextTitle?.let { title ->
                p.setText(GOLD, s * 0.042f)
                p.alpha = (255 * 0.85f * boot).toInt()
                val timeText = "%02d:%02d".format(next / 60, next % 60)
                canvas.drawText("$timeText $title", cx, cy + s * 0.379f, p)
            }
        }

    }

    override fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        val g = glowGeo(s, cx, cy)
        val dayFrac = (t.hour * 3600f + t.minute * 60f + t.second) / 86400f

        // 极简暗态
        p.setStroke(AMB_TRACK, s * 0.0158f)
        canvas.drawCircle(g.cx, g.cy, g.midR, p)

        val handAngle = Math.toRadians(dayFrac * 360.0 - 90.0)
        p.setStroke(AMB_GOLD, s * 0.0058f, Paint.Cap.ROUND)
        canvas.drawLine(
            g.cx, g.cy,
            g.cx + g.midR * 0.78f * cos(handAngle).toFloat(),
            g.cy + g.midR * 0.78f * sin(handAngle).toFloat(),
            p
        )

        p.setText(AMB_TEXT, s * 0.168f, serif)
        canvas.drawText(timeStr(t), cx, cy + s * 0.247f, p)
    }
}
