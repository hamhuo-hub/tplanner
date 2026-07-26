package com.hamhuo.tplanner.timeline

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hamhuo.tplanner.BG
import com.hamhuo.tplanner.TaskEvent
import com.hamhuo.tplanner.timeline.components.TimelineAddButton
import com.hamhuo.tplanner.timeline.components.TimelineBody
import com.hamhuo.tplanner.timeline.components.TimelineDayHeader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A deterministic, non-AI schedule view. Conflicts are intentionally allowed:
 * they are narrowed and stacked instead of blocking a save or opening a dialog.
 */
@Composable
fun TimelineScreen(
    events: List<TaskEvent>,
    onEventClick: (TaskEvent) -> Unit,
    onAddEvent: (String) -> Unit,
    onEventMove: (TaskEvent, Instant, Instant) -> Unit,
    modifier: Modifier = Modifier,
    allowExpandedNavigation: Boolean = true,
    onNavigationExpandedChange: (Boolean) -> Unit = {},
    onModalVisibilityChange: (Boolean) -> Unit = {},
) {
    val zone = remember { ZoneId.systemDefault() }
    val context = LocalContext.current
    val now = rememberTimelineNow(zone)
    val today = now.toLocalDate()
    val state = rememberTimelineState(zone, today)
    val days = remember(state.firstDayEpoch) {
        List(TimelineGeometry.visibleDayCount) { index ->
            state.firstDay.plusDays(index.toLong())
        }
    }
    val visibleEvents = remember(events) { events.filter { it.deletedAt == 0L } }
    val placements = remember(visibleEvents, days, zone) {
        TimelinePlacementMapper.createDayPlacements(
            events = visibleEvents,
            days = days,
            zone = zone,
        )
    }
    val density = LocalDensity.current
    val hourHeightPx = with(density) { TimelineGeometry.hourHeight.toPx() }

    TimelineInitialScrollEffect(
        state = state,
        now = now,
        hourHeightPx = hourHeightPx,
    )

    fun openDatePicker() {
        val initialDate = days.getOrElse(days.size / 2) { LocalDate.now(zone) }
        val dialog = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                state.goToDate(LocalDate.of(year, month + 1, dayOfMonth))
            },
            initialDate.year,
            initialDate.monthValue - 1,
            initialDate.dayOfMonth,
        )
        dialog.setOnDismissListener { onModalVisibilityChange(false) }
        onModalVisibilityChange(true)
        dialog.show()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BG),
    ) {
        Column(Modifier.fillMaxSize()) {
            TimelineDayHeader(
                days = days,
                today = today,
                events = visibleEvents,
                zone = zone,
                onPrevious = state::previousPage,
                onToday = {
                    if (today in days) openDatePicker() else state.goToToday(today)
                },
                onNext = state::nextPage,
                allowExpandedControls = allowExpandedNavigation,
                onExpandedControlsChange = onNavigationExpandedChange,
            )
            TimelineBody(
                days = days,
                today = today,
                now = now,
                events = visibleEvents,
                placements = placements,
                zone = zone,
                state = state,
                hourHeightPx = hourHeightPx,
                onEventClick = onEventClick,
                onEventMove = onEventMove,
                modifier = Modifier.weight(1f),
            )
        }

        TimelineAddButton(
            onClick = { onAddEvent("event") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 52.dp),
        )
    }
}
