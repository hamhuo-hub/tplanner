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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGesturesPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
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
            enter = fadeIn(tween(160)) + expandHorizontally(
                animationSpec = tween(210),
                expandFrom = Alignment.CenterHorizontally,
            ),
            exit = fadeOut(tween(120)) + shrinkHorizontally(
                animationSpec = tween(170),
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
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(90)),
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
    val islandShape = RoundedCornerShape(28.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .widthIn(max = 420.dp)
            .height(54.dp)
            .shadow(
                elevation = 14.dp,
                shape = islandShape,
                ambientColor = BG,
                spotColor = BG,
            )
            .background(SURFACE.copy(alpha = 0.96f), islandShape)
            .border(1.dp, BORDER, islandShape)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            val itemShape = RoundedCornerShape(22.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        color = if (isSelected) GOLD else SURFACE,
                        shape = itemShape,
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) GOLD else BORDER,
                        shape = itemShape,
                    )
                    .clickable(
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) BG else DIM,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.2.sp,
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
            .height(36.dp)
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
                .background(DIM.copy(alpha = 0.82f), RoundedCornerShape(50)),
        )
    }
}
