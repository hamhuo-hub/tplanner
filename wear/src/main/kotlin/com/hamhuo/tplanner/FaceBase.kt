package com.hamhuo.tplanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// 三款表盘的共享 Renderer 基类：动画状态、事件刻度加载、唤醒按钮、Paint 快捷方式
// 均在此处统一管理。子类只需实现 drawInteractive / drawAmbient 两个方法。
abstract class FaceBase(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    currentUserStyleRepository: CurrentUserStyleRepository,
    watchState: WatchState,
    protected val design: FaceDesign,
) : androidx.wear.watchface.Renderer.CanvasRenderer2<FaceBase.Assets>(
    surfaceHolder, currentUserStyleRepository, watchState,
    CanvasType.HARDWARE, design.interactiveDelayMs, false,
) {
    // ── 尺寸 ────────────────────────────────────────────────────────────────
    @Volatile protected var faceW = 0
    @Volatile protected var faceH = 0

    // ── 动画时钟 ────────────────────────────────────────────────────────────
    @Volatile protected var bootStart = 0L
    @Volatile protected var tapStart  = 0L

    // render() 每次调用前更新，供子类 draw*() 直接读取
    @Volatile protected var now        = 0L
    @Volatile protected var bootAlpha  = 0f
    @Volatile protected var tapElapsed = 0L

    // ── 事件刻度 ────────────────────────────────────────────────────────────
    protected var marks = WatchEventMarks.EMPTY
    private var marksLoadedMinute = -1L

    // ── 绘图资源 ────────────────────────────────────────────────────────────
    protected val p     = Paint().apply { isAntiAlias = true }
    protected val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    protected val dateFmt = DateTimeFormatter.ofPattern("M月d日 · EEE", Locale.CHINA)

    // ── 公共接口 ────────────────────────────────────────────────────────────

    fun startTapAnimation() {
        tapStart = System.currentTimeMillis()
        postInvalidate()
    }

    fun isOnWakeButton(x: Int, y: Int): Boolean {
        if (faceW == 0) return false
        val s  = min(faceW, faceH).toFloat()
        val cx = faceW / 2f
        val cy = faceH / 2f + s * design.buttonYFrac()
        val dx = x - cx; val dy = y - cy
        return sqrt((dx * dx + dy * dy).toDouble()) <= s * 0.11f
    }

    private fun FaceDesign.buttonYFrac() = when (this) {
        FaceDesign.RING, FaceDesign.ORBIT -> 0.326f
        FaceDesign.EMBER, FaceDesign.TIDE, FaceDesign.PULSE, FaceDesign.MOON -> 0.395f
        FaceDesign.LUMINA -> 0.437f
    }

    // ── 主渲染入口 ──────────────────────────────────────────────────────────

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        faceW = bounds.width(); faceH = bounds.height()
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        now = System.currentTimeMillis()

        if (!ambient && bootStart == 0L) bootStart = now
        bootAlpha  = easeOutCubic(((now - bootStart).coerceIn(0, BOOT_MS) / BOOT_MS.toFloat()))
        tapElapsed = now - tapStart

        // 每分钟重读一次事件刻度
        val minuteStamp = now / 60_000L
        if (minuteStamp != marksLoadedMinute) {
            marksLoadedMinute = minuteStamp
            marks = WatchEventMarks.load(context)
        }

        val w  = faceW.toFloat(); val h = faceH.toFloat()
        p.setFill(BG); canvas.drawRect(0f, 0f, w, h, p)

        val s  = min(w, h)
        val cx = w / 2f; val cy = h / 2f

        if (ambient) drawAmbient(canvas, zonedDateTime, s, cx, cy)
        else         drawInteractive(canvas, zonedDateTime, s, cx, cy)

        // 入场/点按动画期间请求连续帧；结束后回落低频重绘
        if (!ambient && (now - bootStart < BOOT_MS || now - tapStart < TAP_MS)) invalidate()
    }

    override fun renderHighlightLayer(
        canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets,
    ) {}

    override suspend fun createSharedAssets(): Assets = Assets()

    // ── 子类必须实现 ────────────────────────────────────────────────────────

    protected abstract fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float)
    protected abstract fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float)

    // ── 共用绘制元素 ────────────────────────────────────────────────────────

    // 圆钮 + 点按涟漪（时环/星轨共用）
    protected fun drawWakeButton(canvas: Canvas, s: Float, bx: Float, by: Float) {
        if (tapElapsed in 0 until TAP_MS) {
            val q = tapElapsed / TAP_MS.toFloat()
            p.setStroke(GOLD, s * 0.006f)
            p.alpha = (255 * 0.5f * (1f - q)).toInt()
            canvas.drawCircle(bx, by, s * (0.07f + 0.30f * q), p)
        }
        val pop = if (tapElapsed in 0 until 300)
            1f + 0.25f * sin(PI * (tapElapsed / 300f)).toFloat() else 1f
        p.setFill(BTN_FILL);   canvas.drawCircle(bx, by, s * 0.0632f * pop, p)
        p.setStroke(GOLD, s * 0.0063f); canvas.drawCircle(bx, by, s * 0.0632f * pop, p)
        p.setFill(GOLD);       canvas.drawCircle(bx, by, s * 0.021f * pop, p)
    }

    protected fun timeStr(t: ZonedDateTime) = "%02d:%02d".format(t.hour, t.minute)

    class Assets : androidx.wear.watchface.Renderer.SharedAssets {
        override fun onDestroy() {}
    }
}
