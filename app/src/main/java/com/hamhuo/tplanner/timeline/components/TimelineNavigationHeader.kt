package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.BORDER
import com.hamhuo.tplanner.DIM
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.SURFACE
import com.hamhuo.tplanner.SURFACE2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TimelineNavigationHeader(
    days: List<LocalDate>,
    today: LocalDate,
    onPrevious: () -> Unit,
    onToday: () -> Unit,
    onNext: () -> Unit,
) {
    val locale = Locale.getDefault()
    val monthFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMM yyyy", locale) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Default.ChevronLeft,
                contentDescription = stringResource(R.string.timeline_previous_days),
                tint = DIM,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = days[1].format(monthFormatter).uppercase(locale),
                color = Color(0xFFE8E0D0),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = stringResource(R.string.timeline_drag_hint),
                color = DIM,
                fontSize = 9.sp,
            )
        }
        Box(
            modifier = Modifier
                .background(
                    if (today in days) GOLD else SURFACE2,
                    RoundedCornerShape(50.dp),
                )
                .border(1.dp, if (today in days) GOLD else BORDER, RoundedCornerShape(50.dp))
                .clickable(onClick = onToday)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                stringResource(R.string.timeline_today),
                color = if (today in days) Color(0xFF0E0E0E) else Color(0xFFE8E0D0),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.timeline_next_days),
                tint = DIM,
            )
        }
    }
}
