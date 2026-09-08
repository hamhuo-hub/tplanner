package com.hamhuo.tplanner

import com.hamhuo.tplanner.ui.components.TPlannerButton
import com.hamhuo.tplanner.ui.components.TPlannerButtonStyle
import com.hamhuo.tplanner.ui.components.TPlannerIconButton
import com.hamhuo.tplanner.ui.components.TPlannerInputFrame
import androidx.compose.ui.focus.onFocusChanged

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.PhoneGeometry as TPlannerGeometry
import com.hamhuo.tplanner.PhoneTypography as TPlannerTypography

// ── Helpers ────────────────────────────────────────────────────────────────

private fun prettyWhen(startIso: String, endIso: String): String {
    return try {
        val start = java.time.LocalDateTime.parse(startIso)
        val end = java.time.LocalDateTime.parse(endIso)
        val today = appToday()
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
 *   CONFIRM  — review the extracted schedule and confirm or dismiss
 *
 * No QA, no clarifying questions — the tool always produces a schedule proposal.
 */
@Composable
fun UntangleSheet(
    requestId: String,
    prefillLocation: String,
    locationLoading: Boolean,
    initialText: String,
    thinking: Boolean,
    action: DeepSeekAnalysisService.ProposedAction?,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (text: String) -> Unit,
    onConfirmAction: (DeepSeekAnalysisService.ProposedAction) -> Unit,
) {
    var text by remember(requestId) { mutableStateOf(initialText) }
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
                color = ACCENT_TEXT,
                fontSize = TPlannerTypography.PhoneTitleSp.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showEditor) {
                    TPlannerButton("提取", { onSubmit(text) }, enabled = text.isNotBlank())
                }
                TPlannerIconButton(Icons.Default.Close, "Close", onDismiss)
            }
        }

        // ── Location ─────────────────────────────────────────────────
        if (showEditor) {
            Text(
                when {
                    prefillLocation.isNotBlank() -> prefillLocation
                    locationLoading -> stringResource(R.string.location_locating)
                    else -> stringResource(R.string.location_unavailable)
                },
                color = if (prefillLocation.isNotBlank()) ACCENT_TEXT else DIM,
                fontSize = TPlannerTypography.PhoneMetaSp.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }

        when {
            // ── thinking ─────────────────────────────────────────────
            thinking -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(color = ACCENT_TEXT, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Text("识别日程信息…", color = DIM, fontSize = TPlannerTypography.PhoneSupportingSp.sp)
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
                Text(
                    "提取到以下日程，确认创建吗？",
                    color = DIM,
                    fontSize = TPlannerTypography.PhoneSupportingSp.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(SURFACE, RoundedCornerShape(TPlannerGeometry.RadiusCardDp.dp))
                        .border(1.dp, FOCUS, RoundedCornerShape(TPlannerGeometry.RadiusCardDp.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        action.title,
                        color = TEXT_EDITOR,
                        fontSize = TPlannerTypography.PhoneTitleSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = TPlannerTypography.PhoneBodyLineHeightSp.sp,
                    )
                    Text("$typeLabel · ${prettyWhen(action.startIso, action.endIso)}", color = ACCENT_TEXT, fontSize = TPlannerTypography.PhoneSupportingSp.sp)
                    Text("颜色 ${action.colorId + 1}", color = DIM, fontSize = TPlannerTypography.PhoneMetaSp.sp)
                    Text(
                        prettyAlarm(action.alarmEnabled, action.alarmOffsetMinutes),
                        color = if (action.alarmEnabled) ACCENT_TEXT else DIM,
                        fontSize = TPlannerTypography.PhoneMetaSp.sp,
                    )
                    if (action.note.isNotBlank()) Text(
                        action.note,
                        color = DIM,
                        fontSize = TPlannerTypography.PhoneMetaSp.sp,
                        lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
                    )
                    if (action.checklist.isNotEmpty()) {
                        Text(
                            "清单 · ${action.checklist.joinToString("、")}",
                            color = DIM,
                            fontSize = TPlannerTypography.PhoneMetaSp.sp,
                            lineHeight = TPlannerTypography.PhoneSupportingLineHeightSp.sp,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TPlannerButton(stringResource(R.string.action_cancel), onDismiss, Modifier.weight(1f), TPlannerButtonStyle.Secondary)
                    TPlannerButton("加入日程", { onConfirmAction(action) }, Modifier.weight(1f))
                }
            }

            // ── editor ───────────────────────────────────────────────
            else -> {
                var editorFocused by remember { mutableStateOf(false) }
                TPlannerInputFrame(modifier = Modifier.weight(1f).fillMaxWidth(), focused = editorFocused) {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onTextChange(it)
                        },
                        textStyle = TextStyle(
                            color = TEXT_EDITOR,
                            fontSize = TPlannerTypography.PhoneEditorSp.sp,
                            lineHeight = TPlannerTypography.PhoneEditorLineHeightSp.sp,
                        ),
                        cursorBrush = SolidColor(FOCUS),
                        modifier = Modifier.fillMaxSize().padding(16.dp).focusRequester(focusRequester)
                            .onFocusChanged { editorFocused = it.isFocused },
                        decorationBox = { inner -> inner() }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
