package com.hamhuo.tplanner.timeline.components

import androidx.compose.ui.graphics.Color
import com.hamhuo.tplanner.designsystem.TPlannerCategories
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens

/** Shared visual mapping for timed cards and status strips; no layout or item state is changed. */
internal data class TimelineItemVisuals(
    val background: Color,
    val foreground: Color,
    val supportingForeground: Color,
    val border: Color,
)

internal fun timelineItemVisuals(
    colorId: Int,
    completed: Boolean,
    current: Boolean = false,
    selected: Boolean = false,
    highlighted: Boolean = false,
): TimelineItemVisuals {
    val category = TPlannerCategories.forColorId(colorId)
    return TimelineItemVisuals(
        background = Color(when {
            selected -> TPlannerLightTokens.Component.Task.SelectedBackground
            completed -> TPlannerLightTokens.Component.Task.NormalBackground
            current -> TPlannerLightTokens.Component.Task.CurrentBackground
            else -> category.background
        }),
        foreground = Color(when {
            completed -> TPlannerLightTokens.Component.Task.CompletedForeground
            selected || current -> TPlannerLightTokens.Component.Task.Foreground
            else -> category.foreground
        }),
        supportingForeground = Color(when {
            completed -> TPlannerLightTokens.Component.Task.CompletedForeground
            selected -> TPlannerLightTokens.Component.Task.SupportingForeground
            current -> TPlannerLightTokens.Component.Task.CurrentForeground
            else -> category.foreground
        }),
        border = Color(when {
            highlighted -> TPlannerLightTokens.Semantic.Color.Error
            selected -> TPlannerLightTokens.Component.Task.SelectedBorder
            completed -> TPlannerLightTokens.Semantic.Color.BorderControl
            current -> TPlannerLightTokens.Component.Task.CurrentForeground
            else -> category.border
        }),
    )
}
