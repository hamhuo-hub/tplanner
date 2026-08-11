package com.hamhuo.tplanner

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
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

/** The launcher screen only. Secondary destinations are independent Wear OS activities. */
class NextDashboardView(context: Context) : FrameLayout(context) {
    private val contentHost = FrameLayout(context)
    private val timeView = textView(14f, PRIMARY, MEDIUM)
    private val collapsedTitle = textView(15f, ACCENT, MEDIUM)
    private val newButton = iconButton(
        iconRes = android.R.drawable.ic_input_add,
        contentDescription = context.getString(R.string.task_list_new_unavailable),
        sizeDp = 48,
    )

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val shortDateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private var marks = WatchEventMarks.EMPTY
    private var selectedFilter = WatchListFilter.INBOX
    private var mainScrollY = 0
    private var permissionRequired = false
    private var running = false
    private var permissionAction: (() -> Unit)? = null
    private var listSelectionAction: (() -> Unit)? = null
    private var taskOpenAction: ((WatchEventMarks.NextTask) -> Unit)? = null

    private val clockTicker = object : Runnable {
        override fun run() {
            if (!running) return
            updateClock()
            val now = System.currentTimeMillis()
            postDelayed(this, MINUTE_MS - now % MINUTE_MS + CLOCK_SLOP_MS)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        clipToPadding = false

        addView(
            contentHost,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
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

        timeView.apply {
            gravity = Gravity.END
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        addView(
            timeView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(14)
                marginEnd = dp(54)
            },
        )

        newButton.apply {
            elevation = dp(7).toFloat()
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                announceForAccessibility(context.getString(R.string.task_list_new_unavailable_feedback))
                animate().scaleX(0.88f).scaleY(0.88f).setDuration(80L).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }.start()
            }
        }
        addView(
            newButton,
            LayoutParams(dp(48), dp(48), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(28)
                bottomMargin = dp(28)
            },
        )

        marks = WatchEventMarks.load(context)
        rebuildMainPage()
        updateClock()
    }

    fun setPermissionAction(listener: (() -> Unit)?) {
        permissionAction = listener
    }

    fun setListSelectionAction(listener: (() -> Unit)?) {
        listSelectionAction = listener
    }

    fun setTaskOpenAction(listener: ((WatchEventMarks.NextTask) -> Unit)?) {
        taskOpenAction = listener
    }

    fun selectedFilter(): WatchListFilter = selectedFilter

    fun setSelectedFilter(filter: WatchListFilter) {
        if (selectedFilter == filter) return
        selectedFilter = filter
        mainScrollY = 0
        rebuildMainPage()
    }

    fun start() {
        if (running) return
        running = true
        refreshContent(showFeedback = false)
        removeCallbacks(clockTicker)
        post(clockTicker)
    }

    fun stop() {
        running = false
        removeCallbacks(clockTicker)
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
                topMargin = dp(23)
                bottomMargin = dp(6)
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
        }
        scroll.post {
            scroll.scrollTo(0, mainScrollY)
            updateCollapsedHeader(mainScrollY)
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
        collapsedTitle.alpha = progress
        collapsedTitle.translationY = dp(4) * (1f - progress)
        collapsedTitle.isClickable = progress >= 0.55f
        collapsedTitle.importantForAccessibility = if (progress >= 0.55f) {
            IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun updateClock() {
        val value = timeFormatter.format(ZonedDateTime.now(APP_ZONE))
        timeView.text = value
        timeView.contentDescription = context.getString(R.string.task_list_current_time, value)
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

    private fun iconButton(iconRes: Int, contentDescription: String, sizeDp: Int): ImageButton =
        ImageButton(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(PRIMARY)
            background = InsetDrawable(
                rippleRounded(BUTTON, BUTTON_PRESSED, dp((sizeDp - 8) / 2).toFloat()),
                dp(4),
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            this.contentDescription = contentDescription
            isFocusable = true
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
        const val MINUTE_MS = 60_000L
        const val CLOCK_SLOP_MS = 40L

        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

        const val PRIMARY = 0xFFF5F5F7.toInt()
        const val SECONDARY = 0xFF9A9AA1.toInt()
        const val ACCENT = 0xFFFFD60A.toInt()
        const val CARD = 0xFF202022.toInt()
        const val CARD_PRESSED = 0x33FFFFFF
        const val BUTTON = 0xFF303034.toInt()
        const val BUTTON_PRESSED = 0x55FFFFFF
    }
}
