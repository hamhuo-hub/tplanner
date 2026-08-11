package com.hamhuo.tplanner

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class WatchListFilter(val key: String) {
    INBOX("inbox"),
    TODAY("today");

    companion object {
        fun fromKey(key: String?): WatchListFilter =
            entries.firstOrNull { it.key == key } ?: INBOX
    }
}

internal fun Context.watchListName(filter: WatchListFilter): String = getString(
    when (filter) {
        WatchListFilter.INBOX -> R.string.task_list_filter_inbox
        WatchListFilter.TODAY -> R.string.task_list_filter_today
    },
)

/**
 * A non-interactive copy of the scrolling dashboard content, clipped to the compact header.
 * The collapsed title is a separate sibling drawn above this view, so it stays sharp.
 */
@Suppress("DEPRECATION")
private class FrostedHeaderView(
    context: Context,
    private val source: View,
) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackScale = 0.25f
    private var sourceBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var renderScript: RenderScript? = null
    private var blurScript: ScriptIntrinsicBlur? = null
    private var inputAllocation: Allocation? = null
    private var outputAllocation: Allocation? = null
    private var fallbackBlurUnavailable = false

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(
                RenderEffect.createBlurEffect(
                    dp(BLUR_RADIUS_DP).toFloat(),
                    dp(BLUR_RADIUS_DP).toFloat(),
                    Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    fun refresh() {
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val checkpoint = canvas.save()
        canvas.clipRect(0, 0, width, height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            source.draw(canvas)
        } else {
            if (!drawFallbackBlur(canvas)) {
                source.draw(canvas)
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
        canvas.restoreToCount(checkpoint)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        tintPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            dp(TINT_FADE_HEIGHT_DP).coerceAtMost(height).toFloat(),
            intArrayOf(TINT_TOP, TINT_MIDDLE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        releaseFallbackBuffers()
    }

    override fun onDetachedFromWindow() {
        releaseFallbackBuffers()
        blurScript?.destroy()
        blurScript = null
        renderScript?.destroy()
        renderScript = null
        super.onDetachedFromWindow()
    }

    private fun drawFallbackBlur(canvas: Canvas): Boolean {
        if (fallbackBlurUnavailable) return false
        return try {
            ensureFallbackBuffers()
            val input = sourceBitmap ?: return false
            val output = blurredBitmap ?: return false
            val inputAllocation = inputAllocation ?: return false
            val outputAllocation = outputAllocation ?: return false
            val blurScript = blurScript ?: return false
            val inputCanvas = Canvas(input)
            inputCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            inputCanvas.scale(fallbackScale, fallbackScale)
            source.draw(inputCanvas)

            inputAllocation.copyFrom(input)
            blurScript.setInput(inputAllocation)
            blurScript.forEach(outputAllocation)
            outputAllocation.copyTo(output)

            val checkpoint = canvas.save()
            canvas.scale(width / output.width.toFloat(), height / output.height.toFloat())
            canvas.drawBitmap(output, 0f, 0f, bitmapPaint)
            canvas.restoreToCount(checkpoint)
            true
        } catch (_: RuntimeException) {
            disableFallbackBlur()
            false
        } catch (_: LinkageError) {
            disableFallbackBlur()
            false
        }
    }

    private fun ensureFallbackBuffers() {
        val bitmapWidth = (width * fallbackScale).toInt().coerceAtLeast(1)
        val bitmapHeight = (height * fallbackScale).toInt().coerceAtLeast(1)
        if (sourceBitmap?.width == bitmapWidth && sourceBitmap?.height == bitmapHeight) return

        releaseFallbackBuffers()
        sourceBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        blurredBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

        val rs = renderScript ?: RenderScript.create(context.applicationContext).also {
            renderScript = it
        }
        blurScript = blurScript ?: ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)).apply {
            setRadius((dp(BLUR_RADIUS_DP) * fallbackScale).coerceIn(0.1f, 25f))
        }
        val input = Allocation.createFromBitmap(
            rs,
            sourceBitmap,
            Allocation.MipmapControl.MIPMAP_NONE,
            Allocation.USAGE_SCRIPT,
        )
        inputAllocation = input
        outputAllocation = Allocation.createTyped(rs, input.type)
    }

    private fun releaseFallbackBuffers() {
        inputAllocation?.destroy()
        inputAllocation = null
        outputAllocation?.destroy()
        outputAllocation = null
        sourceBitmap?.recycle()
        sourceBitmap = null
        blurredBitmap?.recycle()
        blurredBitmap = null
    }

    private fun disableFallbackBlur() {
        fallbackBlurUnavailable = true
        releaseFallbackBuffers()
        blurScript?.destroy()
        blurScript = null
        renderScript?.destroy()
        renderScript = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val BLUR_RADIUS_DP = 7
        const val TINT_FADE_HEIGHT_DP = 54
        const val TINT_TOP = 0x24000000
        const val TINT_MIDDLE = 0x16000000
    }
}

/** Masks the fixed blur into the scrolling content instead of ending on a hard edge. */
private class FrostedHeaderClipView(context: Context) : FrameLayout(context) {
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    init {
        clipChildren = true
        clipToPadding = true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        fadePaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(
                Color.WHITE,
                0xE6FFFFFF.toInt(),
                0x66FFFFFF,
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.35f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (width == 0 || height == 0) {
            super.dispatchDraw(canvas)
            return
        }
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
        canvas.restoreToCount(layer)
    }
}

/** The launcher screen only. Secondary destinations are independent Wear OS activities. */
class NextDashboardView(context: Context) : FrameLayout(context) {
    private val contentHost = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
    }
    private val frostedHeader = FrostedHeaderView(context, contentHost)
    private val frostedHeaderClip = FrostedHeaderClipView(context)
    private val collapsedTitle = textView(15f, ACCENT, MEDIUM)
    private val newButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_add_rounded_24)
        imageTintList = ColorStateList.valueOf(Color.BLACK)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rippleRounded(ACCENT, NEW_BUTTON_RIPPLE, dp(NEW_BUTTON_SIZE_DP / 2).toFloat())
        contentDescription = context.getString(R.string.task_list_new)
        isClickable = true
        isFocusable = true
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val shortDateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private var marks = WatchEventMarks.EMPTY
    private var selectedFilter = WatchListFilter.INBOX
    private var mainScrollY = 0
    private var permissionRequired = false
    private var permissionAction: (() -> Unit)? = null
    private var listSelectionAction: (() -> Unit)? = null
    private var newTaskAction: (() -> Unit)? = null
    private var taskOpenAction: ((WatchEventMarks.NextTask) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        clipToPadding = false

        addView(
            contentHost,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        frostedHeader.alpha = 0f
        frostedHeaderClip.addView(
            frostedHeader,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(FROSTED_HEADER_SOURCE_HEIGHT_DP),
                Gravity.TOP,
            ),
        )
        addView(
            frostedHeaderClip,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(COMPACT_HEADER_HEIGHT_DP), Gravity.TOP),
        )

        collapsedTitle.apply {
            gravity = Gravity.END
            alpha = 0f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listSelectionAction?.invoke()
            }
        }
        addView(
            collapsedTitle,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(34)
                marginEnd = dp(54)
            },
        )

        newButton.apply {
            elevation = dp(7).toFloat()
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animate().scaleX(0.88f).scaleY(0.88f).setDuration(80L).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }.start()
                newTaskAction?.invoke()
            }
        }
        addView(
            newButton,
            LayoutParams(dp(NEW_BUTTON_SIZE_DP), dp(NEW_BUTTON_SIZE_DP), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(28)
                bottomMargin = dp(28)
            },
        )

        marks = WatchEventMarks.load(context)
        rebuildMainPage()
    }

    fun setPermissionAction(listener: (() -> Unit)?) {
        permissionAction = listener
    }

    fun setListSelectionAction(listener: (() -> Unit)?) {
        listSelectionAction = listener
    }

    fun setNewTaskAction(listener: (() -> Unit)?) {
        newTaskAction = listener
    }

    fun setTaskOpenAction(listener: ((WatchEventMarks.NextTask) -> Unit)?) {
        taskOpenAction = listener
    }

    fun announceTaskQueued() {
        announceForAccessibility(context.getString(R.string.task_create_queued))
    }

    fun selectedFilter(): WatchListFilter = selectedFilter

    fun setSelectedFilter(filter: WatchListFilter) {
        if (selectedFilter == filter) return
        selectedFilter = filter
        mainScrollY = 0
        rebuildMainPage()
    }

    fun refreshContent(showFeedback: Boolean) {
        marks = WatchEventMarks.load(context)
        rebuildMainPage()
        if (showFeedback && !permissionRequired) {
            announceForAccessibility(context.getString(R.string.task_list_updated))
        }
    }

    fun showPermissionRequired() {
        if (permissionRequired) return
        permissionRequired = true
        rebuildMainPage()
    }

    fun clearPermissionRequired() {
        if (!permissionRequired) return
        permissionRequired = false
        rebuildMainPage()
    }

    private fun rebuildMainPage() {
        contentHost.removeAllViews()
        contentHost.addView(
            createMainPage(),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        frostedHeader.refresh()
    }

    private fun createMainPage(): View {
        val currentListName = context.watchListName(selectedFilter)
        collapsedTitle.text = currentListName
        collapsedTitle.contentDescription = context.getString(
            R.string.task_list_choose_action,
            currentListName,
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(14), 0, dp(14), dp(72))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val largeTitle = textView(28f, ACCENT, BOLD).apply {
            text = currentListName
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(
                R.string.task_list_choose_action,
                currentListName,
            )
            setPadding(dp(18), 0, dp(8), 0)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listSelectionAction?.invoke()
            }
        }
        content.addView(
            largeTitle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(39)).apply {
                topMargin = dp(LARGE_TITLE_TOP_MARGIN_DP)
                bottomMargin = dp(LARGE_TITLE_BOTTOM_MARGIN_DP)
            },
        )

        if (permissionRequired) {
            content.addView(
                stateCard(
                    title = context.getString(R.string.task_list_permission_title),
                    subtitle = context.getString(R.string.task_list_permission_hint),
                    onClick = {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        permissionAction?.invoke()
                    },
                ),
                cardLayoutParams(),
            )
        }

        val visibleTasks = filteredTasks(selectedFilter)
        if (visibleTasks.isEmpty()) {
            content.addView(
                stateCard(
                    title = if (marks.items.isEmpty()) {
                        context.getString(R.string.task_list_waiting_title)
                    } else {
                        context.getString(R.string.task_list_empty_title)
                    },
                    subtitle = if (marks.items.isEmpty()) {
                        context.getString(R.string.task_list_waiting_hint)
                    } else {
                        context.getString(R.string.task_list_empty_hint)
                    },
                ),
                cardLayoutParams(),
            )
        } else {
            visibleTasks.forEach { task ->
                content.addView(taskCard(task), cardLayoutParams())
            }
        }

        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            mainScrollY = scrollY
            updateCollapsedHeader(scrollY)
            frostedHeader.refresh()
        }
        scroll.post {
            scroll.scrollTo(0, mainScrollY)
            updateCollapsedHeader(mainScrollY)
            frostedHeader.refresh()
        }
        return scroll
    }

    private fun taskCard(task: WatchEventMarks.NextTask): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58)
        setPadding(dp(13), dp(9), dp(13), dp(9))
        background = rippleRounded(CARD, CARD_PRESSED, dp(13).toFloat())
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(
            R.string.task_list_item_accessibility,
            task.title,
            taskSubtitle(task),
        )
        addView(
            textView(17f, PRIMARY, MEDIUM).apply {
                text = task.title
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(
            textView(13f, SECONDARY, REGULAR).apply {
                text = taskSubtitle(task)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            },
        )
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            taskOpenAction?.invoke(task)
        }
    }

    private fun stateCard(
        title: String,
        subtitle: String,
        onClick: (() -> Unit)? = null,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58)
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = if (onClick == null) {
            rounded(CARD, dp(13).toFloat())
        } else {
            rippleRounded(CARD, CARD_PRESSED, dp(13).toFloat())
        }
        addView(textView(16f, PRIMARY, MEDIUM).apply { text = title })
        addView(textView(13f, SECONDARY, REGULAR).apply {
            text = subtitle
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        contentDescription = "$title。$subtitle"
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun updateCollapsedHeader(scrollY: Int) {
        val progress = ((scrollY - dp(62)).toFloat() / dp(12)).coerceIn(0f, 1f)
        frostedHeader.alpha = progress
        frostedHeader.isClickable = progress > 0f
        collapsedTitle.alpha = progress
        collapsedTitle.translationY = dp(4) * (1f - progress)
        collapsedTitle.isClickable = progress >= 0.55f
        collapsedTitle.importantForAccessibility = if (progress >= 0.55f) {
            IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun filteredTasks(filter: WatchListFilter): List<WatchEventMarks.NextTask> {
        if (filter == WatchListFilter.INBOX) return marks.items
        val today = LocalDate.now(APP_ZONE)
        val start = today.atStartOfDay(APP_ZONE).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(APP_ZONE).toInstant().toEpochMilli()
        return marks.items.filter { task ->
            task.endEpochMs > start && task.startEpochMs < end
        }
    }

    private fun taskSubtitle(task: WatchEventMarks.NextTask): String {
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(task.startEpochMs), APP_ZONE)
        val end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(task.endEpochMs), APP_ZONE)
        val today = LocalDate.now(APP_ZONE)
        val day = when (start.toLocalDate()) {
            today -> context.getString(R.string.task_list_filter_today)
            today.plusDays(1) -> context.getString(R.string.task_list_tomorrow)
            else -> shortDateFormatter.format(start)
        }
        return if (start.toLocalDate() == end.toLocalDate()) {
            "$day ${timeFormatter.format(start)}–${timeFormatter.format(end)}"
        } else {
            "$day ${timeFormatter.format(start)}–${shortDateFormatter.format(end)} ${timeFormatter.format(end)}"
        }
    }

    private fun cardLayoutParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        bottomMargin = dp(5)
    }

    private fun textView(sizeSp: Float, color: Int, font: Typeface): TextView = TextView(context).apply {
        setTextColor(color)
        textSize = sizeSp
        typeface = font
        includeFontPadding = false
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun rippleRounded(normal: Int, pressed: Int, radius: Float): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(pressed),
            rounded(normal, radius),
            null,
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val LARGE_TITLE_TOP_MARGIN_DP = 23
        const val LARGE_TITLE_HEIGHT_DP = 39
        const val LARGE_TITLE_BOTTOM_MARGIN_DP = 6
        const val COMPACT_HEADER_HEIGHT_DP = 54
        const val FROSTED_HEADER_SOURCE_HEIGHT_DP = COMPACT_HEADER_HEIGHT_DP + 16
        const val NEW_BUTTON_SIZE_DP = 48

        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

        const val PRIMARY = 0xFFF5F5F7.toInt()
        const val SECONDARY = 0xFF9A9AA1.toInt()
        const val ACCENT = 0xFFFFD60A.toInt()
        const val CARD = 0xFF202022.toInt()
        const val CARD_PRESSED = 0x33FFFFFF
        const val NEW_BUTTON_RIPPLE = 0x33000000
    }
}
