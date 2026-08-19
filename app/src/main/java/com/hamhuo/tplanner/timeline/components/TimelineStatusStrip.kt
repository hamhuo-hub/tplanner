package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.EVENT_COLORS
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.ScheduleItem
import com.hamhuo.tplanner.SURFACE2
import com.hamhuo.tplanner.timeline.TimelineGeometry
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** PC-style status bars shown above the timed grid and openable like any item. */
@Composable
internal fun TimelineStatusStrip(
    day: LocalDate,
    events: List<ScheduleItem>,
    zone: ZoneId,
    onEventClick: (ScheduleItem) -> Unit,
) {
    val statuses = remember(day, events, zone) {
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        events.asSequence()
            .filter { it.type == "status" && it.deletedAt == 0L }
            .filter { it.start.isBefore(dayEnd) && it.end.isAfter(dayStart) }
            .sortedWith(compareBy<ScheduleItem>({ it.start }, { it.end }, { it.id }))
            .toList()
    }
    if (statuses.isEmpty()) return

    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val untitledLabel = stringResource(R.string.untitled_event)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE2)
            .padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        statuses.forEach { status ->
            val accent = EVENT_COLORS.getOrElse(status.colorId) { GOLD }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(TimelineGeometry.timeGutterWidth))
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                        .background(accent, RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) { onEventClick(status) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = status.title.ifBlank { untitledLabel },
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${status.start.atZone(zone).format(timeFormatter)}–${status.end.atZone(zone).format(timeFormatter)}",
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
