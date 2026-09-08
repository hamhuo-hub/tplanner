package com.hamhuo.tplanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGesturesPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens as Tokens
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.PhoneGeometry as TPlannerGeometry
import com.hamhuo.tplanner.PhoneTypography as TPlannerTypography

/**
 * Lets the screen coordinate the primary navigation with other transient chrome.
 */
enum class PhoneTabBarPresentation {
    HandleOnly,
    Expanded,
    Hidden,
}

/**
 * Temporary primary navigation for phones.
 *
 * Destination labels intentionally live here, so the second destination remains
 * "Inbox" instead of changing with its currently selected child list.
 */
@Composable
fun PhoneTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    presentation: PhoneTabBarPresentation = PhoneTabBarPresentation.HandleOnly,
    onExpandRequest: () -> Unit = {},
) {
    val labels = listOf(
        stringResource(R.string.tab_journal),
        stringResource(R.string.list_inbox),
        stringResource(R.string.tab_timeline),
    )

    if (presentation == PhoneTabBarPresentation.Hidden) return
    val expanded = presentation == PhoneTabBarPresentation.Expanded

    Box(
        modifier = modifier
            .fillMaxWidth()
            .systemGesturesPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(Tokens.Semantic.Motion.Fast.toInt())) + expandHorizontally(
                animationSpec = tween(Tokens.Semantic.Motion.Standard.toInt()),
                expandFrom = Alignment.CenterHorizontally,
            ),
            exit = fadeOut(tween(Tokens.Semantic.Motion.Fast.toInt())) + shrinkHorizontally(
                animationSpec = tween(Tokens.Semantic.Motion.Standard.toInt()),
                shrinkTowards = Alignment.CenterHorizontally,
            ),
        ) {
            NavigationIsland(
                labels = labels,
                selected = selected,
                onSelect = onSelect,
            )
        }

        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn(tween(Tokens.Semantic.Motion.Standard.toInt())),
            exit = fadeOut(tween(Tokens.Semantic.Motion.Fast.toInt())),
        ) {
            NavigationHandle(
                description = labels.joinToString(separator = " / "),
                onReveal = onExpandRequest,
            )
        }
    }
}

@Composable
private fun NavigationIsland(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val islandShape = RoundedCornerShape(TPlannerGeometry.RadiusNavigationContainerDp.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .widthIn(max = 420.dp)
            .shadow(
                elevation = Tokens.Component.Panel.ShadowBlur.dp,
                shape = islandShape,
                ambientColor = Color(Tokens.Component.Panel.ShadowColor).copy(alpha = Tokens.Component.Panel.ShadowOpacity),
                spotColor = Color(Tokens.Component.Panel.ShadowColor).copy(alpha = Tokens.Component.Panel.ShadowOpacity),
            )
            .background(SURFACE, islandShape)
            .border(1.dp, BORDER_SUBTLE, islandShape)
            .padding(Tokens.Semantic.Spacing.InlineTight.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            val itemShape = RoundedCornerShape(TPlannerGeometry.RadiusNavigationItemDp.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)
                    .clip(itemShape)
                    .background(
                        color = if (isSelected) GOLD else SURFACE,
                        shape = itemShape,
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) FOCUS else BORDER,
                        shape = itemShape,
                    )
                    .clickable(
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = Tokens.Semantic.Spacing.Inline.dp, vertical = Tokens.Semantic.Spacing.Inline.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) ON_ACCENT else DIM,
                    fontSize = TPlannerTypography.PhoneMetaSp.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = TPlannerTypography.PhoneLetterSpacingSp.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NavigationHandle(
    description: String,
    onReveal: () -> Unit,
) {
    val revealThreshold = 12.dp

    Box(
        modifier = Modifier
            .width(76.dp)
            .height(Tokens.Platform.Phone.Geometry.TouchTargetMin.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    onReveal()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onReveal() })
            }
            .pointerInput(revealThreshold) {
                val revealThresholdPx = revealThreshold.toPx()
                var upwardDrag = 0f
                var revealed = false
                detectVerticalDragGestures(
                    onDragStart = {
                        upwardDrag = 0f
                        revealed = false
                    },
                    onDragEnd = {
                        upwardDrag = 0f
                        revealed = false
                    },
                    onDragCancel = {
                        upwardDrag = 0f
                        revealed = false
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (!revealed && dragAmount < 0f) {
                            upwardDrag -= dragAmount
                            if (upwardDrag >= revealThresholdPx) {
                                change.consume()
                                revealed = true
                                onReveal()
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(4.dp)
                .background(DRAG_HANDLE, RoundedCornerShape(TPlannerGeometry.RadiusPillDp.dp)),
        )
    }
}
