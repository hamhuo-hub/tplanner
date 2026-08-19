package com.hamhuo.tplanner.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

enum class TPlannerTaskUnitVariant {
    PHONE,
    WEAR,
}

data class TPlannerTaskUnitModel(
    val title: String,
    val supportingText: String,
    val isTask: Boolean = true,
    val completed: Boolean = false,
    val past: Boolean = false,
    val current: Boolean = false,
    val accentColor: Int = TPlannerColors.Blue,
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
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)

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
        typeface = mono
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(
            title,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(progress, wrapContent(startMarginDp = 4))
        titleRow.addView(alarm, wrapContent(startMarginDp = 4))
        titleRow.addView(status, wrapContent(startMarginDp = 4))
        textColumn.addView(titleRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textColumn.addView(supporting, wrapContent(topMarginDp = 2))
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
        val horizontalPadding = if (wear) 13 else 14
        val verticalPadding = if (wear) 9 else 5
        minimumHeight = dp(if (wear) 58 else 38)
        setPadding(dp(horizontalPadding), dp(verticalPadding), dp(horizontalPadding), dp(verticalPadding))
        title.text = model.title
        title.setTextColor(if (model.completed) TPlannerColors.TextSecondary else TPlannerColors.TextPrimary)
        title.textSize = if (wear) TPlannerTypography.WearTaskTitleSp else TPlannerTypography.PhoneTaskTitleSp
        title.maxLines = if (wear) 2 else 1
        title.paintFlags = if (model.completed) {
            title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        supporting.text = model.supportingText
        supporting.visibility = if (model.supportingText.isBlank()) GONE else VISIBLE
        supporting.setTextColor(TPlannerColors.TextSecondary)
        supporting.textSize = if (wear) TPlannerTypography.WearSupportingSp else TPlannerTypography.PhoneSupportingSp

        configureLeading(model, wear, onLeadingClick)
        configureProgress(model)
        configureBadge(alarm, model.alarmEnabled, "\u25C7", TPlannerColors.Gold)
        configureBadge(
            status,
            model.statusLabel.isNotBlank(),
            model.statusLabel,
            if (model.current) TPlannerColors.BlueBright else TPlannerColors.Gold,
        )

        alpha = if (!wear && (model.completed || model.past)) 0.45f else 1f
        background = when {
            wear -> rippleBackground(TPlannerColors.SurfaceRaised, TPlannerColors.GoldGhost, TPlannerGeometry.RadiusWearDp)
            model.current -> roundedBackground(TPlannerColors.BlueGhost, TPlannerColors.BlueBorder, 0)
            else -> null
        }
        contentDescription = model.accessibilityLabel.ifBlank {
            listOf(model.title, model.supportingText, model.statusLabel)
                .filter(String::isNotBlank)
                .joinToString(", ")
        }
        isClickable = onClick != null
        isFocusable = onClick != null
        setOnClickListener(if (onClick == null) null else OnClickListener { onClick() })
    }

    private fun configureLeading(
        model: TPlannerTaskUnitModel,
        wear: Boolean,
        onLeadingClick: (() -> Unit)?,
    ) {
        if (wear) {
            leading.visibility = GONE
            return
        }
        leading.visibility = VISIBLE
        val params = LayoutParams(dp(if (model.isTask) 15 else 3), dp(if (model.isTask) 15 else 28)).apply {
            marginEnd = dp(8)
            gravity = Gravity.TOP
        }
        leading.layoutParams = params
        if (model.isTask) {
            leading.text = if (model.completed) "\u2713" else ""
            leading.textSize = 10f
            leading.typeface = medium
            leading.setTextColor(Color.BLACK)
            leading.background = roundedBackground(
                if (model.completed) TPlannerColors.Gold else Color.TRANSPARENT,
                if (model.completed) TPlannerColors.Gold else TPlannerColors.Border,
                TPlannerGeometry.RadiusSmallDp,
                strokeWidthDp = if (model.completed) 1 else 2,
            )
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
        progress.setTextColor(if (allDone) TPlannerColors.Green else TPlannerColors.GoldDark)
        progress.background = roundedBackground(
            if (allDone) TPlannerColors.GreenGhost else TPlannerColors.GoldGhost,
            Color.TRANSPARENT,
            TPlannerGeometry.RadiusSmallDp,
        )
    }

    private fun configureBadge(view: TextView, visible: Boolean, value: String, color: Int) {
        view.visibility = if (visible) VISIBLE else GONE
        if (!visible) return
        view.text = value
        view.setTextColor(color)
    }

    private fun badgeView() = TextView(context).apply {
        typeface = mono
        textSize = TPlannerTypography.PhoneBadgeSp
        includeFontPadding = false
        maxLines = 1
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
            roundedBackground(fill, TPlannerColors.Border, radiusDp),
            null,
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
