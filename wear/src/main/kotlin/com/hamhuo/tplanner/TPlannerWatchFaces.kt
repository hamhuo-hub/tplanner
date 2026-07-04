package com.hamhuo.tplanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════════════════════
// tPlanner 三款表盘：时环（Ring）· 星轨（Orbit）· 余烬（Ember）。
// 设计语言与桌面端一致：暗底 #0D0D0D、金 #C9A84C、米白衬线数字、青色事件点。
// 点击表盘下方的金色按钮/短划 → 震动并经典蓝牙唤醒手机（PhoneWaker）。
//
// 动画均为事件驱动：入场 800ms、点按涟漪/光晕 600-800ms，动画期间通过
// invalidate() 请求连续帧；平时按各自的 interactiveDrawModeUpdateDelayMillis
// 低频重绘（余烬因呼吸光环用 100ms，其余 1000ms）。息屏（ambient）下只画
// 暗化的极简内容，无动画、无大面积亮色（防烧屏 + 省电）。
// ═══════════════════════════════════════════════════════════════════════════

enum class FaceDesign(val interactiveDelayMs: Long) {
    RING(1000L),
    ORBIT(1000L),
    EMBER(100L),
}

// 事件刻度数据：由手机端将当日事件写入（分钟数 0-1439 + 下一个事件）。
// 手表侧暂无同步通道时为空——表盘退化为纯时间显示，不画假数据。
object WatchEventMarks {
    data class Marks(val minutes: List<Int>, val nextMinute: Int?, val nextTitle: String?)

    val EMPTY = Marks(emptyList(), null, null)

    fun load(context: Context): Marks = try {
        val raw = context.getSharedPreferences("tplanner_watch_marks", Context.MODE_PRIVATE)
            .getString("marks_json", null)
        if (raw == null) EMPTY else {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("minutes") ?: JSONArray()
            val minutes = (0 until arr.length()).map { arr.getInt(it) }.filter { it in 0..1439 }
            val next = obj.optJSONObject("next")
            Marks(
                minutes,
                next?.optInt("minute", -1)?.takeIf { it in 0..1439 },
                next?.optString("title")?.takeIf { it.isNotBlank() },
            )
        }
    } catch (_: Exception) { EMPTY }
}

abstract class TPlannerFaceService : WatchFaceService() {

    protected abstract val design: FaceDesign

