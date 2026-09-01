package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.RED
import com.hamhuo.tplanner.designsystem.TPlannerTypography

@Composable
internal fun ConflictBadge(
    count: Int,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val triangle = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(triangle, RED)
        }
        Text(
            text = count.toString(),
            color = Color.White,
            fontSize = TPlannerTypography.TimelineTimeSp.sp,
            lineHeight = TPlannerTypography.TimelineTimeSp.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(y = 2.dp),
        )
    }
}
