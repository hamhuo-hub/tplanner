package com.hamhuo.tplanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.DIM
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.RED
import com.hamhuo.tplanner.SURFACE2
import com.hamhuo.tplanner.TEAL
import com.hamhuo.tplanner.TEXT_PRIMARY
import com.hamhuo.tplanner.designsystem.TPlannerGeometry
import com.hamhuo.tplanner.designsystem.TPlannerTypography
import com.hamhuo.tplanner.syncv3.SyncLogEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 同步日志查看面板:只读 + 清空;纯诊断,不参与任何正确性路径。 */
@Composable
fun SyncLogPanel(
    entries: List<SyncLogEntity>,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .width(330.dp)
            .heightIn(max = 430.dp)
            .shadow(16.dp, RoundedCornerShape(TPlannerGeometry.RadiusPanelDp.dp)),
        shape = RoundedCornerShape(TPlannerGeometry.RadiusPanelDp.dp),
        colors = CardDefaults.cardColors(containerColor = SURFACE2),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.sync_logs_title),
                    color = DIM,
                    fontSize = TPlannerTypography.PhoneMicroSp.sp,
                    letterSpacing = 0.1.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.sync_logs_clear),
                        color = GOLD,
                        fontSize = TPlannerTypography.PhoneMicroSp.sp,
                        modifier = Modifier.clickable(onClick = onClear),
                    )
                    Text(
                        "✕",
                        color = DIM,
                        fontSize = TPlannerTypography.PhoneMicroSp.sp,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clickable(onClick = onClose),
                    )
                }
            }
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.sync_logs_empty),
                    color = DIM,
                    fontSize = TPlannerTypography.PhoneBadgeSp.sp,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { it.id }) { entry -> SyncLogRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun SyncLogRow(entry: SyncLogEntity) {
    val levelColor = when (entry.level) {
        "warn" -> GOLD
        "error" -> RED
        else -> TEAL
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatLogTime(entry.createdAt),
                color = DIM,
                fontSize = TPlannerTypography.PhoneMicroSp.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                entry.source.uppercase(),
                color = levelColor,
                fontSize = TPlannerTypography.PhoneMicroSp.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                entry.message,
                color = TEXT_PRIMARY,
                fontSize = TPlannerTypography.PhoneMicroSp.sp,
            )
        }
        val secondary = listOfNotNull(
            entry.detail,
            entry.errorCode?.let { "code=$it" },
        ).joinToString(" · ")
        if (secondary.isNotBlank()) {
            Text(
                secondary,
                color = DIM,
                fontSize = TPlannerTypography.PhoneMicroSp.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private val LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun formatLogTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime().format(LOG_TIME_FORMAT)
