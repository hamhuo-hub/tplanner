package com.hamhuo.tplanner.timeline.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens

@Composable
internal fun TimelineAddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(18.dp)
            .size(54.dp)
            .background(Color(TPlannerLightTokens.Component.Button.Primary.Background), CircleShape)
            .border(1.dp, Color(TPlannerLightTokens.Component.Button.Primary.Border), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.cd_add_event),
            tint = Color(TPlannerLightTokens.Component.Button.Primary.Foreground),
            modifier = Modifier.size(26.dp),
        )
    }
}
