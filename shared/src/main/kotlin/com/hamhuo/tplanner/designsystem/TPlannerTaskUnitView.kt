package com.hamhuo.tplanner.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens.Component.Task
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens.Platform.Phone
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens.Platform.Wear
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens.Semantic

enum class TPlannerTaskUnitVariant {
    PHONE,
    WEAR,
}

data class TPlannerTaskUnitModel(
    val title: String,
    val supportingText: String,
    val isTask: Boolean = true,
    val showTaskCheckbox: Boolean = true,
    val completed: Boolean = false,
    val past: Boolean = false,
    val current: Boolean = false,
    val accentColor: Int = TPlannerLightTokens.Semantic.Category.Id0.Accent,
    val checklistDone: Int = 0,
    val checklistTotal: Int = 0,
    val statusLabel: String = "",
    val alarmEnabled: Boolean = false,
    val accessibilityLabel: String = "",
)

/**
 * Shared task/event unit used directly by Wear Views and through AndroidView on Compose phones.
 * It owns completion, progress, status, typography, spacing and semantic color behavior.
 */
class TPlannerTaskUnitView(context: Context) : LinearLayout(context) {
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val leading = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
    }
    private val textColumn = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private val titleRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val title = TextView(context).apply {
        typeface = medium
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val progress = badgeView()
    private val alarm = badgeView()
    private val status = badgeView()
    private val supporting = TextView(context).apply {
        typeface = regular
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(progress, wrapContent(startMarginDp = 4))
        titleRow.addView(alarm, wrapContent(startMarginDp = 4))
        titleRow.addView(status, wrapContent(startMarginDp = 4))
        textColumn.addView(title, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textColumn.addView(supporting, wrapContent(topMarginDp = 2))
        textColumn.addView(titleRow, wrapContent(topMarginDp = 2))
        addView(leading)
        addView(textColumn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun render(
        model: TPlannerTaskUnitModel,
        variant: TPlannerTaskUnitVariant,
        onClick: (() -> Unit)? = null,
        onLeadingClick: (() -> Unit)? = null,
    ) {
        val wear = variant == TPlannerTaskUnitVariant.WEAR
        val current = model.current && !model.completed && !model.past
        val statusLabel = model.statusLabel.takeUnless { model.current && !current }.orEmpty()
        val horizontalPadding = if (wear) Wear.Geometry.RowPaddingInline else Phone.Geometry.RowPaddingInline
        val verticalPadding = if (wear) Wear.Geometry.RowPaddingBlock else Phone.Geometry.RowPaddingBlock
        minimumHeight = dp(if (wear) Wear.Geometry.TaskRowMinHeight else Phone.Geometry.TaskRowMinHeight)
        setPadding(dp(horizontalPadding), dp(verticalPadding), dp(horizontalPadding), dp(verticalPadding))
        title.text = model.title
        title.setTextColor(if (model.completed || model.past) Task.CompletedForeground else Task.Foreground)
        title.textSize = if (wear) Wear.Typography.TaskTitle.FontSize else Phone.Typography.TaskTitle.FontSize
        title.maxLines = 2
        title.paintFlags = if (model.completed) {
            title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        supporting.text = model.supportingText
        supporting.visibility = if (model.supportingText.isBlank()) GONE else VISIBLE
        supporting.setTextColor(when {
            model.completed || model.past -> Task.CompletedForeground
            current -> Task.CurrentForeground
            else -> Task.SupportingForeground
        })
        val supportingSp = if (wear) Wear.Typography.Meta.FontSize else Phone.Typography.Meta.FontSize
        supporting.textSize = supportingSp
        for (badge in listOf(progress, alarm, status)) badge.textSize = supportingSp

        configureLeading(model, wear, onLeadingClick)
        configureProgress(model)
        configureBadge(alarm, model.alarmEnabled, "\u25C7", Semantic.Color.Warning)
        configureBadge(
            status,
            statusLabel.isNotBlank(),
            statusLabel,
            if (current) Task.CurrentForeground else Semantic.Color.Warning,
        )
        titleRow.visibility = if (listOf(progress, alarm, status).any { it.visibility == VISIBLE }) VISIBLE else GONE
        alpha = Semantic.State.NormalOpacity
        background = rippleBackground(
            if (current) Task.CurrentBackground else Task.NormalBackground,
            Semantic.Color.HoverBackground,
            Semantic.Radius.Card.toInt(),
        )
        contentDescription = model.accessibilityLabel.ifBlank {
            listOf(model.title, model.supportingText, statusLabel)
                .filter(String::isNotBlank)
                .joinToString(", ")
        }
        isClickable = onClick != null
        isFocusable = onClick != null
        setOnClickListener(if (onClick == null) null else OnClickListener { onClick() })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Keep all status cues reachable when a narrow column or larger font outgrows one line.
        val badgeWidth = listOf(progress, alarm, status).filter { it.visibility == VISIBLE }.sumOf { badge ->
            val params = badge.layoutParams as LayoutParams
            kotlin.math.ceil(Layout.getDesiredWidth(badge.text, badge.paint).toDouble()).toInt() +
                badge.paddingLeft + badge.paddingRight + params.marginStart + params.marginEnd
        }
        val direction = if (badgeWidth > textColumn.measuredWidth) VERTICAL else HORIZONTAL
        if (titleRow.orientation != direction) {
            titleRow.orientation = direction
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun configureLeading(
        model: TPlannerTaskUnitModel,
        wear: Boolean,
        onLeadingClick: (() -> Unit)?,
    ) {
        if (wear || (model.isTask && !model.showTaskCheckbox)) {
            leading.visibility = GONE
            leading.isClickable = false
            leading.setOnClickListener(null)
            return
        }
        leading.visibility = VISIBLE
        val target = if (onLeadingClick != null) Phone.Geometry.TouchTargetMin else Phone.Geometry.IconSize
        val params = LayoutParams(dp(if (model.isTask) target else 3f), dp(if (model.isTask) target else 28f)).apply {
            marginEnd = dp(8)
            gravity = Gravity.TOP
        }
        leading.layoutParams = params
        if (model.isTask) {
            leading.text = if (model.completed) "\u2713" else ""
            leading.textSize = Phone.Typography.Meta.FontSize
            leading.typeface = medium
            leading.setTextColor(Semantic.Color.OnAccent)
            val checkbox = roundedBackground(
                if (model.completed) Semantic.Color.Accent else Color.TRANSPARENT,
                if (model.completed) Semantic.Color.TextPrimary else Semantic.Color.BorderControl,
                Semantic.Radius.Small.toInt(),
                strokeWidthDp = if (model.completed) 1 else 2,
            )
            val inset = dp((target - Phone.Geometry.IconSize).coerceAtLeast(0f) / 2f)
            leading.background = InsetDrawable(checkbox, inset)
            leading.contentDescription = model.accessibilityLabel.ifBlank { model.title }
            leading.isFocusable = onLeadingClick != null
            leading.isClickable = onLeadingClick != null
            leading.setOnClickListener(
                if (onLeadingClick == null) null else OnClickListener { onLeadingClick() },
            )
        } else {
            leading.text = ""
            leading.isClickable = onLeadingClick != null
            leading.setOnClickListener(
                if (onLeadingClick == null) null else OnClickListener { onLeadingClick() },
            )
            leading.background = roundedBackground(model.accentColor, model.accentColor, 1)
        }
    }

    private fun configureProgress(model: TPlannerTaskUnitModel) {
        val visible = model.checklistTotal > 0
        progress.visibility = if (visible) VISIBLE else GONE
        if (!visible) return
        val allDone = model.checklistDone == model.checklistTotal
        progress.text = "${model.checklistDone}/${model.checklistTotal}"
        progress.setTextColor(if (allDone) Semantic.Color.Success else Semantic.Color.Warning)
        progress.background = roundedBackground(
            if (allDone) Semantic.Color.SuccessBackground else Semantic.Color.WarningBackground,
            Color.TRANSPARENT,
            Semantic.Radius.Small.toInt(),
        )
    }

    private fun configureBadge(view: TextView, visible: Boolean, value: String, color: Int) {
        view.visibility = if (visible) VISIBLE else GONE
        if (!visible) return
        view.text = value
        view.setTextColor(color)
    }

    private fun badgeView() = TextView(context).apply {
        typeface = regular
        textSize = Phone.Typography.Meta.FontSize
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(4), dp(1), dp(4), dp(1))
    }

    private fun wrapContent(
        startMarginDp: Int = 0,
        topMarginDp: Int = 0,
    ) = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(startMarginDp)
        topMargin = dp(topMarginDp)
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radiusDp: Int,
        strokeWidthDp: Int = 1,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (Color.alpha(stroke) > 0) setStroke(dp(strokeWidthDp), stroke)
    }

    private fun rippleBackground(fill: Int, ripple: Int, radiusDp: Int): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(ripple),
            roundedBackground(fill, Semantic.Color.BorderSubtle, radiusDp),
            null,
        )

    private fun dp(value: Int): Int =
        dp(value.toFloat())

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