    private val vibrator: Vibrator by lazy { getSystemService(Vibrator::class.java) }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = FaceRenderer(applicationContext, surfaceHolder, currentUserStyleRepository, watchState, design)
        return WatchFace(WatchFaceType.DIGITAL, renderer)
            .setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
                    if (tapType != TapType.UP) return
                    if (renderer.isOnWakeButton(tapEvent.xPos, tapEvent.yPos)) {
                        vibrator.cancel()
                        vibrator.vibrate(VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE))
                        renderer.startTapAnimation()
                        PhoneWaker.wakeUpPhone(applicationContext)
                    }
                }
            })
    }

    private class Assets : androidx.wear.watchface.Renderer.SharedAssets {
        override fun onDestroy() {}
    }

    private class FaceRenderer(
        private val context: Context,
        surfaceHolder: SurfaceHolder,
        currentUserStyleRepository: CurrentUserStyleRepository,
        watchState: WatchState,
        private val design: FaceDesign,
    ) : androidx.wear.watchface.Renderer.CanvasRenderer2<Assets>(
        surfaceHolder, currentUserStyleRepository, watchState,
        CanvasType.HARDWARE, design.interactiveDelayMs, false,
    ) {
        @Volatile private var faceW = 0
        @Volatile private var faceH = 0

        // ── 动画时钟 ────────────────────────────────────────────────────────
        // bootStart：第一次以交互模式渲染的时刻（入场动画基准）。
        // tapStart：最近一次点按唤醒（涟漪/光晕爆发基准）。
        @Volatile private var bootStart = 0L
        @Volatile private var tapStart = 0L

        // 事件刻度缓存，每分钟重读一次（数据由手机端写入，频率极低）
        private var marks = WatchEventMarks.EMPTY
        private var marksLoadedMinute = -1L

        private val p = Paint().apply { isAntiAlias = true }
        private val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        private val dateFmt = DateTimeFormatter.ofPattern("M月d日 · EEE", Locale.CHINA)

        fun startTapAnimation() {
            tapStart = System.currentTimeMillis()
            postInvalidate()
        }

        // 点按热区：时环/星轨为底部圆钮，余烬为底部短划——半径放大到 0.11s
        // 便于盲按（视觉元素本身较小）。
        fun isOnWakeButton(x: Int, y: Int): Boolean {
            if (faceW == 0) return false
            val s = min(faceW, faceH).toFloat()
            val cx = faceW / 2f
            val cy = faceH / 2f + s * design.buttonYFrac()
            val dx = x - cx; val dy = y - cy
            return sqrt((dx * dx + dy * dy).toDouble()) <= s * 0.11f
        }

        private fun FaceDesign.buttonYFrac() = when (this) {
            FaceDesign.RING, FaceDesign.ORBIT -> 0.326f
            FaceDesign.EMBER -> 0.395f
        }

        override suspend fun createSharedAssets(): Assets = Assets()

        override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
            faceW = bounds.width(); faceH = bounds.height()
            val w = faceW.toFloat(); val h = faceH.toFloat()
            val s = min(w, h)
            val cx = w / 2f; val cy = h / 2f
            val ambient = renderParameters.drawMode == DrawMode.AMBIENT
            val now = System.currentTimeMillis()

            if (!ambient && bootStart == 0L) bootStart = now

            // 每分钟重读一次事件刻度
            val minuteStamp = now / 60_000L
            if (minuteStamp != marksLoadedMinute) {
                marksLoadedMinute = minuteStamp
                marks = WatchEventMarks.load(context)
            }

            fill(BG); canvas.drawRect(0f, 0f, w, h, p)

            when (design) {
                FaceDesign.RING -> if (ambient) renderRingAmbient(canvas, zonedDateTime, s, cx, cy)
                                   else renderRing(canvas, zonedDateTime, s, cx, cy, now)
                FaceDesign.ORBIT -> if (ambient) renderOrbitAmbient(canvas, zonedDateTime, s, cx, cy)
                                    else renderOrbit(canvas, zonedDateTime, s, cx, cy, now)
                FaceDesign.EMBER -> if (ambient) renderEmberAmbient(canvas, zonedDateTime, s, cx, cy)
                                    else renderEmber(canvas, zonedDateTime, s, cx, cy, now)
            }

            // 入场/点按动画期间请求连续帧；结束后回落到低频重绘
            if (!ambient && (now - bootStart < BOOT_MS || now - tapStart < TAP_MS)) invalidate()
        }

        override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {}

        // ═══════════════════════════════════════════════════════════════════
        // 方案 A · 时环：24h 金色进度环 + 事件刻度 + 衬线时间 + 圆钮
        // ═══════════════════════════════════════════════════════════════════
        private fun renderRing(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float, now: Long) {
            val ringR = s * 0.4316f
            val boot = easeOutCubic(((now - bootStart).coerceIn(0, BOOT_MS) / BOOT_MS.toFloat()))

            stroke(TRACK, s * 0.0158f)
            canvas.drawCircle(cx, cy, ringR, p)

            // 今日进度弧：入场时从 0 生长到当前进度
            val dayFrac = (t.hour * 3600 + t.minute * 60 + t.second) / 86400f
            stroke(GOLD, s * 0.0158f, Paint.Cap.ROUND)
            canvas.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, -90f, dayFrac * 360f * boot, false, p)

            // 事件刻度：普通事件青点，下一个事件金点 + 光环
            for (m in marks.minutes) {
                if (m == marks.nextMinute) continue
                val a = Math.toRadians(m / 1440.0 * 360.0 - 90.0)
                fill(TEAL, boot)
                canvas.drawCircle(cx + ringR * cos(a).toFloat(), cy + ringR * sin(a).toFloat(), s * 0.0158f, p)
            }
            marks.nextMinute?.let { m ->
                val a = Math.toRadians(m / 1440.0 * 360.0 - 90.0)
                val dx = cx + ringR * cos(a).toFloat(); val dy = cy + ringR * sin(a).toFloat()
                fill(GOLD, boot); canvas.drawCircle(dx, dy, s * 0.021f, p)
                stroke(GOLD, s * 0.0042f); p.alpha = (153 * boot).toInt()
                canvas.drawCircle(dx, dy, s * 0.0395f, p)
            }

            text(CREAM, s * 0.158f, serif)
            canvas.drawText(timeStr(t), cx, cy - s * 0.0316f, p)
            text(DIM, s * 0.0553f)
            canvas.drawText(t.format(dateFmt), cx, cy + s * 0.0737f, p)
            if (marks.nextMinute != null && marks.nextTitle != null) {
                text(GOLD, s * 0.0553f)
                canvas.drawText("%02d:%02d %s".format(marks.nextMinute!! / 60, marks.nextMinute!! % 60, marks.nextTitle), cx, cy + s * 0.163f, p)
            }

            drawWakeButton(canvas, s, cx, cy + s * 0.326f, now)
        }

        private fun renderRingAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
            val ringR = s * 0.4316f
            stroke(AMB_TRACK, s * 0.0106f)
            canvas.drawCircle(cx, cy, ringR, p)
            val dayFrac = (t.hour * 60 + t.minute) / 1440f
            stroke(AMB_GOLD, s * 0.0106f, Paint.Cap.ROUND)
            canvas.drawArc(cx - ringR, cy - ringR, cx + ringR, cy + ringR, -90f, dayFrac * 360f, false, p)
            text(AMB_TEXT, s * 0.158f, serif)
            canvas.drawText(timeStr(t), cx, cy + s * 0.055f, p)
        }

        // ═══════════════════════════════════════════════════════════════════
        // 方案 B · 星轨：事件星座 + 虚线连线 + 单针 24 时 + 小字时间
        // ═══════════════════════════════════════════════════════════════════
        private fun renderOrbit(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float, now: Long) {
            val boot = easeOutCubic(((now - bootStart).coerceIn(0, BOOT_MS) / BOOT_MS.toFloat()))

            // 8 个方位刻度
            stroke(TICK, s * 0.0053f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0 - 90.0)
                canvas.drawLine(
                    cx + s * 0.4526f * cos(a).toFloat(), cy + s * 0.4526f * sin(a).toFloat(),
                    cx + s * 0.4842f * cos(a).toFloat(), cy + s * 0.4842f * sin(a).toFloat(), p,
                )
            }

            // 事件星座：按时间顺序虚线连线，星点入场时渐次点亮
            val orbitR = s * 0.4105f
            val ordered = marks.minutes.sorted()
            if (ordered.size >= 2) {
                stroke(LINE, s * 0.004f)
                p.pathEffect = DashPathEffect(floatArrayOf(s * 0.0158f, s * 0.0158f), 0f)
                p.alpha = (255 * boot).toInt()
                for (i in 0 until ordered.size - 1) {
                    val a1 = Math.toRadians(ordered[i] / 1440.0 * 360.0 - 90.0)
                    val a2 = Math.toRadians(ordered[i + 1] / 1440.0 * 360.0 - 90.0)
                    canvas.drawLine(
                        cx + orbitR * cos(a1).toFloat(), cy + orbitR * sin(a1).toFloat(),
                        cx + orbitR * cos(a2).toFloat(), cy + orbitR * sin(a2).toFloat(), p,
                    )
                }
                p.pathEffect = null
            }
            ordered.forEachIndexed { i, m ->
                val a = Math.toRadians(m / 1440.0 * 360.0 - 90.0)
                val stagger = (((now - bootStart) - i * 100L).coerceIn(0, 300) / 300f)
                val isNext = m == marks.nextMinute
                fill(if (isNext) GOLD else TEAL, stagger)
                canvas.drawCircle(
                    cx + orbitR * cos(a).toFloat(), cy + orbitR * sin(a).toFloat(),
                    s * (if (isNext) 0.021f else 0.0158f), p,
                )
            }

            // 单针 24 时：入场时从 0 点扫到当前时刻
            val dayFrac = (t.hour * 3600 + t.minute * 60 + t.second) / 86400f
            val handA = Math.toRadians(dayFrac * 360.0 * boot - 90.0)
            stroke(GOLD, s * 0.0079f, Paint.Cap.ROUND)
            canvas.drawLine(cx, cy, cx + s * 0.3684f * cos(handA).toFloat(), cy + s * 0.3684f * sin(handA).toFloat(), p)
            fill(GOLD); canvas.drawCircle(cx, cy, s * 0.0158f, p)

            text(DIM, s * 0.05f)
            canvas.drawText(t.format(dateFmt), cx, cy - s * 0.1895f, p)
            text(CREAM, s * 0.105f, serif)
            canvas.drawText(timeStr(t), cx, cy - s * 0.0947f, p)

            drawWakeButton(canvas, s, cx, cy + s * 0.326f, now)
        }

        private fun renderOrbitAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
            stroke(AMB_TRACK, s * 0.0053f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0 - 90.0)
                canvas.drawLine(
                    cx + s * 0.4526f * cos(a).toFloat(), cy + s * 0.4526f * sin(a).toFloat(),
                    cx + s * 0.4842f * cos(a).toFloat(), cy + s * 0.4842f * sin(a).toFloat(), p,
                )
            }
            val dayFrac = (t.hour * 60 + t.minute) / 1440f
            val handA = Math.toRadians(dayFrac * 360.0 - 90.0)
            stroke(AMB_GOLD, s * 0.0079f, Paint.Cap.ROUND)
            canvas.drawLine(cx, cy, cx + s * 0.3684f * cos(handA).toFloat(), cy + s * 0.3684f * sin(handA).toFloat(), p)
            text(AMB_TEXT, s * 0.105f, serif)
            canvas.drawText(timeStr(t), cx, cy - s * 0.0947f, p)
        }

        // ═══════════════════════════════════════════════════════════════════
        // 方案 C · 余烬：纯排印时分堆叠 + 呼吸光环 + 底部短划
        // ═══════════════════════════════════════════════════════════════════
        private fun renderEmber(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float, now: Long) {
            // 呼吸：4 秒正弦周期；点按后光晕爆发并在 800ms 内衰减回落
            val breath = 0.7f + 0.6f * (0.5f + 0.5f * sin(2.0 * PI * (now % 4000L) / 4000.0).toFloat())
            val flare = 1f + 2.5f * (1f - ((now - tapStart).coerceIn(0, TAP_MS) / TAP_MS.toFloat()))
            val glow = breath * (if (now - tapStart < TAP_MS) flare else 1f)

            val hcy = cy - s * 0.0105f
            stroke(GOLD, s * 0.0421f); p.alpha = (255 * 0.08f * glow).toInt().coerceAtMost(255)
            canvas.drawCircle(cx, hcy, s * 0.2947f, p)
            stroke(GOLD, s * 0.0263f); p.alpha = (255 * 0.12f * glow).toInt().coerceAtMost(255)
            canvas.drawCircle(cx, hcy, s * 0.2526f, p)

            text(CREAM, s * 0.221f, serif)
            canvas.drawText("%02d".format(t.hour), cx, cy - s * 0.0632f, p)
            stroke(GOLD, s * 0.0053f)
            canvas.drawLine(cx - s * 0.1158f, cy - s * 0.0053f, cx + s * 0.1158f, cy - s * 0.0053f, p)
            text(GOLD, s * 0.221f, serif)
            canvas.drawText("%02d".format(t.minute), cx, cy + s * 0.2f, p)
            text(DIM, s * 0.0526f)
            canvas.drawText(t.format(dateFmt), cx, cy + s * 0.3053f, p)

            // 底部短划 = 唤醒热区的视觉锚点
            stroke(GOLD, s * 0.0079f, Paint.Cap.ROUND)
            canvas.drawLine(cx - s * 0.0632f, cy + s * 0.395f, cx + s * 0.0632f, cy + s * 0.395f, p)
        }

        private fun renderEmberAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float) {
            text(AMB_TEXT, s * 0.221f, serif)
            canvas.drawText("%02d".format(t.hour), cx, cy - s * 0.0632f, p)
            stroke(AMB_GOLD, s * 0.0053f)
            canvas.drawLine(cx - s * 0.1158f, cy - s * 0.0053f, cx + s * 0.1158f, cy - s * 0.0053f, p)
            text(AMB_TEXT, s * 0.221f, serif)
            canvas.drawText("%02d".format(t.minute), cx, cy + s * 0.2f, p)
        }

        // ── 共用元素 ────────────────────────────────────────────────────────
        // 圆钮 + 点按涟漪（时环/星轨）：涟漪从钮心扩散 600ms，按钮同时轻微放大回弹
        private fun drawWakeButton(canvas: Canvas, s: Float, bx: Float, by: Float, now: Long) {
            val tapElapsed = now - tapStart
            if (tapElapsed in 0 until TAP_MS) {
                val q = tapElapsed / TAP_MS.toFloat()
                stroke(GOLD, s * 0.006f)
                p.alpha = (255 * 0.5f * (1f - q)).toInt()
                canvas.drawCircle(bx, by, s * (0.07f + 0.30f * q), p)
            }
            val pop = if (tapElapsed in 0 until 300)
                1f + 0.25f * sin(PI * (tapElapsed / 300f)).toFloat() else 1f
            fill(BTN_FILL); canvas.drawCircle(bx, by, s * 0.0632f * pop, p)
            stroke(GOLD, s * 0.0063f); canvas.drawCircle(bx, by, s * 0.0632f * pop, p)
            fill(GOLD); canvas.drawCircle(bx, by, s * 0.021f * pop, p)
        }

        private fun timeStr(t: ZonedDateTime) = "%02d:%02d".format(t.hour, t.minute)

        private fun easeOutCubic(x: Float): Float { val v = 1f - x; return 1f - v * v * v }

        // ── Paint 助手 ──────────────────────────────────────────────────────
        private fun fill(c: Int, alpha: Float = 1f) {
            p.pathEffect = null; p.typeface = Typeface.DEFAULT
            p.style = Paint.Style.FILL; p.color = c; p.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        }
        private fun stroke(c: Int, w: Float, cap: Paint.Cap = Paint.Cap.BUTT) {
            p.pathEffect = null; p.typeface = Typeface.DEFAULT
            p.style = Paint.Style.STROKE; p.color = c; p.strokeWidth = w; p.strokeCap = cap
        }
        private fun text(c: Int, size: Float, tf: Typeface = Typeface.DEFAULT) {
            p.pathEffect = null; p.style = Paint.Style.FILL; p.color = c
            p.textSize = size; p.textAlign = Paint.Align.CENTER; p.typeface = tf
        }

        companion object {
            private const val BOOT_MS = 800L
            private const val TAP_MS = 600L

            private const val BG = 0xFF0D0D0D.toInt()
            private const val GOLD = 0xFFC9A84C.toInt()
            private const val CREAM = 0xFFE8E0D0.toInt()
            private const val DIM = 0xFF857F6E.toInt()
            private const val TEAL = 0xFF4A9DA8.toInt()
            private const val TRACK = 0xFF232323.toInt()
            private const val TICK = 0xFF2E2E2E.toInt()
            private const val LINE = 0xFF3A362B.toInt()
            private const val BTN_FILL = 0xFF161410.toInt()
            private const val AMB_TEXT = 0xFF8A857A.toInt()
            private const val AMB_GOLD = 0xFF55503F.toInt()
            private const val AMB_TRACK = 0xFF1A1A1A.toInt()
        }
    }
}

class WatchFaceRingService : TPlannerFaceService() { override val design = FaceDesign.RING }
class WatchFaceOrbitService : TPlannerFaceService() { override val design = FaceDesign.ORBIT }
class WatchFaceEmberService : TPlannerFaceService() { override val design = FaceDesign.EMBER }
