package com.tplanner.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Bundle
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.view.SurfaceHolder
import java.text.SimpleDateFormat
import java.util.*

/**
 * tPlanner Watch Face — Soviet Constructivist Style
 *
 * Design language:
 *  - Deep black background
 *  - Large Oswald/bold condensed time (top-left aligned, constructivist grid)
 *  - Gold (#C9A84C) accent bar and current event
 *  - Uppercase date in small caps
 *  - Task progress arc (bottom-right)
 */
class TplannerWatchFace : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine = WatchEngine()

    inner class WatchEngine : CanvasWatchFaceService.Engine() {

        private val calendar = Calendar.getInstance()

        // ── Paints ──────────────────────────────────────────────────────────
        private val bgPaint = Paint().apply { color = Color.parseColor("#0A0A0A") }

        /** Bold condensed time — mimics Oswald. Falls back to DEFAULT_BOLD. */
        private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("oswald", Typeface.BOLD)
                .takeIf { it != Typeface.DEFAULT } ?: Typeface.DEFAULT_BOLD
            color    = Color.WHITE
            textAlign = Paint.Align.LEFT
        }

        private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("oswald", Typeface.NORMAL)
                .takeIf { it != Typeface.DEFAULT } ?: Typeface.DEFAULT
            color    = Color.parseColor("#9A9080")
            letterSpacing = 0.14f
            textAlign = Paint.Align.LEFT
        }

        private val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color  = Color.parseColor("#C9A84C")
            style  = Paint.Style.FILL
        }

        private val eventPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("oswald", Typeface.NORMAL)
                .takeIf { it != Typeface.DEFAULT } ?: Typeface.DEFAULT
            color    = Color.parseColor("#C9A84C")
            textAlign = Paint.Align.LEFT
        }

        private val eventSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("oswald", Typeface.NORMAL)
                .takeIf { it != Typeface.DEFAULT } ?: Typeface.DEFAULT
            color    = Color.parseColor("#7A7163")
            letterSpacing = 0.06f
            textAlign = Paint.Align.LEFT
        }

        private val arcBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color  = Color.parseColor("#2A2A2A")
            style  = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap   = Paint.Cap.ROUND
        }

        private val arcFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color  = Color.parseColor("#4A7C59")
            style  = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap   = Paint.Cap.ROUND
        }

        // ── Ambient mode dim variants ──────────────────────────────────────
        private var isAmbient = false

        // ── Time receiver ──────────────────────────────────────────────────
        private val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) { invalidate() }
        }
        private var registeredReceiver = false

        // ── Lifecycle ──────────────────────────────────────────────────────
        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setWatchFaceStyle(WatchFaceStyle.Builder(this@TplannerWatchFace)
                .setAcceptsTapEvents(false)
                .build())
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                if (!registeredReceiver) {
                    registerReceiver(timeReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
                    registeredReceiver = true
                }
                calendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                if (registeredReceiver) {
                    unregisterReceiver(timeReceiver)
                    registeredReceiver = false
                }
            }
        }

        override fun onAmbientModeChanged(inAmbient: Boolean) {
            super.onAmbientModeChanged(inAmbient)
            isAmbient = inAmbient
            invalidate()
        }

        override fun onPropertiesChanged(props: Bundle) {
            super.onPropertiesChanged(props)
        }

        // ── Draw ───────────────────────────────────────────────────────────
        override fun onDraw(canvas: Canvas, bounds: Rect) {
            calendar.timeInMillis = System.currentTimeMillis()
            val W = bounds.width().toFloat()
            val H = bounds.height().toFloat()

            // Background
            canvas.drawRect(bounds, bgPaint)

            val pad   = W * 0.10f
            val padR  = W * 0.12f

            // ── Gold accent bar (left edge) ────────────────────────────────
            if (!isAmbient) {
                canvas.drawRect(pad * 0.3f, H * 0.12f, pad * 0.55f, H * 0.88f, goldPaint)
            }

            // ── Time (HH:MM) ──────────────────────────────────────────────
            val hour = String.format("%02d", calendar.get(Calendar.HOUR_OF_DAY))
            val min  = String.format("%02d", calendar.get(Calendar.MINUTE))

            timePaint.textSize = W * 0.30f
            timePaint.color = if (isAmbient) Color.GRAY else Color.WHITE
            val timeX = pad * 0.85f
            canvas.drawText(hour, timeX, H * 0.38f, timePaint)

            // Separator dot between HH and MM — Soviet typographic colon
            if (!isAmbient) {
                val dotX = timeX + timePaint.measureText(hour) + W * 0.012f
                canvas.drawCircle(dotX, H * 0.24f, W * 0.018f, goldPaint)
                canvas.drawCircle(dotX, H * 0.30f, W * 0.018f, goldPaint)
            }

            canvas.drawText(min,  timeX, H * 0.62f, timePaint)

            // ── Date ──────────────────────────────────────────────────────
            datePaint.textSize = W * 0.075f
            datePaint.color    = if (isAmbient) Color.DKGRAY else Color.parseColor("#9A9080")
            val dateFmt = SimpleDateFormat("MMM d  E", Locale.ENGLISH).format(calendar.time)
                .uppercase()
            canvas.drawText(dateFmt, timeX, H * 0.73f, datePaint)

            if (!isAmbient) {
                // ── Current / next event ──────────────────────────────────
                val events  = loadEvents()
                val todays  = events.todayActive()
                val focus   = todays.currentEvent() ?: todays.nextEvent()
                val taskDone  = todays.count { it.type == "task" && it.completed }
                val taskTotal = todays.count { it.type == "task" }

                if (focus != null) {
                    eventPaint.textSize = W * 0.062f
                    val title = focus.title.take(16)
                    canvas.drawText(title, timeX, H * 0.82f, eventPaint)

                    val isCurrent = todays.currentEvent() != null
                    eventSubPaint.textSize = W * 0.048f
                    canvas.drawText(
                        if (isCurrent) "NOW" else "NEXT",
                        timeX, H * 0.89f, eventSubPaint
                    )
                }

                // ── Task arc (bottom-right corner) ────────────────────────
                if (taskTotal > 0) {
                    val arcSize = W * 0.22f
                    val arcRect = RectF(W - pad - arcSize, H - pad - arcSize,
                                        W - pad,            H - pad)
                    canvas.drawArc(arcRect, 135f, 270f, false, arcBgPaint)
                    val sweep = 270f * taskDone / taskTotal
                    canvas.drawArc(arcRect, 135f, sweep, false, arcFgPaint)

                    datePaint.textSize  = W * 0.065f
                    datePaint.textAlign = Paint.Align.CENTER
                    datePaint.color     = Color.parseColor("#7EC897")
                    canvas.drawText("$taskDone/$taskTotal",
                        arcRect.centerX(), arcRect.centerY() + datePaint.textSize * 0.35f,
                        datePaint)
                    datePaint.textAlign = Paint.Align.LEFT
                    datePaint.color     = Color.parseColor("#9A9080")
                }
            }
        }
    }
}
