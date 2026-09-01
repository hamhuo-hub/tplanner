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
import kotlin.math.min

// tPlanner 表盘 Renderer 基类：统一管理动画状态、事件刻度、应用入口和 Paint。
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

    // render() 每次调用前更新，供子类 draw*() 直接读取
    @Volatile protected var now        = 0L
    @Volatile protected var bootAlpha  = 0f

    // ── 事件刻度 ────────────────────────────────────────────────────────────
    @Volatile protected var marks = WatchEventMarks.EMPTY
    @Volatile private var marksLoadedAt = 0L

    // ── 绘图资源 ────────────────────────────────────────────────────────────
    protected val p     = Paint().apply { isAntiAlias = true }
    protected val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val dateFormatter = LocalizedDateTimeFormatter(context, R.string.watchface_date_pattern)
    private val shortDateFormatter =
        LocalizedDateTimeFormatter(context, R.string.task_list_short_date_pattern)

    // ── 公共接口 ────────────────────────────────────────────────────────────

    /** Only faces with an explicit app-entry region opt into tap-to-open behavior. */
    open fun isOnAppLaunchRegion(x: Int, y: Int): Boolean = false

    /** Install the same committed projection observed by the Wear app and repaint immediately. */
    internal fun onProjectionInstalled() {
        marks = WatchEventMarks.load(context)
        marksLoadedAt = System.currentTimeMillis()
        invalidate()
    }

    // ── 主渲染入口 ──────────────────────────────────────────────────────────

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        faceW = bounds.width(); faceH = bounds.height()
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        val appDateTime = zonedDateTime.withZoneSameInstant(APP_ZONE)
        now = System.currentTimeMillis()

        if (!ambient && bootStart == 0L) bootStart = now
        bootAlpha  = easeOutCubic(((now - bootStart).coerceIn(0, BOOT_MS) / BOOT_MS.toFloat()))

        // SharedPreferences commits invalidate immediately. This slow read is only a defensive
        // fallback for process/platform edge cases, not the normal synchronization mechanism.
        if (marksLoadedAt == 0L || now < marksLoadedAt || now - marksLoadedAt >= MARKS_REFRESH_MS) {
            marksLoadedAt = now
            marks = WatchEventMarks.load(context)
        }

        val w  = faceW.toFloat(); val h = faceH.toFloat()
        p.setFill(BG); canvas.drawRect(0f, 0f, w, h, p)

        val s  = min(w, h)
        val cx = w / 2f; val cy = h / 2f

        if (ambient) drawAmbient(canvas, appDateTime, s, cx, cy)
        else         drawInteractive(canvas, appDateTime, s, cx, cy)

        // 入场动画期间请求连续帧；结束后回落低频重绘
        if (!ambient && now - bootStart < BOOT_MS) invalidate()
    }

    override fun renderHighlightLayer(
        canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets,
    ) {}

    override suspend fun createSharedAssets(): Assets = Assets()

    // ── 子类必须实现 ────────────────────────────────────────────────────────

    protected abstract fun drawInteractive(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float)
    protected abstract fun drawAmbient(canvas: Canvas, t: ZonedDateTime, s: Float, cx: Float, cy: Float)

    protected fun timeStr(t: ZonedDateTime): String =
        "${twoDigitNumber(t.hour)}:${twoDigitNumber(t.minute)}"

    protected fun twoDigitNumber(value: Int): String =
        String.format(context.currentWatchLocale(), "%02d", value)

    protected fun dateStr(t: ZonedDateTime): String = dateFormatter.format(t)

    protected fun shortDateStr(t: ZonedDateTime): String = shortDateFormatter.format(t)

    protected fun localizedText(resource: Int, vararg arguments: Any): String =
        context.getString(resource, *arguments)

    class Assets : androidx.wear.watchface.Renderer.SharedAssets {
        override fun onDestroy() {}
    }

    private companion object {
        const val MARKS_REFRESH_MS = 60_000L
    }
}
