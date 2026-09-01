package com.hamhuo.tplanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hamhuo.tplanner.designsystem.TPlannerGeometry
import com.hamhuo.tplanner.designsystem.TPlannerTypography
import java.time.Instant
import java.time.ZonedDateTime

/** A standalone Wear OS destination; the platform activity stack owns swipe-to-dismiss. */
class ListSelectionActivity : WearPageActivity() {
    private lateinit var page: ListSelectionView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = WatchListFilter.fromKey(intent.getStringExtra(EXTRA_CURRENT_FILTER))
        page = ListSelectionView(this, selected) { filter ->
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_SELECTED_FILTER, filter.key),
            )
            finish()
        }
        setContentView(page)
    }

    companion object {
        const val EXTRA_SELECTED_FILTER = "selected_filter"
        private const val EXTRA_CURRENT_FILTER = "current_filter"

        fun createIntent(context: Context, selected: WatchListFilter): Intent =
            Intent(context, ListSelectionActivity::class.java)
                .putExtra(EXTRA_CURRENT_FILTER, selected.key)
    }
}

/** Independent task destination. Wear OS owns edge-swipe/back navigation. */
class TaskDetailActivity : WearPageActivity() {
    private lateinit var page: TaskDetailView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val startEpochMs = intent.getLongExtra(EXTRA_START, Long.MIN_VALUE)
        val endEpochMs = intent.getLongExtra(EXTRA_END, Long.MIN_VALUE)
        val checklistJson = intent.getStringExtra(EXTRA_CHECKLIST).orEmpty()
        if (title.isNullOrBlank() || startEpochMs == Long.MIN_VALUE || endEpochMs < startEpochMs) {
            finish()
            return
        }
        page = TaskDetailView(this, title, startEpochMs, endEpochMs, checklistJson)
        setContentView(page)
    }

    companion object {
        private const val EXTRA_TITLE = "task_title"
        private const val EXTRA_START = "task_start"
        private const val EXTRA_END = "task_end"
        private const val EXTRA_CHECKLIST = "task_checklist"

        fun createIntent(context: Context, task: WatchEventMarks.NextTask): Intent =
            Intent(context, TaskDetailActivity::class.java)
                .putExtra(EXTRA_TITLE, task.title)
                .putExtra(EXTRA_START, task.startEpochMs)
                .putExtra(EXTRA_END, task.endEpochMs)
                .putExtra(EXTRA_CHECKLIST, task.checklistJson)
    }
}

abstract class WearPageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (window.decorView.scrollPageWithCrown(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

private class ListSelectionView(
    context: Context,
    selected: WatchListFilter,
    onSelected: (WatchListFilter) -> Unit,
) : FrameLayout(context) {
    init {
        setBackgroundColor(WEAR_BG)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(18), 0, dp(18), dp(28))
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
        content.addView(
            View(context),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(43)),
        )

        WatchListFilter.entries.forEach { filter ->
            val title = context.watchListName(filter)
            val row = TextView(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(13), dp(8), dp(13), dp(8))
                setTextColor(if (filter == selected) ACCENT else PRIMARY)
                textSize = TPlannerTypography.WearSectionSp
                typeface = MEDIUM
                includeFontPadding = false
                text = title
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                background = rippleRounded(
                    CARD,
                    CARD_PRESSED,
                    dp(TPlannerGeometry.RadiusCardDp).toFloat(),
                )
                isClickable = true
                isFocusable = true
                contentDescription = if (filter == selected) {
                    context.getString(R.string.task_list_filter_current_accessibility, title)
                } else {
                    title
                }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onSelected(filter)
                }
            }
            content.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(5) },
            )
        }

        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (color == CARD) setStroke(dp(1), WEAR_BORDER)
    }

    private fun rippleRounded(normal: Int, pressed: Int, radius: Float): RippleDrawable =
        RippleDrawable(android.content.res.ColorStateList.valueOf(pressed), rounded(normal, radius), null)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

