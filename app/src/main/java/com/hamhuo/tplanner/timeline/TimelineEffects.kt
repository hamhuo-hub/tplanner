package com.hamhuo.tplanner.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

@Composable
internal fun rememberTimelineNow(zone: ZoneId): ZonedDateTime {
    var currentEpochMinute by remember {
        mutableStateOf(System.currentTimeMillis() / 60_000L)
    }

    LaunchedEffect(zone) {
        while (true) {
            val waitMillis = 60_000L - System.currentTimeMillis() % 60_000L
            delay(waitMillis)
            currentEpochMinute = System.currentTimeMillis() / 60_000L
        }
    }

    return remember(currentEpochMinute, zone) { ZonedDateTime.now(zone) }
}

@Composable
internal fun TimelineInitialScrollEffect(
    state: TimelineState,
    now: ZonedDateTime,
    hourHeightPx: Float,
) {
    LaunchedEffect(state.scrollState.maxValue) {
        if (state.initialScrollDone || state.scrollState.maxValue <= 0) {
            return@LaunchedEffect
        }

        val minutes = now.hour * 60 + now.minute
        val target = (((minutes - 60).coerceAtLeast(0) / 60f) * hourHeightPx)
            .roundToInt()
            .coerceIn(0, state.scrollState.maxValue)
        state.scrollState.scrollTo(target)
        state.markInitialScrollDone()
    }
}
