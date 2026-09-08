package com.hamhuo.tplanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.hamhuo.tplanner.designsystem.TPlannerTypography
import com.hamhuo.tplanner.designsystem.TPlannerWatchFacePalette.Hop
import java.time.ZonedDateTime
import java.time.Instant
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

/** The production Canvas painter is also the screenshot source; previews contain no copied UI. */
internal class HopFacePainter(context: Context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Path()
    private val clip = Path()
    private val bounds = RectF()
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var depthMetrics: HopFaceMetrics? = null
    private val hourTypeface = Typeface.create(
        ResourcesCompat.getFont(context, R.font.comfortaa) ?: Typeface.DEFAULT, Typeface.BOLD,
    )
    private val taskTypeface = Typeface.create("sans-serif", Typeface.NORMAL)

    /** Stages are useful for reproducing the visual baseline, never stored in user data. */
    enum class Stage { DIAL, INTERVAL, LABEL, TASKS }

    fun draw(
        canvas: Canvas,
        metrics: HopFaceMetrics,
        time: ZonedDateTime,
        tasks: List<HopTaskInterval>,
        ambient: Boolean = false,
        burnInProtection: Boolean = false,
        lowBitAmbient: Boolean = false,
        stage: Stage = Stage.TASKS,
    ) {
        val g = metrics.geometry(time)
        canvas.drawColor(if (ambient) Hop.AmbientBackground else Hop.Rim)
        p.isAntiAlias = !ambient || !lowBitAmbient
        canvas.save()
        clip.rewind()
        clip.addCircle(metrics.centerX, metrics.centerY, metrics.faceRadiusPx, Path.Direction.CW)
        canvas.clipPath(clip)
        if (!ambient) canvas.drawColor(Hop.Paper)
        canvas.save()
        if (ambient && burnInProtection) {
            val minute = time.hour * 60 + time.minute
            val step = metrics.density
            canvas.translate(((minute % 3) - 1) * step, (((minute / 3) % 3) - 1) * step)
        }
        drawDial(canvas, g, time, ambient)
        if (!ambient && stage != Stage.DIAL) {
            drawTasks(canvas, g, if (stage == Stage.TASKS) tasks else tasks.take(1), stage != Stage.INTERVAL)
        }
        drawNow(canvas, g, ambient)
        canvas.restore()
        // The housing is stationary: the inner shadow overlays the moving artwork, and
        // its lower edge stays light. No blur/filter or extra frame scheduling is needed.
        if (!ambient) drawInsetDepth(canvas, metrics)
        canvas.restore()
    }

    private fun drawInsetDepth(canvas: Canvas, m: HopFaceMetrics) {
        if (depthMetrics != m) {
            depthMetrics = m
            depthPaint.shader = RadialGradient(
                m.centerX - m.diameterPx * 0.012f,
                m.centerY + m.diameterPx * 0.035f,
                m.faceRadiusPx * 1.045f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(Hop.RecessShadow, 0),
                    ColorUtils.setAlphaComponent(Hop.RecessShadow, 9),
                    ColorUtils.setAlphaComponent(Hop.RecessShadow, 45),
                    ColorUtils.setAlphaComponent(Hop.RecessShadow, 112),
                ),
                floatArrayOf(0.78f, 0.87f, 0.96f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(m.centerX, m.centerY, m.faceRadiusPx, depthPaint)
    }

    private fun drawDial(canvas: Canvas, g: HopDialGeometry, time: ZonedDateTime, ambient: Boolean) {
        val m = g.metrics
        // The visible portion is always less than half a revolution; enumerate nearby absolute
        // hours, so 12/1 and midnight preserve their relative positions without duplicated dials.
        val baseHour = time.withMinute(0).withSecond(0).withNano(0)
        for (offset in -3..3) {
            val hour = baseHour.plusHours(offset.toLong())
            val angle = g.angleAt(hour.toInstant().toEpochMilli())
            val point = g.point(m.orbitRadiusPx, angle)
            val hour12 = ((hour.hour + 11) % 12 + 1).toString()
            text(if (ambient) Hop.AmbientInk else Hop.Ink, m.hourTextSizePx, hourTypeface)
            // Keep numerals upright even as their positions move around the virtual dial.
            canvas.drawText(hour12, point.x, point.y - (p.ascent() + p.descent()) / 2f, p)
        }

        val halfWindow = g.halfWindowMs(m.tickRadiusPx, -m.density * 3f)
        val tickStep = 60_000L * 2 // Thirty quiet hairlines per hour.
        val first = Math.floorDiv(g.nowEpochMs - halfWindow, tickStep) * tickStep
        val last = g.nowEpochMs + halfWindow
        var tick = first
        while (tick <= last) {
            val minute = Instant.ofEpochMilli(tick).atZone(time.zone).minute
            val major = minute % 10 == 0
            val hour = minute == 0
            if (!ambient || hour) {
                val angle = g.angleAt(tick)
                val length = when { hour -> 11f; major -> 7f; else -> 3.5f } * m.density
                val outer = g.point(m.tickRadiusPx, angle)
                val inner = g.point(m.tickRadiusPx - length, angle)
                stroke(if (ambient) Hop.AmbientInk else Hop.Ink, m.density * if (major) 1f else 0.65f)
                canvas.drawLine(inner.x, inner.y, outer.x, outer.y, p)
            }
            tick += tickStep
        }
    }

    private fun drawTasks(canvas: Canvas, g: HopDialGeometry, tasks: List<HopTaskInterval>, labels: Boolean) {
        val m = g.metrics
        text(Hop.Task, m.taskTextSizePx, taskTypeface)
        val fontHeight = p.fontMetrics.descent - p.fontMetrics.ascent
        // Measured font height controls spacing; increased accessibility text never changes
        // clock geometry or becomes tiny to squeeze a second lane into the face.
        val laneGap = fontHeight + 5f * m.density
        val firstRadius = m.diameterPx * HopSizeSpec.TASK_DIAMETER_RATIO
        val availableRadialSpace = m.safeRadiusPx + m.orbitRadiusPx - firstRadius
        val lanes = if (laneGap + fontHeight / 2f < availableRadialSpace) 2 else 1
        val halfWindow = g.halfWindowMs(firstRadius)
        val segments = HopTimeline.layout(tasks, g.nowEpochMs - halfWindow, g.nowEpochMs + halfWindow, lanes)

        for (segment in segments) {
            val radius = firstRadius + segment.lane * laneGap
            val half = g.halfWindowMs(radius)
            val start = maxOf(segment.task.startEpochMs, g.nowEpochMs - half)
            val end = minOf(segment.task.endEpochMs, g.nowEpochMs + half)
            if (segment.isPoint) {
                if (start == segment.task.startEpochMs && start < g.nowEpochMs + half) cap(canvas, g, radius, start)
                continue
            }
            if (start >= end) continue
            val showStart = segment.task.startEpochMs >= g.nowEpochMs - half
            val showEnd = segment.task.endEpochMs < g.nowEpochMs + half

            val readableInset = m.faceRadiusPx - m.safeRadiusPx + fontHeight / 2f + 2f * m.density
            val readableHalf = g.halfWindowMs(radius, readableInset)
            val padMs = (4f * m.density / radius * HopSizeSpec.MILLIS_PER_TURN / (2 * PI)).toLong()
            val readableStart = maxOf(start + padMs, g.nowEpochMs - readableHalf)
            val readableEnd = minOf(end - padMs, g.nowEpochMs + readableHalf)
            text(Hop.Task, m.taskTextSizePx, taskTypeface)
            val length = (readableEnd - readableStart).coerceAtLeast(0).toFloat() / HopSizeSpec.MILLIS_PER_TURN * 2f * PI.toFloat() * radius
            val label = if (labels) HopLabelPlanner.plan(segment.task.title, length, m.taskTextSizePx, p::measureText) else null

            var gapStart = end
            var gapEnd = end
            if (label != null && readableEnd > readableStart) {
                val textHalfMs = ceil(label.widthPx / radius * HopSizeSpec.MILLIS_PER_TURN / (4 * PI)).toLong()
                val minAnchor = readableStart + textHalfMs
                val maxAnchor = readableEnd - textHalfMs
                if (minAnchor <= maxAnchor) {
                    val preferredAnchor = HopTimeline.labelAnchor(
                        segment.copy(startEpochMs = start, endEpochMs = end,
                            showStartCap = showStart, showEndCap = showEnd),
                        minAnchor, maxAnchor,
                    )
                    // The full-length hand crosses every task track at NOW. Keep readable
                    // titles to its nearest free side without cutting a gap in the hand.
                    val handClearance = textHalfMs + padMs
                    val anchor = if (abs(preferredAnchor - g.nowEpochMs) >= handClearance) {
                        preferredAnchor
                    } else {
                        listOf(g.nowEpochMs - handClearance, g.nowEpochMs + handClearance)
                            .filter { it in minAnchor..maxAnchor }
                            .minByOrNull { abs(it - preferredAnchor) }
                    }
                    if (anchor != null) {
                        gapStart = anchor - textHalfMs - padMs
                        gapEnd = anchor + textHalfMs + padMs
                        drawLabel(canvas, g, radius, anchor, label)
                    }
                }
            }
            // Short names occupy their natural width. Fine time strokes carry the rest of the
            // duration, and real endpoints alone receive caps. Long tasks never make a ring.
            stroke(Hop.Track, 0.55f * m.density)
            drawArc(canvas, g, radius, start, minOf(end, gapStart))
            drawArc(canvas, g, radius, maxOf(start, gapEnd), end)
            if (showStart) cap(canvas, g, radius, start)
            if (showEnd) cap(canvas, g, radius, end)
        }
    }

    private fun drawLabel(canvas: Canvas, g: HopDialGeometry, radius: Float, anchor: Long, label: HopLabelPlan) {
        text(Hop.Task, g.metrics.taskTextSizePx, taskTypeface)
        val centerAngle = g.angleAt(anchor)
        // Read clockwise above the dial and counterclockwise below it, keeping text upright.
        val direction = if (cos(centerAngle + PI / 2) >= 0) 1 else -1
        val startAngle = centerAngle - direction * label.widthPx / (2 * radius)
        val fontCenterOffset = -(p.ascent() + p.descent()) / 2f
        if (label.letterSpacingEm == 0f) {
            circleBounds(g, radius)
            arc.rewind()
            arc.addArc(bounds, degrees(startAngle), direction * degrees(label.widthPx / radius.toDouble()))
            p.textAlign = Paint.Align.LEFT
            canvas.drawTextOnPath(label.text, arc, 0f, fontCenterOffset, p)
        } else {
            var cursor = 0f
            for (glyph in label.graphemes) {
                val width = p.measureText(glyph)
                val angle = startAngle + direction * (cursor + width / 2) / radius
                val point = g.point(radius, angle)
                canvas.save()
                canvas.rotate(degrees(angle) + if (direction > 0) 90f else -90f, point.x, point.y)
                p.textAlign = Paint.Align.CENTER
                canvas.drawText(glyph, point.x, point.y + fontCenterOffset, p)
                canvas.restore()
                cursor += width + label.letterSpacingEm * g.metrics.taskTextSizePx
            }
        }
    }

    private fun drawNow(canvas: Canvas, g: HopDialGeometry, ambient: Boolean) {
        val m = g.metrics
        // Extend past both aperture intersections; the fixed circular clip leaves the rim clean.
        val start = g.point(
            m.orbitRadiusPx - if (ambient) m.diameterPx * 0.18f else m.faceRadiusPx + m.density,
            g.nowAngle,
        )
        val end = g.point(
            if (ambient) m.tickRadiusPx + m.diameterPx * 0.025f
            else m.orbitRadiusPx + m.faceRadiusPx + m.density,
            g.nowAngle,
        )
        stroke(if (ambient) Hop.AmbientInk else Hop.Now, m.density * if (ambient) 1f else 1.4f)
        canvas.drawLine(start.x, start.y, end.x, end.y, p)
        if (!ambient) {
            val label = g.point(m.orbitRadiusPx - m.diameterPx * 0.235f, g.nowAngle)
            text(Hop.Now, TPlannerTypography.HopNowSp * m.scaledDensity, taskTypeface)
            val nx = -sin(g.nowAngle).toFloat()
            val ny = cos(g.nowAngle).toFloat()
            val clearance = abs(nx) * p.measureText("NOW") / 2f +
                abs(ny) * (p.descent() - p.ascent()) / 2f + 4f * m.density
            canvas.drawText("NOW", label.x + nx * clearance,
                label.y + ny * clearance - (p.ascent() + p.descent()) / 2f, p)
        }
    }

    private fun cap(canvas: Canvas, g: HopDialGeometry, radius: Float, epochMs: Long) {
        val delta = 2f * g.metrics.density
        val angle = g.angleAt(epochMs)
        val a = g.point(radius - delta, angle)
        val b = g.point(radius + delta, angle)
        stroke(Hop.Task, g.metrics.density * 0.85f)
        canvas.drawLine(a.x, a.y, b.x, b.y, p)
    }

    private fun drawArc(canvas: Canvas, g: HopDialGeometry, radius: Float, start: Long, end: Long) {
        if (start >= end) return
        circleBounds(g, radius)
        canvas.drawArc(bounds, degrees(g.angleAt(start)), degrees(g.angleAt(end) - g.angleAt(start)), false, p)
    }

    private fun circleBounds(g: HopDialGeometry, radius: Float) {
        bounds.set(g.dialX - radius, g.dialY - radius, g.dialX + radius, g.dialY + radius)
    }

    private fun text(color: Int, size: Float, face: Typeface) {
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = 255
        p.textSize = size
        p.typeface = face
        p.textAlign = Paint.Align.CENTER
    }

    private fun stroke(color: Int, width: Float) {
        p.style = Paint.Style.STROKE
        p.color = color
        p.alpha = 255
        p.strokeWidth = width
        p.strokeCap = Paint.Cap.BUTT
    }

    private fun degrees(radians: Double): Float = (radians * 180 / PI).toFloat()
}
