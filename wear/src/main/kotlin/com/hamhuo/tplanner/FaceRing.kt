package com.hamhuo.tplanner

import android.graphics.Canvas
import android.graphics.Paint
import java.time.ZonedDateTime
import kotlin.math.cos
import kotlin.math.sin

// 时环：12h 金色进度环 + 事件刻度 + 衬线时间 + 圆钮
class FaceRing(
    context: android.content.Context,
    surfaceHolder: android.view.SurfaceHolder,
    currentUserStyleRepository: androidx.wear.watchface.style.CurrentUserStyleRepository,
    watchState: androidx.wear.watchface.WatchState,
) : FaceBase(context, surfaceHolder, currentUserStyleRepository, watchState, FaceDesign.RING) {

    override fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        val ringR = s * 0.4316f
        val boot  = bootAlpha

        p.setStroke(TRACK, s * 0.0158f)
        canvas.drawCircle(cx, cy, ringR, p)

        // 12h 进度弧：入场时从 0 生长到当前进度
        val halfDaySec = (t.hour % 12) * 3600 + t.minute * 60 + t.second
        val halfDayFrac = halfDaySec / 43200f
        p.setStroke(GOLD, s * 0.0158f, Paint.Cap.ROUND)
        canvas.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, -90f, halfDayFrac * 360f * boot, false, p)

        // 事件刻度（折叠到 12h 环上）
        for (m in marks.minutes) {
            if (m == marks.nextMinute) continue
            val a = Math.toRadians((m % 720) / 720.0 * 360.0 - 90.0)
            p.setFill(EVENT_DOT, boot)
            canvas.drawCircle(cx + ringR * cos(a).toFloat(), cy + ringR * sin(a).toFloat(), s * 0.0158f, p)
        }
        marks.nextMinute?.let { m ->
            val a = Math.toRadians((m % 720) / 720.0 * 360.0 - 90.0)
            val dx = cx + ringR * cos(a).toFloat(); val dy = cy + ringR * sin(a).toFloat()
            p.setFill(GOLD, boot); canvas.drawCircle(dx, dy, s * 0.021f, p)
            p.setStroke(GOLD, s * 0.0042f); p.alpha = (153 * boot).toInt()
            canvas.drawCircle(dx, dy, s * 0.0395f, p)
        }

        p.setText(CREAM, s * 0.158f, serif)
        canvas.drawText(timeStr(t), cx, cy - s * 0.0316f, p)
        p.setText(DIM, s * 0.0553f)
        canvas.drawText(t.format(dateFmt), cx, cy + s * 0.0737f, p)
        if (marks.nextMinute != null && marks.nextTitle != null) {
            p.setText(GOLD, s * 0.0553f)
            canvas.drawText("%02d:%02d %s".format(marks.nextMinute!! / 60, marks.nextMinute!! % 60, marks.nextTitle), cx, cy + s * 0.163f, p)
        }

    }

    override fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
        val ringR = s * 0.4316f
        p.setStroke(AMB_TRACK, s * 0.0106f)
        canvas.drawCircle(cx, cy, ringR, p)
        val halfDayMin = (t.hour % 12) * 60 + t.minute
        p.setStroke(AMB_GOLD, s * 0.0106f, Paint.Cap.ROUND)
        canvas.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, -90f, halfDayMin / 720f * 360f, false, p)
        p.setText(AMB_TEXT, s * 0.158f, serif)
        canvas.drawText(timeStr(t), cx, cy + s * 0.055f, p)
    }
}
