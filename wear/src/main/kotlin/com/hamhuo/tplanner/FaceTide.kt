package com.hamhuo.tplanner

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// 潮汐：24h 波浪进度——曲线从午夜开始随当前时刻向上涨，金球在浪尖。
// 今日已过的时间 = 金色波段 + 填充；未到的 = 暗轨。手机调起记录为青色菱形。
class FaceTide(
    private val context: android.content.Context,
    surfaceHolder: android.view.SurfaceHolder,
    currentUserStyleRepository: androidx.wear.watchface.style.CurrentUserStyleRepository,
    watchState: androidx.wear.watchface.WatchState,
) : FaceBase(context, surfaceHolder, currentUserStyleRepository, watchState, FaceDesign.TIDE) {

    @Volatile private var wakeMinutes = emptyList<Int>()
    @Volatile private var wakeDate: java.time.LocalDate? = null

    override fun onWakeInvoked(at: ZonedDateTime) {
        wakeDate = at.toLocalDate()
        wakeMinutes = WakeInvocationMarks.record(context, at)
    }

    // 浪尖金球命中检测——替代底部珍珠锚点
    override fun isOnWakeButton(x: Int, y: Int): Boolean {
        if (faceW == 0) return false
        val s  = min(faceW, faceH).toFloat()
        val cx = faceW / 2f
        val cy = faceH / 2f
        val g  = geo(s, cx, cy)
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val dayFrac = (now.hour * 3600f + now.minute * 60f + now.second) / 86400f
        val frac = dayFrac.coerceIn(0f, 1f)
        val tx = g.startX + frac * g.width
        val ty = g.baseY - g.amp * cos(frac * 2.0 * PI).toFloat()
        val dx = x - tx; val dy = y - ty
        val hitR = s * 0.08f  // 金球半径 0.035f 的三倍宽容区
        return sqrt((dx * dx + dy * dy).toDouble()) <= hitR
    }

    private fun refreshWakeMinutes(t: ZonedDateTime) {
        val date = t.toLocalDate()
        if (wakeDate != date) {
            wakeDate = date
            wakeMinutes = WakeInvocationMarks.load(context, date)
        }
    }

    // 提前算好共享几何，避免在 drawInteractive / drawAmbient 里重复
    private data class WaveGeo(
        val baseY: Float, val amp: Float,
        val startX: Float, val endX: Float, val width: Float,
    )

    private fun geo(s: Float, cx: Float, cy: Float) = WaveGeo(
        baseY  = cy + s * 0.045f,
        amp    = s * 0.105f,
        startX = cx - s * 0.47f,
        endX   = cx + s * 0.47f,
        width  = s * 0.94f,
    )

    // 生成 [0 .. frac] 这一段波（起始→当前进度），返回路径 + 当前末端坐标
    private fun waveSegment(g: WaveGeo, frac: Float, steps: Int = 80): Pair<Path, Pair<Float, Float>> {
        val path = Path()
        val endI = (steps * frac).toInt().coerceAtMost(steps)
        path.moveTo(g.startX, g.baseY - g.amp * cos(0.0).toFloat())
        var lastX = g.startX; var lastY = g.baseY - g.amp
        for (i in 0..endI) {
            val x    = g.startX + i * g.width / steps
            val f    = (x - g.startX) / g.width
            val y    = g.baseY - g.amp * cos(f * 2.0 * PI).toFloat()
            path.lineTo(x, y)
            lastX = x; lastY = y
        }
        return Pair(path, Pair(lastX, lastY))
    }

    // 完整波（轨道用）
    private fun fullWave(g: WaveGeo, steps: Int = 80): Path {
        val (path, _) = waveSegment(g, 1f, steps)
        return path
    }

    override fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        refreshWakeMinutes(t)
        val boot = bootAlpha
        val g    = geo(s, cx, cy)

        val dayFrac = (t.hour * 3600f + t.minute * 60f + t.second) / 86400f

        // ── 轨道：完整暗线 ──────────────────────────────────────────────────
        p.setStroke(TRACK, s * 0.0032f, Paint.Cap.ROUND)
        p.alpha = (255 * 0.6f * boot).toInt()
        canvas.drawPath(fullWave(g), p)

        // ── 已涨波段（0 → dayFrac） ──────────────────────────────────────────
        val (goldPath, tip) = waveSegment(g, dayFrac)
        val tipX = tip.first; val tipY = tip.second

        // 波段下方渐变填充（只填已涨部分）
        val fillBottom = g.baseY + g.amp + s * 0.06f
        val fillPath   = Path(goldPath)
        fillPath.lineTo(tipX, fillBottom)       // 末端下垂
        fillPath.lineTo(g.startX, fillBottom)   // 回起点
        fillPath.close()

        val gradTop = GOLD and 0x00FFFFFF or (18 shl 24)
        val gradBot = GOLD and 0x00FFFFFF or (0 shl 24)
        p.shader = LinearGradient(
            cx, g.baseY - g.amp, cx, fillBottom,
            intArrayOf(gradTop, gradBot),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        p.setFill(GOLD, boot)
        canvas.drawPath(fillPath, p)
        p.shader = null

        // 金色波段线
        p.setStroke(GOLD, s * 0.0042f, Paint.Cap.ROUND)
        p.alpha = (255 * boot).toInt()
        canvas.drawPath(goldPath, p)

        // ── 今日调起记录：按点击唤醒按钮的时间映射到 24h 潮汐线上 ────────────
        for (m in wakeMinutes) {
            val frac  = m / 1440f
            val ex    = g.startX + frac * g.width
            val ey    = g.baseY - g.amp * cos(frac * 2.0 * PI).toFloat()
            val hd    = s * 0.016f
            val alpha = if (frac <= dayFrac) 0.8f else 0.35f
            val dia   = Path().apply {
                moveTo(ex, ey - hd)
                lineTo(ex + hd * 0.55f, ey)
                lineTo(ex, ey + hd)
                lineTo(ex - hd * 0.55f, ey)
                close()
            }
            p.setFill(TEAL, boot * alpha)
            canvas.drawPath(dia, p)
        }

        // ── 日程事件：蓝色半透明小点，映射到 24h 潮汐线上 ──────────────────
        for (m in marks.minutes) {
            val frac = m / 1440f
            val ex   = g.startX + frac * g.width
            val ey   = g.baseY - g.amp * cos(frac * 2.0 * PI).toFloat()
            val dotR = s * 0.013f
            p.setFill(EVENT_DOT, boot)
            canvas.drawCircle(ex, ey, dotR, p)
        }

        // ── 浪尖金球 ──────────────────────────────────────────────────────
        val orbR = s * 0.035f
        val breath = 0.55f + 0.9f * (0.5f + 0.5f * sin(2.0 * PI * (now % 4000L) / 4000.0).toFloat())
        val flare  = 1f + 2.5f * (1f - (tapElapsed.coerceIn(0, TAP_MS) / TAP_MS.toFloat()))
        val glow   = breath * (if (tapElapsed < TAP_MS) flare else 1f)

        p.setStroke(GOLD, s * 0.0042f)
        p.alpha = (255 * 0.28f * glow * boot).toInt().coerceAtMost(255)
        canvas.drawCircle(tipX, tipY, orbR * 2.4f, p)
        p.setStroke(GOLD, s * 0.0032f)
        p.alpha = (255 * 0.45f * glow * boot).toInt().coerceAtMost(255)
        canvas.drawCircle(tipX, tipY, orbR * 1.6f, p)
        p.setFill(GOLD, boot)
        canvas.drawCircle(tipX, tipY, orbR, p)
        p.setFill(0xFFEDD890.toInt(), boot * 0.7f)
        canvas.drawCircle(tipX, tipY, orbR * 0.5f, p)

        // ── 时间 & 日期 ────────────────────────────────────────────────────
        p.setText(CREAM, s * 0.14f, serif)
        canvas.drawText(timeStr(t), cx, cy - s * 0.14f, p)
        p.setText(DIM, s * 0.046f)
        canvas.drawText(t.format(dateFmt), cx, cy - s * 0.06f, p)

    }

    override fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        val g       = geo(s, cx, cy)
        val dayFrac = (t.hour * 3600f + t.minute * 60f + t.second) / 86400f

        // 暗轨
        p.setStroke(AMB_TRACK, s * 0.0026f, Paint.Cap.ROUND)
        p.alpha = 255
        canvas.drawPath(fullWave(g, 40), p)

        // 已涨波段
        val (goldPath, _) = waveSegment(g, dayFrac, 40)
        p.setStroke(AMB_GOLD, s * 0.0032f, Paint.Cap.ROUND)
        canvas.drawPath(goldPath, p)

        // 时刻暗金点
        val orbFrac = dayFrac
        val orbX = g.startX + orbFrac * g.width
        val orbY = g.baseY - g.amp * cos(orbFrac * 2.0 * PI).toFloat()
        p.setFill(AMB_GOLD)
        canvas.drawCircle(orbX, orbY, s * 0.026f, p)

        p.setText(AMB_TEXT, s * 0.14f, serif)
        canvas.drawText(timeStr(t), cx, cy - s * 0.14f, p)
    }
}
