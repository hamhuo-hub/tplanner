package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.BORDER
import com.hamhuo.tplanner.BG
import com.hamhuo.tplanner.DIM
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.SURFACE2
import com.hamhuo.tplanner.TEXT_EDITOR
import com.hamhuo.tplanner.designsystem.TPlannerGeometry
import com.hamhuo.tplanner.designsystem.TPlannerTypography
import com.hamhuo.tplanner.timeline.TIMELINE_DATE_WINDOW_CENTER
import com.hamhuo.tplanner.timeline.TIMELINE_DATE_WINDOW_COUNT
import com.hamhuo.tplanner.timeline.TimelineGeometry
import com.hamhuo.tplanner.timeline.timelineDateAtIndex
import com.hamhuo.tplanner.timeline.timelineDateFitsWindow
import com.hamhuo.tplanner.timeline.timelineDateIndex
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DateCellWidth = 46.dp

/**
 * A scrollable date axis for the single-day timeline.
 *
 * Scrolling only moves the date strip. The timeline changes after a date is
 * tapped, so horizontal browsing never competes with event-card dragging.
 */
@Composable
internal fun TimelineDayHeader(
    selectedDay: LocalDate,
    today: LocalDate,
    onDaySelected: (LocalDate) -> Unit,
    onCalendarClick: () -> Unit,
    allowExpandedControls: Boolean = true,
    onExpandedControlsChange: (Boolean) -> Unit = {},
) {
    val locale = Locale.getDefault()
    val weekdayFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("EEE", locale)
    }
    val monthFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("MMM", locale)
    }
    var dateWindowAnchor by remember { mutableStateOf(selectedDay) }
    val selectedIndex = remember(dateWindowAnchor, selectedDay) {
        timelineDateIndex(dateWindowAnchor, selectedDay)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - 3).coerceAtLeast(0),
    )
    val currentOnExpandedControlsChange by rememberUpdatedState(onExpandedControlsChange)

    LaunchedEffect(selectedDay, dateWindowAnchor) {
        if (!timelineDateFitsWindow(dateWindowAnchor, selectedDay)) {
            dateWindowAnchor = selectedDay
        }
    }

    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem((selectedIndex - 3).coerceAtLeast(0))
    }

    LaunchedEffect(allowExpandedControls) {
        currentOnExpandedControlsChange(false)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TimelineGeometry.dayHeaderHeight)
            .background(SURFACE2)
            .border(1.dp, BORDER),
    ) {
        CalendarAnchor(
            selectedDay = selectedDay,
            monthFormatter = monthFormatter,
            onClick = onCalendarClick,
        )

        LazyRow(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(
                count = TIMELINE_DATE_WINDOW_COUNT,
                key = { index ->
                    dateWindowAnchor.toEpochDay() +
                        index -
                        TIMELINE_DATE_WINDOW_CENTER
                },
            ) { index ->
                val day = timelineDateAtIndex(dateWindowAnchor, index)
                TimelineDateCell(
                    day = day,
                    selected = day == selectedDay,
                    today = day == today,
                    weekdayFormatter = weekdayFormatter,
                    locale = locale,
                    onClick = { onDaySelected(day) },
                )
            }
        }
    }
}

@Composable
private fun CalendarAnchor(
    selectedDay: LocalDate,
    monthFormatter: DateTimeFormatter,
    onClick: () -> Unit,
) {
    val todayLabel = stringResource(R.string.timeline_today)
    Column(
        modifier = Modifier
            .width(TimelineGeometry.timeGutterWidth)
            .fillMaxHeight()
            .clickable(
                onClickLabel = todayLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = selectedDay.format(monthFormatter).uppercase(Locale.getDefault()),
            color = GOLD,
            fontSize = TPlannerTypography.TimelineTimeSp.sp,
            lineHeight = TPlannerTypography.TimelineHourLineHeightSp.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = todayLabel.uppercase(Locale.getDefault()),
            color = DIM,
            fontSize = TPlannerTypography.TimelineWeekdaySp.sp,
            lineHeight = TPlannerTypography.TimelineTimeSp.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun TimelineDateCell(
    day: LocalDate,
    selected: Boolean,
    today: Boolean,
    weekdayFormatter: DateTimeFormatter,
    locale: Locale,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(TPlannerGeometry.RadiusFieldDp.dp)
    val foreground = when {
        selected -> BG
        today -> GOLD
        else -> TEXT_EDITOR
    }

    Column(
        modifier = Modifier
            .width(DateCellWidth)
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(if (selected) GOLD else Color.Transparent)
            .then(
                if (today && !selected) {
                    Modifier.border(1.dp, GOLD.copy(alpha = 0.75f), shape)
                } else {
                    Modifier
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = day.format(weekdayFormatter).uppercase(locale),
            color = foreground.copy(alpha = if (selected) 0.72f else 0.78f),
            fontSize = TPlannerTypography.TimelineMonthSp.sp,
            lineHeight = TPlannerTypography.TimelineTimeSp.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            text = day.dayOfMonth.toString(),
            color = foreground,
            fontSize = TPlannerTypography.TimelineDaySp.sp,
            lineHeight = TPlannerTypography.TimelineDayLineHeightSp.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
    }
}
