package com.hamhuo.tplanner

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.ZoneId

// ── shared constants ───────────────────────────────────────────────────

private const val TYPE_EVENT = "event"
private const val TYPE_STATUS = "status"
private const val TYPE_TASK = "task"
const val TAG_VALUE = "task_creation_value"

const val CREATION_PRIMARY = 0xFFF5F5F7.toInt()
const val CREATION_ACCENT = 0xFFFFD60A.toInt()
const val CREATION_DIM = 0xFF8E8E93.toInt()
const val CREATION_CARD = 0xFF202022.toInt()
const val CREATION_CARD_PRESSED = 0x33FFFFFF
val CREATION_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL)
val CREATION_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val CREATION_BOLD = Typeface.create("sans-serif", Typeface.BOLD)
val CREATION_ZONE = ZoneId.of(WatchTaskProtocol.DEFAULT_TIME_ZONE_ID)
val TASK_COLORS = intArrayOf(
    0xFF5B8FCC.toInt(),
    0xFFC9A84C.toInt(),
    0xFFC0697A.toInt(),
    0xFF5B9E72.toInt(),
    0xFF8B6BAE.toInt(),
    0xFFC87D5A.toInt(),
    0xFF4A9DA8.toInt(),
    0xFF8A8A8A.toInt(),
)

// ── shared View builders ───────────────────────────────────────────────

fun Context.creationScrollPage(content: LinearLayout): View = FrameLayout(this).apply {
    setBackgroundColor(Color.BLACK)
    addView(
        ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(18), 0, dp(18), dp(24))
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        },
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ),
    )
}

fun Context.creationContent(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
}

fun Context.creationTopSpacer(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(29))
}

fun Context.creationBottomSpacer(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26))
}

fun Context.creationHeading(value: String): TextView = TextView(this).apply {
    text = value
    setTextColor(CREATION_PRIMARY)
    textSize = 23f
    typeface = CREATION_BOLD
    includeFontPadding = false
    gravity = Gravity.CENTER
    maxLines = 2
    ellipsize = TextUtils.TruncateAt.END
    setPadding(dp(10), dp(3), dp(10), dp(8))
}

fun Context.creationActionRow(
    textRes: Int,
    action: () -> Unit,
): TextView = TextView(this).apply {
    setText(textRes)
    setTextColor(CREATION_ACCENT)
    textSize = 17f
    typeface = CREATION_BOLD
    includeFontPadding = false
    gravity = Gravity.CENTER
    minimumHeight = dp(54)
    setPadding(dp(18), dp(8), dp(18), dp(8))
    background = creationRippleRounded(Color.TRANSPARENT, CREATION_CARD_PRESSED, dp(14).toFloat())
    isClickable = true
    isFocusable = true
    contentDescription = getString(textRes)
    setOnClickListener { action() }
}

fun Context.creationTypeButton(
    titleRes: Int,
    descriptionRes: Int,
    action: () -> Unit,
): LinearLayout {
    val title = getString(titleRes)
    val description = getString(descriptionRes)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(61)
        setPadding(dp(13), dp(7), dp(13), dp(7))
        background = creationRippleRounded(CREATION_CARD, CREATION_CARD_PRESSED, dp(14).toFloat())
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.task_create_type_accessibility, title, description)
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(creationRowText(title, 17f, CREATION_PRIMARY, CREATION_MEDIUM))
            addView(creationRowText(description, 12f, CREATION_DIM, CREATION_REGULAR))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(5)
        }
    }
}

fun Context.creationSettingRow(
    titleRes: Int,
    descriptionRes: Int,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(66)
    setPadding(dp(13), dp(8), dp(12), dp(8))
    background = creationRippleRounded(CREATION_CARD, CREATION_CARD_PRESSED, dp(14).toFloat())
    isClickable = true
    isFocusable = true
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(5) }

    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(creationRowText(getString(titleRes), 17f, CREATION_PRIMARY, CREATION_MEDIUM))
        addView(creationRowText(getString(descriptionRes), 12f, CREATION_DIM, CREATION_REGULAR).apply {
            tag = TAG_VALUE
        })
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}

fun Context.creationRowText(
    value: String,
    sizeSp: Float,
    color: Int,
    font: Typeface,
): TextView = TextView(this).apply {
    text = value
    setTextColor(color)
    textSize = sizeSp
    typeface = font
    includeFontPadding = false
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

fun creationRounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radius
    setColor(color)
}

fun creationRippleRounded(normal: Int, pressed: Int, radius: Float): RippleDrawable =
    RippleDrawable(
        ColorStateList.valueOf(pressed),
        creationRounded(normal, radius),
        null,
    )

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()
