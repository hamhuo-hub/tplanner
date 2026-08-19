package com.hamhuo.tplanner

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitModel
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitVariant
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitView
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

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
        setBackgroundColor(BG)
    }
    private val frostedHeader = FrostedHeaderView(context, contentHost)
    private val frostedHeaderClip = FrostedHeaderClipView(context)
    private val collapsedTitle = textView(12f, ACCENT, MEDIUM)
    private val newButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_add_rounded_24)
        imageTintList = ColorStateList.valueOf(ACCENT)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(13), dp(12), dp(12), dp(12))
        background = rippleRounded(CONTROL, CARD_PRESSED, dp(NEW_BUTTON_SIZE_DP / 2).toFloat())
        contentDescription = context.getString(R.string.task_list_new)
        isClickable = true
        isFocusable = true
    }
    private val syncFeedback = textView(10f, ACCENT, WEAR_MONOSPACE).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.02f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(172)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(CARD, dp(15).toFloat())
        alpha = 0f
        visibility = INVISIBLE
        elevation = dp(8).toFloat()
        isClickable = false
        isFocusable = false
    }

    private val timeFormatter = LocalizedDateTimeFormatter(context, R.string.task_time_pattern)
    private val shortDateFormatter =
        LocalizedDateTimeFormatter(context, R.string.task_list_short_date_pattern)
    private var marks = WatchEventMarks.EMPTY
    private var selectedFilter = WatchListFilter.INBOX
    private var mainScrollY = 0
    private var mainScroll: ScrollView? = null
    private var mainContent: LinearLayout? = null
    private var permissionRequired = false
    private var permissionAction: (() -> Unit)? = null
    private var listSelectionAction: (() -> Unit)? = null
    private var newTaskAction: (() -> Unit)? = null
    private var taskOpenAction: ((WatchEventMarks.NextTask) -> Unit)? = null
    private var taskDeleteAction: ((WatchEventMarks.NextTask) -> Unit)? = null
    private var syncAction: (() -> Unit)? = null
    private var syncInProgress = false
    private var syncPullEligible = false
    private var syncPullStartX = 0f
    private var syncPullStartY = 0f
    private val hideSyncFeedback = Runnable {
        syncFeedback.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction { syncFeedback.visibility = INVISIBLE }
            .start()
    }

    init {
        setBackgroundColor(BG)
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
                topMargin = dp(10)
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
        addView(
            syncFeedback,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(9)
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

    fun setTaskDeleteAction(listener: ((WatchEventMarks.NextTask) -> Unit)?) {
        taskDeleteAction = listener
    }

    fun setSyncAction(listener: (() -> Unit)?) {
        syncAction = listener
    }

    internal fun showSyncing() {
        syncInProgress = true
        showSyncFeedback(
            message = context.getString(R.string.task_list_syncing),
            color = ACCENT,
            autoHide = false,
        )
    }

    internal fun showSyncResult(result: WatchManualSync.Result) {
        syncInProgress = false
        val (message, color) = when (result) {
            WatchManualSync.Result.COMPLETED ->
                context.getString(R.string.task_list_sync_complete) to SUCCESS

            WatchManualSync.Result.FAILED ->
                context.getString(R.string.task_list_sync_failed) to ERROR
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        showSyncFeedback(message, color, autoHide = true)
    }

    fun announceTaskQueued() {
        announceForAccessibility(context.getString(R.string.task_create_queued))
    }

    fun selectedFilter(): WatchListFilter = selectedFilter

    fun setSelectedFilter(filter: WatchListFilter) {
        if (selectedFilter == filter) return
        // Filters are projections of one canonical task snapshot, not independent stores.
        // Reload first so persisted local deletes are reflected before rebuilding another view.
        marks = WatchEventMarks.load(context)
        selectedFilter = filter
        mainScrollY = 0
        mainScroll?.scrollTo(0, 0)
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        var triggerSync = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                syncPullStartX = event.x
                syncPullStartY = event.y
                syncPullEligible =
                    !syncInProgress &&
                    syncAction != null &&
                    mainScroll?.scrollY == 0
            }

            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - syncPullStartX
                val deltaY = event.y - syncPullStartY
                triggerSync =
                    syncPullEligible &&
                    deltaY >= dp(SYNC_PULL_DISTANCE_DP) &&
                    kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * 1.2f
                syncPullEligible = false
            }

            MotionEvent.ACTION_CANCEL -> syncPullEligible = false
        }

        val handled = super.dispatchTouchEvent(event)
        if (triggerSync) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            post { syncAction?.invoke() }
        }
        return handled
    }

    private fun rebuildMainPage() {
        val existingScroll = mainScroll
        val existingContent = mainContent

        if (existingScroll == null || existingContent == null) {
            val page = createMainPage()
            contentHost.removeAllViews()
            contentHost.addView(
                page,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        } else {
            // Preserve the current viewport while replacing only the list contents.
            val restoreScrollY = existingScroll.scrollY
            populateMainContent(existingContent)

            existingScroll.post {
                val child = existingScroll.getChildAt(0)
                val viewportHeight =
                    existingScroll.height - existingScroll.paddingTop - existingScroll.paddingBottom
                val maxScrollY =
                    ((child?.height ?: 0) - viewportHeight).coerceAtLeast(0)
                val targetScrollY = restoreScrollY.coerceIn(0, maxScrollY)

                if (existingScroll.scrollY != targetScrollY) {
                    existingScroll.scrollTo(0, targetScrollY)
                }

                mainScrollY = existingScroll.scrollY
                updateCollapsedHeader(mainScrollY)
                frostedHeader.refresh()
            }
        }
    }

    private fun createMainPage(): View {
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

        mainScroll = scroll
        mainContent = content

        populateMainContent(content)

        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            mainScrollY = scrollY
            updateCollapsedHeader(scrollY)
            frostedHeader.refresh()
        }

        updateCollapsedHeader(mainScrollY)
        return scroll
    }

    private fun populateMainContent(content: LinearLayout) {
        val currentListName = context.watchListName(selectedFilter)
        collapsedTitle.text = currentListName
        collapsedTitle.contentDescription = context.getString(
            R.string.task_list_choose_action,
            currentListName,
        )

        content.removeAllViews()

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
                content.addView(SwipeTaskCardView(task), cardLayoutParams())
            }
        }
    }

    private fun taskCard(task: WatchEventMarks.NextTask): View {
        val (checklistDone, checklistTotal) = checklistProgress(task.checklistJson)
        return TPlannerTaskUnitView(context).apply {
            render(
                model = TPlannerTaskUnitModel(
                    title = task.title,
                    supportingText = taskSubtitle(task),
                    isTask = task.type == "task",
                    checklistDone = checklistDone,
                    checklistTotal = checklistTotal,
                    accessibilityLabel = context.getString(
                        R.string.task_list_item_accessibility,
                        task.title,
                        taskSubtitle(task),
                    ),
                ),
                variant = TPlannerTaskUnitVariant.WEAR,
                onClick = {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    taskOpenAction?.invoke(task)
                },
            )
        }
    }

    private fun checklistProgress(raw: String): Pair<Int, Int> = runCatching {
        val array = JSONArray(raw)
        var done = 0
        for (index in 0 until array.length()) {
            if (array.optJSONObject(index)?.optBoolean("completed") == true) done += 1
        }
        done to array.length()
    }.getOrDefault(0 to 0)

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
        contentDescription = context.getString(
            R.string.task_list_state_accessibility,
            title,
            subtitle,
        )
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
        // Keep the local tombstone set authoritative even if another caller updates it between
        // snapshot reloads. This prevents a stale Marks instance from resurrecting a task.
        val deletedIds = WatchLocalDeletes.all(context)
        val available = if (deletedIds.isEmpty()) {
            marks.items
        } else {
            marks.items.filterNot { it.id in deletedIds }
        }
        if (filter == WatchListFilter.INBOX) return available
        val today = LocalDate.now(APP_ZONE)
        val start = today.atStartOfDay(APP_ZONE).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(APP_ZONE).toInstant().toEpochMilli()
        return available.filter { task ->
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
            context.getString(
                R.string.task_list_same_day_subtitle,
                day,
                timeFormatter.format(start),
                timeFormatter.format(end),
            )
        } else {
            context.getString(
                R.string.task_list_cross_day_subtitle,
                day,
                timeFormatter.format(start),
                shortDateFormatter.format(end),
                timeFormatter.format(end),
            )
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
        if (color == CARD || color == CONTROL) setStroke(dp(1), BORDER)
    }

    private fun rippleRounded(normal: Int, pressed: Int, radius: Float): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(pressed),
            rounded(normal, radius),
            null,
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showSyncFeedback(message: String, color: Int, autoHide: Boolean) {
        removeCallbacks(hideSyncFeedback)
        syncFeedback.animate().cancel()
        syncFeedback.text = message
        syncFeedback.setTextColor(color)
        syncFeedback.contentDescription = message
        syncFeedback.visibility = VISIBLE
        syncFeedback.alpha = 1f
        announceForAccessibility(message)
        if (autoHide) postDelayed(hideSyncFeedback, SYNC_FEEDBACK_DURATION_MS)
    }

    /** Wraps a task card with a swipe-to-reveal delete button. Vertical scroll passes through. */
    private inner class SwipeTaskCardView(
        task: WatchEventMarks.NextTask,
    ) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val revealWidth = dp(60)
        private var tracking = false
        private var isOpen = false
        private var downX = 0f
        private var downY = 0f
        private var isDeleting = false
        private val cardContent: View

        init {
            clipChildren = false
            clipToPadding = false

            // bottom layer: delete button
            val deleteButton = textView(14f, PRIMARY, MEDIUM).apply {
                text = context.getString(R.string.task_list_delete)
                gravity = Gravity.CENTER
                background = rounded(ERROR, dp(13).toFloat())
                minimumWidth = revealWidth
                setOnClickListener {
                    if (isDeleting) return@setOnClickListener
                    isDeleting = true

                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    isClickable = false

                    val wrapper = this@SwipeTaskCardView
                    val startHeight = wrapper.height.coerceAtLeast(1)

                    android.animation.ValueAnimator.ofInt(startHeight, 0).apply {
                        duration = 200L

                        addUpdateListener { animator ->
                            val lp = wrapper.layoutParams
                            lp.height = (animator.animatedValue as Int).coerceAtLeast(0)
                            wrapper.layoutParams = lp
                            wrapper.alpha = 1f - animator.animatedFraction
                        }

                        addListener(
                            object : AnimatorListenerAdapter() {
                                private var finished = false

                                private fun finishDelete() {
                                    if (finished) return
                                    finished = true

                                    (wrapper.parent as? ViewGroup)?.removeView(wrapper)
                                    taskDeleteAction?.invoke(task)
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    finishDelete()
                                }

                                override fun onAnimationCancel(animation: Animator) {
                                    finishDelete()
                                }
                            },
                        )
                        start()
                    }
                }
            }
            addView(
                deleteButton,
                LayoutParams(revealWidth, LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL),
            )

            // top layer: existing card
            cardContent = taskCard(task)
            addView(
                cardContent,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; tracking = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (!tracking && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        tracking = true
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                    return tracking
                }
                else -> return tracking
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        cardContent.translationX = (ev.x - downX).coerceIn(-revealWidth.toFloat(), 0f)
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (tracking) {
                        settle(cardContent.translationX < -revealWidth * 0.35f)
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    } else if (isOpen) {
                        settle(false)
                    } else {
                        cardContent.callOnClick()
                    }
                    parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (tracking) settle(isOpen)
                    parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        fun close() { if (isOpen) settle(false) }

        private fun settle(open: Boolean) {
            isOpen = open
            cardContent.animate()
                .translationX(if (open) -revealWidth.toFloat() else 0f)
                .setDuration(150L)
                .start()
        }
    }

    private companion object {
        const val LARGE_TITLE_TOP_MARGIN_DP = 23
        const val LARGE_TITLE_HEIGHT_DP = 39
        const val LARGE_TITLE_BOTTOM_MARGIN_DP = 6
        const val COMPACT_HEADER_HEIGHT_DP = 54
        const val FROSTED_HEADER_SOURCE_HEIGHT_DP = COMPACT_HEADER_HEIGHT_DP + 16
        const val NEW_BUTTON_SIZE_DP = 48
        const val SYNC_PULL_DISTANCE_DP = 52
        const val SYNC_FEEDBACK_DURATION_MS = 1_600L

        val REGULAR: Typeface = WEAR_REGULAR
        val MEDIUM: Typeface = WEAR_MEDIUM
        val BOLD: Typeface = WEAR_BOLD

        const val BG = WEAR_BG
        const val PRIMARY = WEAR_PRIMARY
        const val SECONDARY = WEAR_DIM
        const val ACCENT = WEAR_GOLD
        const val SUCCESS = WEAR_TEAL
        const val ERROR = WEAR_RED
        const val BORDER = WEAR_BORDER
        const val CARD = WEAR_SURFACE2
        const val CONTROL = WEAR_CONTROL
        const val CARD_PRESSED = WEAR_CONTROL_PRESSED
    }
}
