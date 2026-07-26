package com.hamhuo.tplanner.timeline.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.BORDER
import com.hamhuo.tplanner.DIM
import com.hamhuo.tplanner.EVENT_COLORS
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.SURFACE
import com.hamhuo.tplanner.SURFACE2
import com.hamhuo.tplanner.TaskEvent
import com.hamhuo.tplanner.timeline.TimelineGeometry
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val NavigationButtonSize = 32.dp
private const val ControlsVisibleMillis = 1_800L

/**
 * Timeline 的日期坐标与页内导航。
 *
 * 横向手势只绑定在本表头，不会与下方事件卡片的拖拽竞争。外层一级导航展开时，
 * 传入 [allowExpandedControls] = false 即可保持最简日期坐标，翻页手势仍然可用。
 */
@Composable
internal fun TimelineDayHeader(
    days: List<LocalDate>,
    today: LocalDate,
    events: List<TaskEvent>,
    zone: ZoneId,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
    allowExpandedControls: Boolean = true,
    onExpandedControlsChange: (Boolean) -> Unit = {},
) {
    if (days.isEmpty()) return

    val locale = Locale.getDefault()
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEE d", locale) }
    val monthFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMM yyyy", locale) }
    val shortMonthFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMM", locale) }
    val swipeThresholdPx = with(LocalDensity.current) { 44.dp.toPx() }
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnToday by rememberUpdatedState(onToday)
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnExpandedControlsChange by rememberUpdatedState(onExpandedControlsChange)

    var controlsVisible by remember { mutableStateOf(false) }
    var controlsRevealId by remember { mutableIntStateOf(0) }
    var navigationDirection by remember { mutableIntStateOf(0) }
    val expandedControlsVisible = controlsVisible && allowExpandedControls

    fun revealControls() {
        if (allowExpandedControls) {
            controlsVisible = true
            controlsRevealId += 1
        }
    }

    fun navigatePrevious() {
        navigationDirection = -1
        currentOnPrevious()
        revealControls()
    }

    fun navigateNext() {
        navigationDirection = 1
        currentOnNext()
        revealControls()
    }

    fun navigateToday() {
        val todayPageStart = today.minusDays((days.size / 2).toLong())
        navigationDirection = when {
            todayPageStart < days.first() -> -1
            todayPageStart > days.first() -> 1
            else -> 0
        }
        currentOnToday()
        revealControls()
    }

    LaunchedEffect(controlsRevealId, allowExpandedControls) {
        if (!allowExpandedControls) {
            controlsVisible = false
            return@LaunchedEffect
        }
        if (controlsVisible) {
            delay(ControlsVisibleMillis)
            controlsVisible = false
        }
    }

    LaunchedEffect(expandedControlsVisible) {
        currentOnExpandedControlsChange(expandedControlsVisible)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TimelineGeometry.dayHeaderHeight)
            .background(SURFACE2)
            .border(1.dp, BORDER)
            .pointerInput(swipeThresholdPx, allowExpandedControls, days) {
                var horizontalDragPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDragPx = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        horizontalDragPx += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        when {
                            horizontalDragPx <= -swipeThresholdPx -> navigateNext()
                            horizontalDragPx >= swipeThresholdPx -> navigatePrevious()
                            abs(horizontalDragPx) > 0f -> revealControls()
                        }
                        horizontalDragPx = 0f
                    },
                    onDragCancel = { horizontalDragPx = 0f },
                )
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            Spacer(
                Modifier
                    .width(TimelineGeometry.timeGutterWidth)
                    .fillMaxHeight(),
            )
            AnimatedContent(
                targetState = days,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                transitionSpec = {
                    when {
                        navigationDirection > 0 ->
                            (slideInHorizontally { it / 2 } + fadeIn())
                                .togetherWith(slideOutHorizontally { -it / 2 } + fadeOut())

                        navigationDirection < 0 ->
                            (slideInHorizontally { -it / 2 } + fadeIn())
                                .togetherWith(slideOutHorizontally { it / 2 } + fadeOut())

                        else -> fadeIn().togetherWith(fadeOut())
                    }
                },
                label = "timeline-date-axis",
            ) { visibleDays ->
                TimelineDateCells(
                    days = visibleDays,
                    today = today,
                    events = events,
                    zone = zone,
                    locale = locale,
                    dateFormatter = dateFormatter,
                    monthFormatter = monthFormatter,
                    shortMonthFormatter = shortMonthFormatter,
                    onHeaderClick = ::revealControls,
                    onMiddleClick = ::navigateToday,
                )
            }
        }

        AnimatedVisibility(
            visible = expandedControlsVisible,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = TimelineGeometry.timeGutterWidth + 3.dp, end = 3.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                TimelineNavigationButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(R.string.timeline_previous_days),
                            tint = GOLD,
                        )
                    },
                    onClick = ::navigatePrevious,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                TimelineNavigationButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.timeline_next_days),
                            tint = GOLD,
                        )
                    },
                    onClick = ::navigateNext,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

@Composable
private fun TimelineDateCells(
    days: List<LocalDate>,
    today: LocalDate,
    events: List<TaskEvent>,
    zone: ZoneId,
    locale: Locale,
    dateFormatter: DateTimeFormatter,
    monthFormatter: DateTimeFormatter,
    shortMonthFormatter: DateTimeFormatter,
    onHeaderClick: () -> Unit,
    onMiddleClick: () -> Unit,
) {
    val middleIndex = days.size / 2
    val middleMonth = days[middleIndex].month
    val todayActionLabel = stringResource(R.string.timeline_today)
    Row(Modifier.fillMaxSize()) {
        days.forEachIndexed { index, day ->
            val dayStart = day.atStartOfDay(zone).toInstant()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
            val status = events.firstOrNull {
                it.type == "status" &&
                    it.deletedAt == 0L &&
                    it.start.isBefore(dayEnd) &&
                    it.end.isAfter(dayStart)
            }
            val isToday = day == today
            val isMiddle = index == middleIndex
            val dateText = buildString {
                append(day.format(dateFormatter).uppercase(locale))
                if (isMiddle) {
                    append(" · ")
                    append(day.format(monthFormatter).uppercase(locale))
                } else if (day.month != middleMonth) {
                    append(" ")
                    append(day.format(shortMonthFormatter).uppercase(locale))
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(0.5.dp, BORDER)
                    .clickable(
                        onClickLabel = if (isMiddle) todayActionLabel else null,
                        role = Role.Button,
                        onClick = if (isMiddle) onMiddleClick else onHeaderClick,
                    )
                    .padding(horizontal = 3.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(25.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            when {
                                isToday -> GOLD
                                isMiddle -> SURFACE
                                else -> Color.Transparent
                            },
                        )
                        .then(
                            if (isMiddle && !isToday) {
                                Modifier.border(1.dp, DIM, RoundedCornerShape(50))
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = if (isMiddle) 6.dp else 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dateText,
                        color = if (isToday) Color(0xFF0E0E0E) else Color(0xFFE8E0D0),
                        fontSize = if (isMiddle) 8.5.sp else 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                status?.let {
                    Text(
                        text = it.title,
                        modifier = Modifier.padding(top = 1.dp),
                        color = EVENT_COLORS.getOrElse(it.colorId) { GOLD },
                        fontSize = 7.5.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineNavigationButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(NavigationButtonSize)
            .background(SURFACE.copy(alpha = 0.96f), CircleShape)
            .border(1.dp, GOLD.copy(alpha = 0.75f), CircleShape),
    ) {
        icon()
    }
}
