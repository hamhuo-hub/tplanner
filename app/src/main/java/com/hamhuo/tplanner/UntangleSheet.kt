package com.hamhuo.tplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Helpers ────────────────────────────────────────────────────────────────

private fun prettyWhen(startIso: String, endIso: String): String {
    return try {
        val start = java.time.LocalDateTime.parse(startIso)
        val end = java.time.LocalDateTime.parse(endIso)
        val today = java.time.LocalDate.now()
        val startHm = "%02d:%02d".format(start.hour, start.minute)
        val endHm = "%02d:%02d".format(end.hour, end.minute)
        val zh = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[start.dayOfWeek.value - 1]
        val date = when (start.toLocalDate()) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> "${start.monthValue}月${start.dayOfMonth}日 $zh"
        }
        if (start.toLocalDate() == end.toLocalDate()) "$date $startHm–$endHm"
        else "$date $startHm – ${end.monthValue}月${end.dayOfMonth}日 $endHm"
    } catch (_: Exception) { "$startIso – $endIso" }
}

private fun prettyAlarm(enabled: Boolean, offsetMinutes: Int): String = when {
    !enabled -> "系统闹铃 · 关闭"
    offsetMinutes == 0 -> "系统闹铃 · 开始时"
    offsetMinutes % (24 * 60) == 0 -> "系统闹铃 · 提前 ${offsetMinutes / (24 * 60)} 天"
    offsetMinutes % 60 == 0 -> "系统闹铃 · 提前 ${offsetMinutes / 60} 小时"
    else -> "系统闹铃 · 提前 $offsetMinutes 分钟"
}

/**
 * Full-screen schedule-extraction panel. Three states:
 *
 *   EDIT    — write text describing what you want on your schedule
 *   THINKING — LLM is extracting schedule fields from your text
 *   CONFIRM  — review the extracted schedule and confirm or decline
 *
 * No QA, no clarifying questions — the tool always produces a schedule proposal.
 */
@Composable
fun UntangleSheet(
    prefillLocation: String,
    thinking: Boolean,
    action: DeepSeekAnalysisService.ProposedAction?,
    onDismiss: () -> Unit,
    onSubmit: (text: String) -> Unit,
    onConfirmAction: (DeepSeekAnalysisService.ProposedAction) -> Unit,
    onDeclineAction: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val showEditor = action == null && !thinking
    LaunchedEffect(showEditor) { if (showEditor) focusRequester.requestFocus() }

    Column(
        modifier = Modifier.fillMaxSize().background(BG).imePadding().padding(horizontal = 20.dp)
    ) {
        // ── Top bar ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    action != null -> "加个日程？"
                    thinking -> "识别中…"
                    else -> "写日程"
                },
                color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showEditor) {
                    Text("提取", color = if (text.isNotBlank()) GOLD else DIM, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { if (text.isNotBlank()) onSubmit(text) })
                }
                Icon(Icons.Default.Close, contentDescription = "Close", tint = DIM,
                    modifier = Modifier.size(18.dp).clickable { onDismiss() })
            }
        }

        // ── Location ─────────────────────────────────────────────────
        if (showEditor) {
            Text(
                prefillLocation.ifBlank { "Locating..." },
                color = if (prefillLocation.isNotBlank()) GOLD else DIM,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }

        when {
            // ── thinking ─────────────────────────────────────────────
            thinking -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(color = GOLD, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Text("识别日程信息…", color = DIM, fontSize = 14.sp)
                    }
                }
            }

            // ── schedule confirmation card ───────────────────────────
            action != null -> {
                val typeLabel = when (action.type) {
                    "event" -> "提醒"
                    "status" -> "状态"
                    else -> "任务"
                }
                Text("提取到以下日程，确认创建吗？", color = DIM, fontSize = 14.sp, modifier = Modifier.padding(bottom = 16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(SURFACE, RoundedCornerShape(12.dp))
                        .border(1.dp, GOLD.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(action.title, color = Color(0xFFE8E0D0), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                    Text("$typeLabel · ${prettyWhen(action.startIso, action.endIso)}", color = GOLD, fontSize = 14.sp)
                    Text("颜色 ${action.colorId + 1}", color = DIM, fontSize = 13.sp)
                    Text(
                        prettyAlarm(action.alarmEnabled, action.alarmOffsetMinutes),
                        color = if (action.alarmEnabled) GOLD else DIM,
                        fontSize = 13.sp,
                    )
                    if (action.note.isNotBlank()) Text(action.note, color = DIM, fontSize = 13.sp, lineHeight = 20.sp)
                    if (action.checklist.isNotEmpty()) {
                        Text("清单 · ${action.checklist.joinToString("、")}", color = DIM, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f).background(Color(0xFF222222), RoundedCornerShape(12.dp))
                            .clickable { onDeclineAction() }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("就当笔记", color = DIM, fontSize = 15.sp) }
                    Box(
                        modifier = Modifier.weight(1f).background(GOLD, RoundedCornerShape(12.dp))
                            .clickable { onConfirmAction(action) }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("加入日程", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
            }

            // ── editor ───────────────────────────────────────────────
            else -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .background(SURFACE, RoundedCornerShape(12.dp))
                        .border(1.dp, BORDER, RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(color = Color(0xFFE8E0D0), fontSize = 17.sp, lineHeight = 28.sp),
                        cursorBrush = SolidColor(GOLD),
                        modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
                        decorationBox = { inner -> inner() }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
