package com.hamhuo.tplanner.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitModel
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitVariant
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitView

/** Compose entry point for the cross-phone-and-Wear task unit. */
@Composable
fun TPlannerTaskUnit(
    model: TPlannerTaskUnitModel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLeadingClick: (() -> Unit)? = null,
) {
    AndroidView(
        factory = ::TPlannerTaskUnitView,
        modifier = modifier,
        update = { view ->
            view.render(
                model = model,
                variant = TPlannerTaskUnitVariant.PHONE,
                onClick = onClick,
                onLeadingClick = onLeadingClick,
            )
        },
    )
}