private class TaskDetailView(
    context: Context,
    title: String,
    startEpochMs: Long,
    endEpochMs: Long,
    checklistJson: String,
) : FrameLayout(context) {
    private val timeFormatter = LocalizedDateTimeFormatter(context, R.string.task_time_pattern)
    private val dateFormatter =
        LocalizedDateTimeFormatter(context, R.string.task_list_long_date_pattern)

    init {
        setBackgroundColor(WEAR_BG)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(18), 0, dp(18), dp(28))
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

        content.addView(
            textView(22f, ACCENT, MEDIUM).apply {
                text = context.getString(R.string.task_list_detail_title)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                topMargin = dp(35)
                bottomMargin = dp(7)
            },
        )

        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startEpochMs), APP_ZONE)
        val end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(endEpochMs), APP_ZONE)
        val date = if (start.toLocalDate() == end.toLocalDate()) {
            dateFormatter.format(start)
        } else {
            context.getString(
                R.string.task_list_date_range,
                dateFormatter.format(start),
                dateFormatter.format(end),
            )
        }
        val time = context.getString(
            R.string.task_list_time_range,
            timeFormatter.format(start),
            timeFormatter.format(end),
        )
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(118)
            setPadding(dp(14), dp(13), dp(14), dp(14))
            background = rounded(CARD, dp(TPlannerGeometry.RadiusWearDp).toFloat())
            contentDescription = context.getString(
                R.string.task_list_detail_accessibility,
                title,
                date,
                time,
            )
        }
        panel.addView(textView(20f, PRIMARY, MEDIUM).apply {
            text = title
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        })
        panel.addView(detailLabel(context.getString(R.string.task_list_date_label)), detailLabelParams())
        panel.addView(detailValue(date))
        panel.addView(detailLabel(context.getString(R.string.task_list_time_label)), detailLabelParams())
        panel.addView(detailValue(time))
        content.addView(
            panel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val checklist = parseChecklist(checklistJson)
        android.util.Log.d("TaskDetail", "checklist json length=${checklistJson.length}, items=${checklist.size}")
        if (checklist.isNotEmpty()) {
            val checklistPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(60)
                setPadding(dp(14), dp(13), dp(14), dp(14))
                background = rounded(CARD, dp(TPlannerGeometry.RadiusWearDp).toFloat())
            }
            checklistPanel.addView(
                detailLabel(context.getString(R.string.task_list_checklist_label)),
                detailLabelParams().apply { topMargin = 0 },
            )
            checklist.forEach { item ->
                checklistPanel.addView(
                    checklistItemView(item.text, item.completed),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(5) },
                )
            }
            content.addView(
                checklistPanel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(14) },
            )
        }

        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private data class ChecklistItemData(val text: String, val completed: Boolean)

    private fun parseChecklist(json: String): List<ChecklistItemData> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                ChecklistItemData(
                    text = obj.optString("text", ""),
                    completed = obj.optBoolean("completed", false),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun checklistItemView(text: String, completed: Boolean): TextView =
        textView(14f, if (completed) WEAR_DIM else PRIMARY, REGULAR).apply {
            setText(text)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, 0, 0)
        }

    private fun detailLabel(value: String): TextView = textView(12f, ACCENT, MEDIUM).apply {
        text = value
    }

    private fun detailValue(value: String): TextView = textView(15f, PRIMARY, REGULAR).apply {
        text = value
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun detailLabelParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(13)
        bottomMargin = dp(2)
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
        if (color == CARD) setStroke(dp(1), WEAR_BORDER)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

private val REGULAR: Typeface = WEAR_REGULAR
private val MEDIUM: Typeface = WEAR_MEDIUM
private const val PRIMARY = WEAR_PRIMARY
private const val ACCENT = WEAR_GOLD
private const val CARD = WEAR_SURFACE2
private const val CARD_PRESSED = WEAR_CONTROL_PRESSED
