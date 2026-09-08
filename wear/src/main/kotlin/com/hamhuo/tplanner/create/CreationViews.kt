package com.hamhuo.tplanner

import android.content.Context
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
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens
import java.time.ZoneId

// ── shared constants ───────────────────────────────────────────────────

const val TYPE_EVENT = "event"
const val TYPE_STATUS = "status"
const val TYPE_TASK = "task"
const val TAG_VALUE = "task_creation_value"

const val CREATION_PRIMARY = WEAR_PRIMARY
const val CREATION_ACCENT = WEAR_ACCENT_TEXT
const val CREATION_DIM = WEAR_DIM
const val CREATION_CARD = WEAR_SURFACE2
const val CREATION_BORDER = WEAR_BORDER
const val CREATION_CARD_PRESSED = WEAR_CONTROL_PRESSED
val CREATION_REGULAR = WEAR_REGULAR
val CREATION_MEDIUM = WEAR_MEDIUM
val CREATION_HEADING_FONT = WEAR_SEMIBOLD
val CREATION_ZONE = ZoneId.of(WatchTaskProtocol.DEFAULT_TIME_ZONE_ID)
const val TASK_COLOR_COUNT = 8

// ── shared View builders ───────────────────────────────────────────────

fun Context.creationScrollPage(content: LinearLayout): View = FrameLayout(this).apply {
    setBackgroundColor(WEAR_BG)
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
    textSize = TPlannerLightTokens.Platform.Wear.Typography.Heading.FontSize
    typeface = CREATION_HEADING_FONT
    wearTextMetrics(TPlannerLightTokens.Platform.Wear.Typography.Heading.LineHeight)
    gravity = Gravity.CENTER
    maxLines = 2
    ellipsize = TextUtils.TruncateAt.END
    setPadding(dp(10), dp(3), dp(10), dp(8))
}

fun Context.creationActionRow(
    textRes: Int,
    primary: Boolean = false,
    action: () -> Unit,
): TextView = TextView(this).apply {
    setText(textRes)
    wearButtonStyle(primary)
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
        minimumHeight = wearDp(TPlannerLightTokens.Platform.Wear.Geometry.TaskRowMinHeight)
        setPadding(
        wearDp(TPlannerLightTokens.Platform.Wear.Geometry.RowPaddingInline),
        wearDp(TPlannerLightTokens.Platform.Wear.Geometry.RowPaddingBlock),
        wearDp(TPlannerLightTokens.Platform.Wear.Geometry.RowPaddingInline),
        wearDp(TPlannerLightTokens.Platform.Wear.Geometry.RowPaddingBlock),
    )
        background = creationRippleRounded(
            CREATION_CARD,
            CREATION_CARD_PRESSED,
            wearDp(TPlannerLightTokens.Semantic.Radius.Control).toFloat(),
        )
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
            addView(creationRowText(title, TPlannerLightTokens.Platform.Wear.Typography.TaskTitle.FontSize, CREATION_PRIMARY, CREATION_MEDIUM))
            addView(creationRowText(description, TPlannerLightTokens.Platform.Wear.Typography.Meta.FontSize, CREATION_DIM, CREATION_REGULAR))
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
    minimumHeight = wearDp(TPlannerLightTokens.Platform.Wear.Geometry.TaskRowMinHeight)
    setPadding(
        wearDp(TPlannerLightTokens.Platform.Wear.Geometry.RowPaddingInline),
        wearDp(TPlannerLightTokens.Platform.Wear.Geometry.RowPaddingBlock),
        wearDp(TPlannerLightTokens.Platform.Wear.Geometry.RowPaddingInline),
        wearDp(TPlannerLightTokens.Platform.Wear.Geometry.RowPaddingBlock),
    )
    background = creationRippleRounded(
        CREATION_CARD,
        CREATION_CARD_PRESSED,
        wearDp(TPlannerLightTokens.Semantic.Radius.Control).toFloat(),
    )
    isClickable = true
    isFocusable = true
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(5) }

    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(creationRowText(getString(titleRes), TPlannerLightTokens.Platform.Wear.Typography.TaskTitle.FontSize, CREATION_PRIMARY, CREATION_MEDIUM))
        addView(creationRowText(getString(descriptionRes), TPlannerLightTokens.Platform.Wear.Typography.Meta.FontSize, CREATION_DIM, CREATION_REGULAR).apply {
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
    wearTextMetrics()
    maxLines = 3
    ellipsize = TextUtils.TruncateAt.END
}

fun Context.creationRounded(color: Int, radius: Float): GradientDrawable =
    wearSurfaceBackground(color, radius)

fun Context.creationRippleRounded(normal: Int, pressed: Int, radius: Float): RippleDrawable =
    wearInteractiveBackground(normal, radius, WEAR_BORDER, pressed)

/** A value and its label form one 48dp-or-larger target, with content-driven height. */
internal class CreationNumberField(
    val root: LinearLayout,
    val value: TextView,
    private val label: TextView,
) {
    fun renderSelection(selected: Boolean) {
        root.isSelected = selected
        value.setTextColor(if (selected) CREATION_ACCENT else CREATION_PRIMARY)
        label.setTextColor(if (selected) CREATION_ACCENT else CREATION_DIM)
        root.background = root.context.wearInteractiveBackground(
            fill = if (selected) TPlannerLightTokens.Semantic.Color.SelectedBackground else WEAR_INPUT,
            border = if (selected) TPlannerLightTokens.Semantic.Color.Focus else WEAR_BORDER,
        )
        root.contentDescription = "${label.text}: ${value.text}"
    }
}

internal fun Context.creationNumberField(labelRes: Int, onSelect: () -> Unit): CreationNumberField {
    val label = creationRowText(getString(labelRes),
        TPlannerLightTokens.Platform.Wear.Typography.Meta.FontSize, CREATION_DIM, CREATION_MEDIUM)
    val value = creationRowText("", TPlannerLightTokens.Platform.Wear.Typography.Heading.FontSize,
        CREATION_PRIMARY, CREATION_HEADING_FONT).apply {
        wearTextMetrics(TPlannerLightTokens.Platform.Wear.Typography.Heading.LineHeight)
        gravity = Gravity.CENTER
        maxLines = 2
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = wearDp(TPlannerLightTokens.Platform.Wear.Geometry.ControlMinHeight)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isClickable = true
        isFocusable = true
        addView(label)
        addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            onSelect()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }
    return CreationNumberField(root, value, label)
}

/** Keep the familiar two-column picker at normal scale; stack fields when text needs room. */
internal fun Context.creationNumberFields(first: CreationNumberField, second: CreationNumberField): LinearLayout =
    LinearLayout(this).apply {
        val stacked = resources.configuration.fontScale > 1.3f || resources.configuration.screenWidthDp < 176
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        listOf(first, second).forEachIndexed { index, field ->
            addView(field.root, if (stacked) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { if (index > 0) topMargin = dp(8) }
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (index == 0) marginEnd = dp(8) }
            })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

internal fun Context.creationStepperRow(adjust: (Int) -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    listOf(-1, 1).forEachIndexed { index, delta ->
        addView(TextView(context).apply {
            text = if (delta < 0) "−" else "+"
            contentDescription = getString(if (delta < 0) R.string.task_create_decrease else R.string.task_create_increase)
            wearButtonStyle()
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                adjust(delta)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index == 0) marginEnd = dp(8)
        })
    }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(8) }
}

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()
