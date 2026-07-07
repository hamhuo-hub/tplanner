package com.hamhuo.tplanner

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// 月相：中央月面盈亏 + 暗金轨道 + 星尘事件 + 金线地平 + 底部珍珠
// 设计定位：时间不是流逝，而是月亮逐渐显露——静止的观察者。
// 减少金色用量，追求爱彼夜光盘 × 日式庭院 × 天文台的静谧高级感。
class FaceMoon(
    context: android.content.Context,
    surfaceHolder: android.view.SurfaceHolder,
    currentUserStyleRepository: androidx.wear.watchface.style.CurrentUserStyleRepository,
    watchState: androidx.wear.watchface.WatchState,
) : FaceBase(context, surfaceHolder, currentUserStyleRepository, watchState, FaceDesign.MOON) {

    override fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        val boot = bootAlpha

        // ── 月相：近似朔望月 29.53 天 ──────────────────────────────────────
        val moonPhase = (t.dayOfYear.toFloat() % 29.53f) / 29.53f

        val moonCX = cx
        val moonCY = cy - s * 0.05f
        val moonR  = s * 0.18f

        // 呼吸光晕：5 秒正弦周期，比 Ember/Tide 更慢更克制
        val breath = 0.6f + 0.25f * (0.5f + 0.5f * sin(2.0 * PI * (now % 5000L) / 5000.0).toFloat())
        val flare  = 1f + 1.8f * (1f - (tapElapsed.coerceIn(0, TAP_MS) / TAP_MS.toFloat()))
        val glow   = breath * (if (tapElapsed < TAP_MS) flare else 1f)

        // ── 暗金外轨 ─────────────────────────────────────────────────────
        val orbitR = moonR * 1.40f
        p.setStroke(GOLD, s * 0.0021f)
        p.alpha = (255 * 0.28f * boot).toInt()
        canvas.drawCircle(moonCX, moonCY, orbitR, p)

        // 轨道的细微刻度：8 个方位极短标线（天文台望远镜刻度感）
        p.setStroke(GOLD, s * 0.0016f)
        p.alpha = (255 * 0.15f * boot).toInt()
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0 - 90.0)
            canvas.drawLine(
                moonCX + (orbitR - s * 0.008f) * cos(a).toFloat(),
                moonCY + (orbitR - s * 0.008f) * sin(a).toFloat(),
                moonCX + (orbitR + s * 0.008f) * cos(a).toFloat(),
                moonCY + (orbitR + s * 0.008f) * sin(a).toFloat(),
                p,
            )
        }

        // ── 星尘事件（沿轨道散布，时间决定方位） ────────────────────────
        val dayMin = t.hour * 60 + t.minute
        for (m in marks.minutes) {
            val frac  = m / 1440f
            val angle = Math.toRadians(frac * 360.0 - 90.0)
            val sx    = moonCX + orbitR * cos(angle).toFloat()
            val sy    = moonCY + orbitR * sin(angle).toFloat()
            val hd    = s * 0.014f
            val alpha = if (m <= dayMin) 0.75f else 0.30f  // 已过的亮，未来的暗
            val star  = diamondPath(sx, sy, hd)
            p.setFill(TEAL, boot * alpha)
            canvas.drawPath(star, p)

            // 下一个事件：额外微光环
            if (m == marks.nextMinute) {
                p.setStroke(GOLD, s * 0.0032f)
                p.alpha = (255 * 0.35f * boot).toInt()
                canvas.drawCircle(sx, sy, hd * 2.2f, p)
            }
        }

        // ── 月晕（极淡，仅在最外层） ────────────────────────────────────
        p.setStroke(GOLD, s * 0.0042f)
        p.alpha = (255 * 0.05f * glow * boot).toInt().coerceAtMost(255)
        canvas.drawCircle(moonCX, moonCY, moonR * 1.18f, p)

        // ── 月面本体 ────────────────────────────────────────────────────
        p.setFill(MOON_CLR, boot)
        canvas.drawCircle(moonCX, moonCY, moonR, p)

        // ── 高光（左上角柔光，模拟球体） ────────────────────────────────
        val hlX = moonCX - moonR * 0.25f
        val hlY = moonCY - moonR * 0.30f
        p.setFill(0xFFFFFFFF.toInt(), boot * 0.10f)
        canvas.drawCircle(hlX, hlY, moonR * 0.24f, p)
        p.setFill(0xFFFFFFFF.toInt(), boot * 0.05f)
        canvas.drawCircle(hlX, hlY, moonR * 0.40f, p)

        // ── 盈亏阴影（裁切在月面内部） ──────────────────────────────────
        canvas.save()
        val moonClip = Path().apply { addCircle(moonCX, moonCY, moonR, Path.Direction.CW) }
        canvas.clipPath(moonClip)
        val shadowOffset = moonR * cos(moonPhase * 2.0 * PI).toFloat()
        p.setFill(BG, boot)
        canvas.drawCircle(moonCX + shadowOffset, moonCY, moonR * 0.98f, p)
        canvas.restore()

        // ── 月面边缘细线（勾勒轮廓，防止阴影边缘生硬） ──────────────────
        p.setStroke(GOLD, s * 0.0016f)
        p.alpha = (255 * 0.18f * boot).toInt()
        canvas.drawCircle(moonCX, moonCY, moonR, p)

        // ── 地平金线（月球轨迹暗示） ────────────────────────────────────
        val lineY = moonCY + moonR * 1.55f
        p.setStroke(GOLD, s * 0.0026f, Paint.Cap.ROUND)
        p.alpha = (255 * 0.35f * boot).toInt()
        canvas.drawLine(cx - s * 0.23f, lineY, cx + s * 0.23f, lineY, p)

        // 金线两端小点（望远镜十字丝感）
        p.setFill(GOLD, boot * 0.35f)
        canvas.drawCircle(cx - s * 0.23f, lineY, s * 0.0053f, p)
        canvas.drawCircle(cx + s * 0.23f, lineY, s * 0.0053f, p)

        // ── 时间 ────────────────────────────────────────────────────────
        p.setText(CREAM, s * 0.15f, serif)
        canvas.drawText(timeStr(t), cx, lineY + s * 0.09f, p)

        // ── 日期 ────────────────────────────────────────────────────────
        p.setText(DIM, s * 0.046f)
        canvas.drawText(t.format(dateFmt), cx, lineY + s * 0.145f, p)

        // ── 底部珍珠（唤醒锚点，克制版） ────────────────────────────────
        val pearlY = cy + s * 0.395f
        val pearlR = s * 0.024f
        if (tapElapsed in 0 until TAP_MS) {
            val q = tapElapsed / TAP_MS.toFloat()
            p.setStroke(GOLD, s * 0.0032f)
            p.alpha = (255 * 0.30f * (1f - q)).toInt()
            canvas.drawCircle(cx, pearlY, pearlR * 3.5f * q + pearlR, p)
        }
        p.setStroke(GOLD, s * 0.0021f)
        p.alpha = (255 * 0.35f).toInt()
        canvas.drawCircle(cx, pearlY, pearlR * 1.5f, p)
        p.setFill(GOLD)
        canvas.drawCircle(cx, pearlY, pearlR, p)
        p.setFill(0xFFEDD890.toInt(), 0.55f)
        canvas.drawCircle(cx - pearlR * 0.18f, pearlY - pearlR * 0.22f, pearlR * 0.20f, p)
    }

    override fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        val moonPhase = (t.dayOfYear.toFloat() % 29.53f) / 29.53f

        val moonCX = cx
        val moonCY = cy - s * 0.05f
        val moonR  = s * 0.18f

        // 灰色月面（低功耗下不画光晕、轨道、星尘）
        p.setFill(AMB_TEXT, 0.45f)
        canvas.drawCircle(moonCX, moonCY, moonR, p)

        // 盈亏阴影
        val shadowOffset = moonR * cos(moonPhase * 2.0 * PI).toFloat()
        p.setFill(BG)
        canvas.drawCircle(moonCX + shadowOffset, moonCY, moonR * 0.98f, p)

        // 仅保留时间文字
        p.setText(AMB_TEXT, s * 0.15f, serif)
        canvas.drawText(timeStr(t), cx, moonCY + moonR * 1.55f + s * 0.09f, p)
    }

    companion object {
        // 四角星 / 菱形路径，小尺寸下比五角星更清晰
        private fun diamondPath(x: Float, y: Float, h: Float): Path = Path().apply {
            moveTo(x, y - h)
            lineTo(x + h * 0.5f, y)
            lineTo(x, y + h)
            lineTo(x - h * 0.5f, y)
            close()
        }
    }
}
