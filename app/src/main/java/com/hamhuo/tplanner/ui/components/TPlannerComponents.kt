package com.hamhuo.tplanner.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens as Tokens
import com.hamhuo.tplanner.designsystem.TPlannerSyncFeedbackTone
import com.hamhuo.tplanner.designsystem.TPlannerSyncFeedbackView
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitModel
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitVariant
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitView

enum class TPlannerButtonStyle { Primary, Secondary, Destructive }

/** A single touch target and explicit foreground for every phone action. */
@Composable
fun TPlannerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TPlannerButtonStyle = TPlannerButtonStyle.Primary,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val background = when (style) {
        TPlannerButtonStyle.Primary -> if (pressed) Tokens.Component.Button.Primary.PressedBackground else Tokens.Component.Button.Primary.Background
        TPlannerButtonStyle.Secondary -> Tokens.Component.Button.Secondary.Background
        TPlannerButtonStyle.Destructive -> Tokens.Semantic.Color.ErrorBackground
    }
    val foreground = when (style) {
        TPlannerButtonStyle.Primary -> Tokens.Component.Button.Primary.Foreground
        TPlannerButtonStyle.Secondary -> Tokens.Component.Button.Secondary.Foreground
        TPlannerButtonStyle.Destructive -> Tokens.Semantic.Color.Error
    }
    val border = when (style) {
        TPlannerButtonStyle.Primary -> Tokens.Component.Button.Primary.Border
        TPlannerButtonStyle.Secondary -> Tokens.Component.Button.Secondary.Border
        TPlannerButtonStyle.Destructive -> Tokens.Semantic.Color.Error
    }
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = Tokens.Platform.Phone.Geometry.ControlMinHeight.dp)
            .drawWithContent {
                drawContent()
                if (focused) {
                    val stroke = Tokens.Component.Button.Focus.Width.dp.toPx()
                    val inset = Tokens.Component.Button.Focus.Offset.dp.toPx() + stroke / 2f
                    val radius = Tokens.Component.Button.Radius.dp.toPx() + inset
                    drawRoundRect(
                        color = Color(Tokens.Component.Button.Focus.Color),
                        topLeft = Offset(-inset, -inset),
                        size = Size(size.width + inset * 2f, size.height + inset * 2f),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(stroke),
                    )
                }
            },
        shape = RoundedCornerShape(Tokens.Component.Button.Radius.dp),
        interactionSource = interactions,
        border = BorderStroke(
            Tokens.Semantic.Stroke.Control.dp,
            Color(if (enabled) border else Tokens.Semantic.Color.BorderControl),
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(background), contentColor = Color(foreground),
            disabledContainerColor = Color(Tokens.Component.Button.Disabled.Background),
            disabledContentColor = Color(Tokens.Component.Button.Disabled.Foreground),
        ),
        contentPadding = PaddingValues(
            horizontal = Tokens.Platform.Phone.Geometry.RowPaddingInline.dp,
            vertical = Tokens.Semantic.Spacing.Inline.dp,
        ),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun TPlannerIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color(Tokens.Semantic.Color.TextSecondary),
) {
    IconButton(onClick, modifier.size(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(Tokens.Platform.Phone.Geometry.IconSize.dp))
    }
}

/** Frame for existing editors: keeps their selection, IME and persistence behavior intact. */
@Composable
fun TPlannerInputFrame(
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Tokens.Component.Field.Radius.dp)
    Box(
        modifier.heightIn(min = Tokens.Platform.Phone.Geometry.ControlMinHeight.dp)
            .background(Color(Tokens.Component.Field.Background), shape)
            .border(
                if (focused) Tokens.Semantic.Stroke.Focus.dp else Tokens.Semantic.Stroke.Control.dp,
                Color(if (focused) Tokens.Component.Field.FocusBorder else Tokens.Component.Field.Border), shape,
            ),
        content = content,
    )
}

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
