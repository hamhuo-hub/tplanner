package com.hamhuo.tplanner.timeline

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hamhuo.tplanner.TaskEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Stable
internal class TimelineState(
    private val firstDayEpochState: MutableState<Long>,
    private val initialScrollDoneState: MutableState<Boolean>,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    val scrollState: ScrollState,
) {
    val firstDayEpoch: Long
        get() = firstDayEpochState.value

    val firstDay: LocalDate
        get() = LocalDate.ofEpochDay(firstDayEpoch)

    var highlight by mutableStateOf<ConflictHighlight?>(null)
        private set

    var draggingEventId by mutableStateOf<String?>(null)
        private set

    var viewportTopPx by mutableStateOf(0f)
        private set

    var viewportHeightPx by mutableStateOf(0f)
        private set

    val initialScrollDone: Boolean
        get() = initialScrollDoneState.value

    private var clearHighlightJob: Job? = null

    fun previousPage() {
        firstDayEpochState.value = firstDay
            .minusDays(1)
            .toEpochDay()
        clearHighlight()
    }

    fun nextPage() {
        firstDayEpochState.value = firstDay
            .plusDays(1)
            .toEpochDay()
        clearHighlight()
    }

    fun goToToday(today: LocalDate) {
        goToDate(today)
    }

    fun goToDate(date: LocalDate) {
        firstDayEpochState.value = date.minusDays(1).toEpochDay()
        clearHighlight()
    }

    fun updateViewport(topPx: Float, heightPx: Float) {
        viewportTopPx = topPx
        viewportHeightPx = heightPx
    }

    fun setDraggingEvent(eventId: String?) {
        draggingEventId = eventId
    }

    fun markInitialScrollDone() {
        initialScrollDoneState.value = true
    }

    fun showConflict(
        source: DayPlacement,
        visibleEvents: List<TaskEvent>,
        hourHeightPx: Float,
    ) {
        val sourceEvent = source.placement.event
        val peers = visibleEvents.filter { it.id in source.placement.conflictIds }
        if (peers.isEmpty()) return

        val start = peers
            .minOfOrNull { peer -> maxOf(sourceEvent.start, peer.start, source.dayStart) }
            ?: source.placement.visibleStart
        val end = peers
            .maxOfOrNull { peer -> minOf(sourceEvent.end, peer.end, source.dayEnd) }
            ?: source.placement.visibleEnd
        if (!end.isAfter(start)) return

        val nextHighlight = ConflictHighlight(
            day = source.day,
            eventIds = source.placement.conflictIds + sourceEvent.id,
            start = start,
            end = end,
        )
        clearHighlightJob?.cancel()
        highlight = nextHighlight

        val startMinutes = timelineWallClockMinutes(start, source.day, zone)
        val targetScroll = ((startMinutes / 60f) * hourHeightPx - hourHeightPx)
            .roundToInt()
            .coerceIn(0, scrollState.maxValue)

        clearHighlightJob = scope.launch {
            scrollState.animateScrollTo(targetScroll)
            delay(3_000)
            if (highlight == nextHighlight) highlight = null
        }
    }

    private fun clearHighlight() {
        clearHighlightJob?.cancel()
        clearHighlightJob = null
        highlight = null
    }
}

@Composable
internal fun rememberTimelineState(
    zone: ZoneId,
    today: LocalDate,
): TimelineState {
    val firstDayEpochState = rememberSaveable {
        mutableStateOf(today.minusDays(1).toEpochDay())
    }
    val initialScrollDoneState = rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    return remember(
        firstDayEpochState,
        initialScrollDoneState,
        zone,
        scope,
        scrollState,
    ) {
        TimelineState(
            firstDayEpochState = firstDayEpochState,
            initialScrollDoneState = initialScrollDoneState,
            zone = zone,
            scope = scope,
            scrollState = scrollState,
        )
    }
}
