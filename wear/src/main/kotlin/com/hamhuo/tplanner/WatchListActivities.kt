package com.hamhuo.tplanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    override fun onResume() {
        super.onResume()
        page.start()
    }

    override fun onPause() {
        page.stop()
        super.onPause()
    }

    companion object {
        const val EXTRA_SELECTED_FILTER = "selected_filter"
        private const val EXTRA_CURRENT_FILTER = "current_filter"

        fun createIntent(context: Context, selected: WatchListFilter): Intent =
            Intent(context, ListSelectionActivity::class.java)
                .putExtra(EXTRA_CURRENT_FILTER, selected.key)
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
    private val clock = FloatingClockView(context)

    init {
        setBackgroundColor(Color.BLACK)
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
                textSize = 18f
                typeface = MEDIUM
                includeFontPadding = false
                text = title
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                background = rippleRounded(CARD, CARD_PRESSED, dp(12).toFloat())
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
        addView(
            clock,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(14)
                marginEnd = dp(54)
            },
        )
    }

    fun start() = clock.start()
    fun stop() = clock.stop()

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun rippleRounded(normal: Int, pressed: Int, radius: Float): RippleDrawable =
        RippleDrawable(android.content.res.ColorStateList.valueOf(pressed), rounded(normal, radius), null)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

private class FloatingClockView(context: Context) : TextView(context) {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private var running = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            updateClock()
            val now = System.currentTimeMillis()
            postDelayed(this, MINUTE_MS - now % MINUTE_MS + CLOCK_SLOP_MS)
        }
    }

    init {
        setTextColor(PRIMARY)
        textSize = 14f
        typeface = MEDIUM
        includeFontPadding = false
        gravity = Gravity.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateClock()
    }

    fun start() {
        if (running) return
        running = true
        removeCallbacks(ticker)
        post(ticker)
    }

    fun stop() {
        running = false
        removeCallbacks(ticker)
    }

    private fun updateClock() {
        val value = formatter.format(ZonedDateTime.now(APP_ZONE))
        text = value
        contentDescription = context.getString(R.string.task_list_current_time, value)
    }
}

private val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
private const val PRIMARY = 0xFFF5F5F7.toInt()
private const val ACCENT = 0xFFFFD60A.toInt()
private const val CARD = 0xFF202022.toInt()
private const val CARD_PRESSED = 0x33FFFFFF
private const val MINUTE_MS = 60_000L
private const val CLOCK_SLOP_MS = 40L
