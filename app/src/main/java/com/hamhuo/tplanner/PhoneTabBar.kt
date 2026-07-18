package com.hamhuo.tplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PhoneTabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .background(
                        color = if (isSelected) GOLD else Color(0xFF252525),
                        shape = RoundedCornerShape(50.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) GOLD else BORDER,
                        shape = RoundedCornerShape(50.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 22.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = label,
                    color      = if (isSelected) Color(0xFF0E0E0E) else DIM,
                    fontSize   = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}
