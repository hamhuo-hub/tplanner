package com.hamhuo.tplanner

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * “下一项”表盘：左下半面是 Wear OS 风格的分钟轮，右上半面是事项弧带。
 * 两块内容沿左上到右下的对角线分开，保留梵克雅宝参考表盘的不对称构图。
 */
class FaceNext(
    context: android.content.Context,
    surfaceHolder: android.view.SurfaceHolder,
    currentUserStyleRepository: androidx.wear.watchface.style.CurrentUserStyleRepository,
    watchState: androidx.wear.watchface.WatchState,
) : FaceBase(context, surfaceHolder, currentUserStyleRepository, watchState, FaceDesign.NEXT) {
    private val hasBurnInProtection = watchState.hasBurnInProtection
    private val timeTypeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val minuteTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val taskTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val taskStrongTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    override fun drawInteractive(
        canvas: Canvas,
        t: ZonedDateTime,
        s: Float,
        cx: Float,
        cy: Float,
    ) {
        canvas.drawColor(BLACK)
        val alpha = bootAlpha
        drawOuterFrame(canvas, s, cx, cy, alpha)
        drawTimeWheel(canvas, t, s, cx, cy, alpha, ambient = false)
        drawTaskArcs(canvas, t, s, cx, cy, alpha, ambient = false)
        drawDiagonalCut(canvas, s, cx, cy, alpha, ambient = false)
        drawTimeReadout(canvas, t, s, cx, cy, alpha, ambient = false)
        drawTapFeedback(canvas, s, cx, cy, alpha)
    }

    override fun drawAmbient(
        canvas: Canvas,
        t: ZonedDateTime,
        s: Float,
        cx: Float,
        cy: Float,
    ) {
        canvas.drawColor(BLACK)
        val (offsetX, offsetY) = if (hasBurnInProtection) burnInOffset(t, s) else Pair(0f, 0f)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        drawTimeWheel(canvas, t, s, cx, cy, 1f, ambient = true)
        drawTaskArcs(canvas, t, s, cx, cy, 1f, ambient = true)
        if (!hasBurnInProtection) drawDiagonalCut(canvas, s, cx, cy, 1f, ambient = true)
        drawTimeReadout(canvas, t, s, cx, cy, 1f, ambient = true)
        canvas.restore()
    }

    private fun drawOuterFrame(canvas: Canvas, s: Float, cx: Float, cy: Float, alpha: Float) {
        p.setStroke(FRAME, s * 0.0025f)
        p.alpha = (150f * alpha).toInt()
        canvas.drawCircle(cx, cy, s * 0.468f, p)
    }

    /** 0–60 分钟沿左下半圆展开；当前分钟使用暖金色刻度强调。 */
    private fun drawTimeWheel(
        canvas: Canvas,
        t: ZonedDateTime,
        s: Float,
        cx: Float,
        cy: Float,
        alpha: Float,
        ambient: Boolean,
    ) {
        val outerRadius = s * 0.421f
        val wheelBounds = circleBounds(cx, cy, outerRadius)
        if (!ambient) {
            p.setStroke(WHEEL_TRACK, s * 0.0022f, Paint.Cap.ROUND)
            p.alpha = (150f * alpha).toInt()
            canvas.drawArc(wheelBounds, TIME_START_ANGLE, HALF_SWEEP, false, p)
        }

        val currentMinute = t.minute + t.second / 60f
        for (minute in 0..60) {
            val major = minute % 10 == 0
            if (ambient && !major) continue

            val angle = TIME_START_ANGLE + minute / 60f * HALF_SWEEP
            val isCurrent = !ambient && minute == currentMinute.toInt().coerceIn(0, 59)
            val tickLength = when {
                isCurrent -> s * 0.052f
                major -> s * 0.034f
                else -> s * 0.017f
            }
            val outer = pointOnCircle(cx, cy, outerRadius, angle)
            val inner = pointOnCircle(cx, cy, outerRadius - tickLength, angle)
            p.setStroke(
                when {
                    isCurrent -> ACCENT
                    ambient -> AMBIENT_TEXT
                    major -> WHEEL_MAJOR
                    else -> WHEEL_MINOR
                },
                when {
                    isCurrent -> s * 0.008f
                    major -> s * 0.004f
                    else -> s * 0.002f
                },
                Paint.Cap.ROUND,
            )
            p.alpha = ((if (ambient) 120f else 235f) * alpha).toInt()
            canvas.drawLine(inner.first, inner.second, outer.first, outer.second, p)

            if (major) {
                val label = if (minute == 60) "00" else minute.toString().padStart(2, '0')
                val labelPoint = pointOnCircle(cx, cy, outerRadius - s * 0.070f, angle)
                drawTangentLabel(
                    canvas = canvas,
                    label = label,
                    x = labelPoint.first,
                    y = labelPoint.second,
                    angle = angle,
                    color = if (ambient) AMBIENT_TEXT else WHEEL_LABEL,
                    textSize = s * 0.029f,
                    alpha = (if (ambient) 120f else 205f) * alpha,
                )
            }
        }

    }

    private fun drawTimeReadout(
        canvas: Canvas,
        t: ZonedDateTime,
        s: Float,
        cx: Float,
        cy: Float,
        alpha: Float,
        ambient: Boolean,
    ) {
        val hourX = cx - s * 0.205f
        val centerY = cy + s * 0.018f
        p.setText(if (ambient) AMBIENT_PRIMARY else PRIMARY, s * 0.142f, timeTypeface)
        p.alpha = ((if (ambient) 175f else 255f) * alpha).toInt()
        p.textAlign = Paint.Align.CENTER
        val hourBaseline = centerY - (p.ascent() + p.descent()) / 2f
        canvas.drawText(t.hour.toString().padStart(2, '0'), hourX, hourBaseline, p)

        val minuteCenterX = cx - s * 0.055f
        val minuteRect = minuteBounds(s, cx, cy)
        p.setStroke(if (ambient) AMBIENT_STROKE else SECONDARY, s * 0.003f, Paint.Cap.ROUND)
        p.alpha = ((if (ambient) 115f else 210f) * alpha).toInt()
        canvas.drawRoundRect(minuteRect, s * 0.019f, s * 0.019f, p)

        p.setText(if (ambient) AMBIENT_PRIMARY else PRIMARY, s * 0.068f, minuteTypeface)
        p.alpha = ((if (ambient) 175f else 245f) * alpha).toInt()
        val minuteBaseline = centerY - (p.ascent() + p.descent()) / 2f
        canvas.drawText(t.minute.toString().padStart(2, '0'), minuteCenterX, minuteBaseline, p)

        p.setText(if (ambient) AMBIENT_TEXT else SECONDARY, s * 0.026f, taskTypeface)
        p.alpha = ((if (ambient) 105f else 175f) * alpha).toInt()
        canvas.drawText(
            dateFmt.format(t),
            cx - s * 0.132f,
            cy + s * 0.119f,
            p,
        )
    }

    /** 最近三项沿右上半面三条同心弧排布，路径端点正好落在斜切线上。 */
    private fun drawTaskArcs(
        canvas: Canvas,
        nowTime: ZonedDateTime,
        s: Float,
        cx: Float,
        cy: Float,
        alpha: Float,
        ambient: Boolean,
    ) {
        val visibleTasks = if (ambient) marks.items.take(1) else marks.items.take(MAX_VISIBLE_TASKS)
        if (visibleTasks.isEmpty()) {
            drawEmptyTaskArc(canvas, s, cx, cy, alpha, ambient)
            return
        }

        visibleTasks.forEachIndexed { index, task ->
            val radius = s * (0.383f - index * 0.074f)
            val arc = taskArc(cx, cy, radius)
            if (!ambient) {
                p.setStroke(TASK_TRACK, s * 0.002f, Paint.Cap.ROUND)
                p.alpha = ((145f - index * 22f) * alpha).toInt()
                canvas.drawPath(arc, p)
            }

            val pathLength = PathMeasure(arc, false).length
            val textSize = s * (if (index == 0) 0.043f else 0.038f)
            p.setText(
                when {
                    ambient -> AMBIENT_PRIMARY
                    index == 0 -> ACCENT_LIGHT
                    else -> PRIMARY
                },
                textSize,
                if (index == 0) taskStrongTypeface else taskTypeface,
            )
            p.textAlign = Paint.Align.LEFT
            p.alpha = ((if (ambient) 145f else 235f - index * 35f) * alpha).toInt()
            val rawLabel = taskLabel(nowTime, task)
            val label = ellipsizeForWidth(rawLabel, pathLength - s * 0.090f)
            val offset = ((pathLength - p.measureText(label)) / 2f).coerceAtLeast(s * 0.045f)
            canvas.drawTextOnPath(label, arc, offset, -s * 0.010f, p)
            p.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawEmptyTaskArc(
        canvas: Canvas,
        s: Float,
        cx: Float,
        cy: Float,
        alpha: Float,
        ambient: Boolean,
    ) {
        val arc = taskArc(cx, cy, s * 0.340f)
        if (!ambient) {
            p.setStroke(TASK_TRACK, s * 0.002f, Paint.Cap.ROUND)
            p.alpha = (120f * alpha).toInt()
            canvas.drawPath(arc, p)
        }
        p.setText(if (ambient) AMBIENT_TEXT else SECONDARY, s * 0.038f, taskTypeface)
        p.textAlign = Paint.Align.LEFT
        p.alpha = ((if (ambient) 105f else 180f) * alpha).toInt()
        val label = "同步后显示事项"
        val length = PathMeasure(arc, false).length
        canvas.drawTextOnPath(label, arc, (length - p.measureText(label)) / 2f, -s * 0.010f, p)
        p.textAlign = Paint.Align.CENTER
    }

    private fun drawDiagonalCut(
        canvas: Canvas,
        s: Float,
        cx: Float,
        cy: Float,
        alpha: Float,
        ambient: Boolean,
    ) {
        val start = pointOnCircle(cx, cy, s * 0.438f, TASK_START_ANGLE)
        val end = pointOnCircle(cx, cy, s * 0.438f, TIME_START_ANGLE)
        val gapBounds = minuteBounds(s, cx, cy).apply {
            val padding = s * DIAGONAL_MINUTE_GAP
            inset(-padding, -padding)
        }

        // The divider is fixed at 45 degrees through (cx, cy), so points on it
        // share the same x/y delta. Stop before the expanded minute bounds and
        // resume after them, leaving the minute badge in a real geometric gap.
        val gapStartDelta = maxOf(gapBounds.left - cx, gapBounds.top - cy)
        val gapEndDelta = minOf(gapBounds.right - cx, gapBounds.bottom - cy)
        val gapStartX = cx + gapStartDelta
        val gapStartY = cy + gapStartDelta
        val gapEndX = cx + gapEndDelta
        val gapEndY = cy + gapEndDelta

        fun drawSegments() {
            canvas.drawLine(start.first, start.second, gapStartX, gapStartY, p)
            canvas.drawLine(gapEndX, gapEndY, end.first, end.second, p)
        }

        if (!ambient) {
            p.setStroke(BLACK, s * 0.022f, Paint.Cap.ROUND)
            drawSegments()
        }
        p.setStroke(if (ambient) AMBIENT_STROKE else DIVIDER, s * 0.0025f, Paint.Cap.ROUND)
        p.alpha = ((if (ambient) 85f else 190f) * alpha).toInt()
        drawSegments()

    }

    private fun minuteBounds(s: Float, cx: Float, cy: Float): RectF {
        val centerX = cx - s * 0.055f
        val centerY = cy + s * 0.018f
        val halfWidth = s * 0.072f
        val halfHeight = s * 0.050f
        return RectF(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight,
        )
    }

    private fun drawTangentLabel(
        canvas: Canvas,
        label: String,
        x: Float,
        y: Float,
        angle: Float,
        color: Int,
        textSize: Float,
        alpha: Float,
    ) {
        var rotation = ((angle + 90f) % 360f + 360f) % 360f
        if (rotation > 180f) rotation -= 360f
        if (rotation > 90f) rotation -= 180f
        if (rotation < -90f) rotation += 180f
        canvas.save()
        canvas.rotate(rotation, x, y)
        p.setText(color, textSize, minuteTypeface)
        p.alpha = alpha.toInt().coerceIn(0, 255)
        val baseline = y - (p.ascent() + p.descent()) / 2f
        canvas.drawText(label, x, baseline, p)
        canvas.restore()
    }

    private fun drawTapFeedback(canvas: Canvas, s: Float, cx: Float, cy: Float, alpha: Float) {
        if (tapElapsed !in 0L until TAP_MS) return
        val progress = tapElapsed / TAP_MS.toFloat()
        val halfLength = s * (0.05f + progress * 0.34f)
        val unit = 1f / kotlin.math.sqrt(2f)
        p.setStroke(ACCENT_LIGHT, s * 0.004f, Paint.Cap.ROUND)
        p.alpha = (170f * (1f - progress) * alpha).toInt().coerceIn(0, 255)
        canvas.drawLine(
            cx - halfLength * unit,
            cy - halfLength * unit,
            cx + halfLength * unit,
            cy + halfLength * unit,
            p,
        )
    }

    private fun taskLabel(nowTime: ZonedDateTime, task: WatchEventMarks.NextTask): String {
        val start = Instant.ofEpochMilli(task.startEpochMs).atZone(APP_ZONE)
        val end = Instant.ofEpochMilli(task.endEpochMs).atZone(APP_ZONE)
        val prefix = when {
            !nowTime.isBefore(start) && nowTime.isBefore(end) -> "现在"
            start.toLocalDate() == nowTime.toLocalDate() -> "%02d:%02d".format(start.hour, start.minute)
            start.toLocalDate() == nowTime.toLocalDate().plusDays(1) -> "明天 %02d:%02d".format(start.hour, start.minute)
            else -> "%d/%d %02d:%02d".format(start.monthValue, start.dayOfMonth, start.hour, start.minute)
        }
        return "$prefix · ${task.title}"
    }

    /** Paint.breakText 返回 UTF-16 单元，截断时避免切在代理对中间。 */
    private fun ellipsizeForWidth(text: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (p.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val contentWidth = (maxWidth - p.measureText(ellipsis)).coerceAtLeast(0f)
        var end = p.breakText(text, true, contentWidth, null).coerceIn(0, text.length)
        if (
            end in 1 until text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end -= 1
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private fun burnInOffset(t: ZonedDateTime, s: Float): Pair<Float, Float> {
        val step = (s * 0.004f).coerceIn(1f, 2f)
        return when ((t.hour * 60 + t.minute) % 9) {
            0 -> Pair(-step, -step)
            1 -> Pair(0f, -step)
            2 -> Pair(step, -step)
            3 -> Pair(step, 0f)
            4 -> Pair(step, step)
            5 -> Pair(0f, step)
            6 -> Pair(-step, step)
            7 -> Pair(-step, 0f)
            else -> Pair(0f, 0f)
        }
    }

    private fun taskArc(cx: Float, cy: Float, radius: Float): Path = Path().apply {
        addArc(circleBounds(cx, cy, radius), TASK_START_ANGLE, HALF_SWEEP)
    }

    private fun circleBounds(cx: Float, cy: Float, radius: Float) = RectF(
        cx - radius,
        cy - radius,
        cx + radius,
        cy + radius,
    )

    private fun pointOnCircle(
        cx: Float,
        cy: Float,
        radius: Float,
        angleDegrees: Float,
    ): Pair<Float, Float> {
        val radians = angleDegrees / 180.0 * PI
        return Pair(
            cx + cos(radians).toFloat() * radius,
            cy + sin(radians).toFloat() * radius,
        )
    }

    private companion object {
        const val TIME_START_ANGLE = 45f
        const val TASK_START_ANGLE = 225f
        const val HALF_SWEEP = 180f
        const val MAX_VISIBLE_TASKS = 3
        const val DIAGONAL_MINUTE_GAP = 0.018f

        const val BLACK = 0xFF000000.toInt()
        const val PRIMARY = 0xFFF4F1EB.toInt()
        const val SECONDARY = 0xFF9C9992.toInt()
        const val WHEEL_LABEL = 0xFFD8D5CE.toInt()
        const val WHEEL_MAJOR = 0xFFC2BFB8.toInt()
        const val WHEEL_MINOR = 0xFF65635F.toInt()
        const val WHEEL_TRACK = 0xFF2D2C2A.toInt()
        const val TASK_TRACK = 0xFF34312D.toInt()
        const val FRAME = 0xFF292826.toInt()
        const val DIVIDER = 0xFF6F665B.toInt()
        const val ACCENT = 0xFFD9A441.toInt()
        const val ACCENT_LIGHT = 0xFFF0C96C.toInt()
        const val AMBIENT_PRIMARY = 0xFFB4B1AA.toInt()
        const val AMBIENT_TEXT = 0xFF77746E.toInt()
        const val AMBIENT_STROKE = 0xFF4A4742.toInt()
    }
}
