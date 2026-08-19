package com.hamhuo.tplanner.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.hamhuo.tplanner.designsystem.TPlannerSyncFeedbackTone
import com.hamhuo.tplanner.designsystem.TPlannerSyncFeedbackView
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitModel
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitVariant
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitView

data class TPlannerSyncFeedbackPresentation(
    val generation: Int,
    val message: String,
    val tone: TPlannerSyncFeedbackTone,
)

/** Compose entry point for the same compact sync-result view used by Wear. */
@Composable
fun TPlannerSyncFeedback(
    presentation: TPlannerSyncFeedbackPresentation,
    modifier: Modifier = Modifier,
) {
    key(presentation.generation) {
        AndroidView(
            factory = { context ->
                TPlannerSyncFeedbackView(context).apply {
                    show(presentation.message, presentation.tone, autoHide = true)
                }
            },
            modifier = modifier,
        )
    }
}

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
