package com.hamhuo.tplanner

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.widget.TextView
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens

/** Activity surfaces only; watch-face artwork retains its own palette. */
internal const val WEAR_BG = TPlannerLightTokens.Semantic.Color.Canvas
internal const val WEAR_SURFACE = TPlannerLightTokens.Semantic.Color.Surface
internal const val WEAR_SURFACE2 = TPlannerLightTokens.Semantic.Color.Raised
internal const val WEAR_CONTROL = TPlannerLightTokens.Semantic.Color.Surface
internal const val WEAR_INPUT = TPlannerLightTokens.Semantic.Color.Input
internal const val WEAR_PRIMARY = TPlannerLightTokens.Semantic.Color.TextPrimary
internal const val WEAR_DIM = TPlannerLightTokens.Semantic.Color.TextSecondary
internal const val WEAR_MUTED = TPlannerLightTokens.Semantic.Color.TextMuted
internal const val WEAR_ACCENT = TPlannerLightTokens.Semantic.Color.Accent
internal const val WEAR_ACCENT_TEXT = TPlannerLightTokens.Semantic.Color.AccentText
internal const val WEAR_ON_ACCENT = TPlannerLightTokens.Semantic.Color.OnAccent
internal const val WEAR_BLUE = TPlannerLightTokens.Semantic.Color.Info
internal const val WEAR_TEAL = TPlannerLightTokens.Semantic.Color.Success
internal const val WEAR_RED = TPlannerLightTokens.Semantic.Color.Error
internal const val WEAR_ERROR_BACKGROUND = TPlannerLightTokens.Semantic.Color.ErrorBackground
internal const val WEAR_BORDER = TPlannerLightTokens.Semantic.Color.BorderControl
internal const val WEAR_SUBTLE_BORDER = TPlannerLightTokens.Semantic.Color.BorderSubtle
internal const val WEAR_CONTROL_PRESSED = TPlannerLightTokens.Semantic.Color.HoverBackground
internal const val WEAR_ACCENT_PRESSED = TPlannerLightTokens.Semantic.Color.AccentPressed

internal val WEAR_REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
internal val WEAR_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
internal val WEAR_SEMIBOLD: Typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    Typeface.create(WEAR_REGULAR, TPlannerLightTokens.Platform.Wear.Typography.Heading.FontWeight, false)
} else {
    Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

internal fun TextView.wearTextMetrics(
    lineHeight: Float = TPlannerLightTokens.Platform.Wear.Typography.Body.LineHeight,
) {
    includeFontPadding = false
    letterSpacing = 0f
    setLineSpacing(0f, lineHeight)
}

internal fun Context.wearDp(value: Float): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.wearSurfaceBackground(
    fill: Int = WEAR_SURFACE2,
    radiusPx: Float = wearDp(TPlannerLightTokens.Semantic.Radius.Card).toFloat(),
    border: Int = WEAR_SUBTLE_BORDER,
    borderWidthDp: Float = TPlannerLightTokens.Semantic.Stroke.Control,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radiusPx
    setColor(fill)
    setStroke(wearDp(borderWidthDp), border)
}

internal fun Context.wearInteractiveBackground(
    fill: Int = WEAR_CONTROL,
    radiusPx: Float = wearDp(TPlannerLightTokens.Semantic.Radius.Control).toFloat(),
    border: Int = WEAR_BORDER,
    pressed: Int = WEAR_CONTROL_PRESSED,
): RippleDrawable {
    val focusInset = wearDp(
        TPlannerLightTokens.Component.Button.Focus.Width + TPlannerLightTokens.Component.Button.Focus.Offset,
    )
    val focused = LayerDrawable(arrayOf(
        wearSurfaceBackground(
            WEAR_BG, radiusPx, TPlannerLightTokens.Component.Button.Focus.Color,
            TPlannerLightTokens.Component.Button.Focus.Width,
        ),
        wearSurfaceBackground(fill, radiusPx, border),
    )).apply {
        // The canvas gap separates the focus ring from orange/category fills. Keep the
        // inner control border and the View's existing padding and 48dp touch bounds.
        setLayerInset(1, focusInset, focusInset, focusInset, focusInset)
        setPadding(0, 0, 0, 0)
    }
    val states = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), wearSurfaceBackground(
            TPlannerLightTokens.Semantic.Color.DisabledBackground, radiusPx, WEAR_SUBTLE_BORDER,
        ))
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), wearSurfaceBackground(fill, radiusPx, border))
    }
    return RippleDrawable(ColorStateList.valueOf(pressed), states, null)
}

internal fun TextView.wearButtonStyle(primary: Boolean = false) {
    val colors = TPlannerLightTokens.Semantic.Color
    minimumWidth = context.wearDp(TPlannerLightTokens.Platform.Wear.Geometry.TouchTargetMin)
    minimumHeight = context.wearDp(TPlannerLightTokens.Platform.Wear.Geometry.ControlMinHeight)
    textSize = TPlannerLightTokens.Platform.Wear.Typography.Body.FontSize
    typeface = WEAR_MEDIUM
    wearTextMetrics()
    gravity = Gravity.CENTER
    maxLines = 3
    setPadding(context.wearDp(12f), context.wearDp(10f), context.wearDp(12f), context.wearDp(10f))
    setTextColor(ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(colors.DisabledForeground, if (primary) WEAR_ON_ACCENT else WEAR_ACCENT_TEXT),
    ))
    background = context.wearInteractiveBackground(
        fill = if (primary) WEAR_ACCENT else WEAR_CONTROL,
        border = if (primary) TPlannerLightTokens.Component.Button.Primary.Border else WEAR_BORDER,
        pressed = if (primary) WEAR_ACCENT_PRESSED else WEAR_CONTROL_PRESSED,
    )
    isClickable = true
    isFocusable = true
}
