package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.BORDER
import com.hamhuo.tplanner.DIM
import com.hamhuo.tplanner.EVENT_COLORS
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.SURFACE2
import com.hamhuo.tplanner.TaskEvent
import com.hamhuo.tplanner.timeline.TimelineGeometry
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun TimelineDayHeader(
    days: List<LocalDate>,
    today: LocalDate,
    events: List<TaskEvent>,
    zone: ZoneId,
) {
    val locale = Locale.getDefault()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TimelineGeometry.dayHeaderHeight)
            .background(SURFACE2)
            .border(1.dp, BORDER),
    ) {
        Box(
            Modifier
                .width(TimelineGeometry.timeGutterWidth)
                .fillMaxHeight(),
        )
        days.forEach { day ->
            val dayStart = day.atStartOfDay(zone).toInstant()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
            val status = events.firstOrNull {
                it.type == "status" &&
                    it.deletedAt == 0L &&
                    it.start.isBefore(dayEnd) &&
                    it.end.isAfter(dayStart)
            }
            val isToday = day == today
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(0.5.dp, BORDER)
                    .padding(horizontal = 3.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                    color = if (isToday) GOLD else DIM,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier
                        .size(27.dp)
                        .background(if (isToday) GOLD else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        day.dayOfMonth.toString(),
                        color = if (isToday) Color(0xFF0E0E0E) else Color(0xFFE8E0D0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                status?.let {
                    Text(
                        text = it.title,
                        color = EVENT_COLORS.getOrElse(it.colorId) { GOLD },
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
