package com.hamhuo.tplanner

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.hamhuo.tplanner.designsystem.TPlannerWatchFacePalette
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
    private val timeTypeface: Typeface = runCatching {
        ResourcesCompat.getFont(context, R.font.dune_rise)!!
    }.getOrDefault(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    private val minuteTypeface: Typeface = runCatching {
        ResourcesCompat.getFont(context, R.font.dune_rise)!!
    }.getOrDefault(Typeface.create("sans-serif-condensed", Typeface.BOLD))
    private val taskTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val taskStrongTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** The task arcs occupy the half above/right of the diagonal divider. */
    override fun isOnAppLaunchRegion(x: Int, y: Int): Boolean {
        if (faceW <= 0 || faceH <= 0) return false
        val s = minOf(faceW, faceH).toFloat()
        val cx = faceW / 2f
        val cy = faceH / 2f
        val dx = x - cx
        val dy = y - cy
        val contentRadius = s * 0.438f
        val insideContent = dx * dx + dy * dy <= contentRadius * contentRadius
        val onTaskSide = dy < dx
        val onMinuteBadge = minuteBounds(s, cx, cy).contains(x.toFloat(), y.toFloat())
        return insideContent && onTaskSide && !onMinuteBadge
    }

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
                val label = twoDigitNumber(if (minute == 60) 0 else minute)
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
        val hourX = cx - s * 0.225f
        val centerY = cy + s * 0.018f
        p.setText(if (ambient) AMBIENT_PRIMARY else PRIMARY, s * 0.132f, timeTypeface)
        p.alpha = ((if (ambient) 175f else 255f) * alpha).toInt()
        p.textAlign = Paint.Align.CENTER
        val hourBaseline = centerY - (p.ascent() + p.descent()) / 2f
        canvas.drawText(twoDigitNumber(t.hour), hourX, hourBaseline, p)

        val minuteCenterX = cx - s * 0.035f
        val minuteRect = minuteBounds(s, cx, cy)
        p.setStroke(if (ambient) AMBIENT_STROKE else SECONDARY, s * 0.003f, Paint.Cap.ROUND)
        p.alpha = ((if (ambient) 115f else 210f) * alpha).toInt()
        canvas.drawRoundRect(minuteRect, s * 0.019f, s * 0.019f, p)

        p.setText(if (ambient) AMBIENT_PRIMARY else PRIMARY, s * 0.062f, minuteTypeface)
        p.alpha = ((if (ambient) 175f else 245f) * alpha).toInt()
        val minuteBaseline = centerY - (p.ascent() + p.descent()) / 2f
        canvas.drawText(twoDigitNumber(t.minute), minuteCenterX, minuteBaseline, p)

        p.setText(if (ambient) AMBIENT_TEXT else SECONDARY, s * 0.040f, taskTypeface)
        p.alpha = ((if (ambient) 125f else 205f) * alpha).toInt()
        canvas.drawText(
            dateStr(t),
            cx - s * 0.132f,
            cy + s * 0.126f,
            p,
        )
    }

    /** 当天最多三项沿右上半面三条同心弧排布，路径端点正好落在斜切线上。 */
    private fun drawTaskArcs(
        canvas: Canvas,
        nowTime: ZonedDateTime,
        s: Float,
        cx: Float,
        cy: Float,
        alpha: Float,
        ambient: Boolean,
    ) {
        val today = nowTime.toLocalDate()
        val dayStartMs = today.atStartOfDay(APP_ZONE).toInstant().toEpochMilli()
        val dayEndMs = today.plusDays(1).atStartOfDay(APP_ZONE).toInstant().toEpochMilli()
        val todayTasks = marks.items.filter { task ->
            task.endEpochMs > dayStartMs && task.startEpochMs < dayEndMs
        }
        val visibleTasks = if (ambient) todayTasks.take(1) else todayTasks.take(MAX_VISIBLE_TASKS)
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
        val label = localizedText(R.string.watchface_empty_today)
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
        val centerX = cx - s * 0.035f
        val centerY = cy + s * 0.018f
        val halfWidth = s * 0.064f
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

    private fun taskLabel(nowTime: ZonedDateTime, task: WatchEventMarks.NextTask): String {
        val start = Instant.ofEpochMilli(task.startEpochMs).atZone(APP_ZONE)
        val end = Instant.ofEpochMilli(task.endEpochMs).atZone(APP_ZONE)
        val prefix = when {
            !nowTime.isBefore(start) && nowTime.isBefore(end) ->
                localizedText(R.string.watchface_task_now)
            start.toLocalDate() == nowTime.toLocalDate() -> timeStr(start)
            start.toLocalDate() == nowTime.toLocalDate().plusDays(1) ->
                localizedText(R.string.watchface_task_tomorrow_time, timeStr(start))
            else -> localizedText(
                R.string.watchface_task_date_time,
                shortDateStr(start),
                timeStr(start),
            )
        }
        return localizedText(R.string.watchface_task_label, prefix, task.title)
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
        const val DIAGONAL_MINUTE_GAP = 0.030f

        const val BLACK = TPlannerWatchFacePalette.Next.Black
        const val PRIMARY = TPlannerWatchFacePalette.Next.Primary
        const val SECONDARY = TPlannerWatchFacePalette.Next.Secondary
        const val WHEEL_LABEL = TPlannerWatchFacePalette.Next.WheelLabel
        const val WHEEL_MAJOR = TPlannerWatchFacePalette.Next.WheelMajor
        const val WHEEL_MINOR = TPlannerWatchFacePalette.Next.WheelMinor
        const val WHEEL_TRACK = TPlannerWatchFacePalette.Next.WheelTrack
        const val TASK_TRACK = TPlannerWatchFacePalette.Next.TaskTrack
        const val FRAME = TPlannerWatchFacePalette.Next.Frame
        const val DIVIDER = TPlannerWatchFacePalette.Next.Divider
        const val ACCENT = TPlannerWatchFacePalette.Next.Accent
        const val ACCENT_LIGHT = TPlannerWatchFacePalette.Next.AccentLight
        const val AMBIENT_PRIMARY = TPlannerWatchFacePalette.Next.AmbientPrimary
        const val AMBIENT_TEXT = TPlannerWatchFacePalette.Next.AmbientText
        const val AMBIENT_STROKE = TPlannerWatchFacePalette.Next.AmbientStroke
    }
}
