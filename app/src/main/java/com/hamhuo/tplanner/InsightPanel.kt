package com.hamhuo.tplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun InsightPanel(store: InsightStore, onRefresh: () -> Unit) {
    val scrollState = rememberScrollState()
    var dateOffset by remember { mutableIntStateOf(0) }
    val date = remember(dateOffset) {
        LocalDate.now().plusDays(dateOffset.toLong())
    }
    val dateStr = remember(date) { date.toString() }

    val report = remember(dateStr) { store.getDayReport(dateStr) }
    val distortionCounts = remember(dateStr) { store.getDistortionCounts(dateStr) }
    val avgIntensity = remember(dateStr) { store.getAvgIntensity(dateStr) }

    val dateDisplay = remember(date) {
        when (dateOffset) {
            0 -> "Today"
            -1 -> "Yesterday"
            else -> date.format(
                DateTimeFormatter.ofPattern("MMM d  E")
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
    ) {
        // ── Date nav ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("< Prev", color = DIM, fontSize = 14.sp,
                modifier = Modifier.clickable { dateOffset-- })
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Insights", color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(dateDisplay, color = DIM, fontSize = 13.sp)
            }
            Text("Next >", color = DIM, fontSize = 14.sp,
                modifier = Modifier.clickable { if (dateOffset < 0) dateOffset++ })
        }

        if (distortionCounts.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                Text("No anxiety records for this day", color = DIM, fontSize = 15.sp)
            }
            return@Column
        }

        // ── Stats overview ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatCard("Events", "${report?.totalEvents ?: distortionCounts.size}", GOLD)
            StatCard("Avg Intensity", "${avgIntensity}%", TEAL)
        }

        Spacer(Modifier.height(18.dp))

        // ── Distortion distribution ─────────────────────────────────────
        Text("Distortion Distribution", color = Color(0xFFE0D8C0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SURFACE, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val total = distortionCounts.values.sum()
            distortionCounts.entries
                .sortedByDescending { it.value }
                .forEach { (label, count) ->
                    DistortionBar(label, count, total)
                    if (label != distortionCounts.entries.sortedByDescending { it.value }.last().key) {
                        HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                    }
                }
        }

        // ── Top location / time slot ────────────────────────────────────
        val topLocation = report?.topLocation ?: store.getTopLocation(dateStr)
        val topTimeSlot = report?.topTimeSlot ?: store.getTopTimeSlot(dateStr)
        if (topLocation.isNotBlank() || topTimeSlot.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (topLocation.isNotBlank()) {
                    StatCard("Top Location", topLocation, GOLD, Modifier.weight(1f))
                }
                if (topTimeSlot.isNotBlank()) {
                    StatCard("Top Time", topTimeSlot, TEAL, Modifier.weight(1f))
                }
            }
        }

        // ── Day review ──────────────────────────────────────────────────
        val narrative = report?.narrative ?: ""
        if (narrative.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Text("Day Review", color = Color(0xFFE0D8C0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SURFACE, RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Text(
                    narrative,
                    color = Color(0xFFD0C8B8),
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                )
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SURFACE, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, color = DIM, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DistortionBar(label: String, count: Int, total: Int) {
    val fraction = if (total > 0) count.toFloat() / total else 0f
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFFD0C8B8), fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Box(
            modifier = Modifier.weight(1f).height(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF2A2A2A))
        ) {
            Box(
                modifier = Modifier.fillMaxSize().then(
                    Modifier.width((1.dp * (fraction * 200).toInt()))
                )
                    .clip(RoundedCornerShape(3.dp))
                    .background(GOLD)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("$count", color = DIM, fontSize = 12.sp, modifier = Modifier.width(36.dp))
    }
}
