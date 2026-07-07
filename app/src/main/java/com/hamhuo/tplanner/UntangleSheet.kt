package com.hamhuo.tplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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

// AI 提议时间的友好展示："今天/明天 HH:mm"或"M月d日 周X HH:mm"；无时间则提示。
private fun prettyWhen(iso: String): String {
    if (iso.isBlank()) return "没写具体时间 · 先放在今天"
    return try {
        val dt = java.time.LocalDateTime.parse(iso)
        val today = java.time.LocalDate.now()
        val hm = "%02d:%02d".format(dt.hour, dt.minute)
        val zh = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[dt.dayOfWeek.value - 1]
        when (dt.toLocalDate()) {
            today -> "今天 $hm"
            today.plusDays(1) -> "明天 $hm"
            else -> "${dt.monthValue}月${dt.dayOfMonth}日 $zh $hm"
        }
    } catch (_: Exception) { iso }
}

// 全屏"理一理"面板。四态由父组件驱动：
//   编辑（都为空 && !thinking）→ 写下凌乱的想法/随手记
//   思考（thinking）           → AI 正在读你的片段
//   追问（questions!=null）    → AI 抛回的问题；不是答案，是帮你定位卡点
//   加日程（action!=null）     → AI 识别出待办/提醒，提议帮你建日程（你确认才建）
// 产品刻意不给"答案/分析/结论"，只把问题递还给你；有副作用的动作（建日程）
// 一律先提议、由你确认，绝不自作主张。
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UntangleSheet(
    prefillLocation: String,
    thinking: Boolean,
    questions: List<String>?,
    action: DeepSeekAnalysisService.ProposedAction?,
    clarify: DeepSeekAnalysisService.Clarify?,
    onDismiss: () -> Unit,
    onSubmit: (text: String) -> Unit,
    onConfirmAction: (DeepSeekAnalysisService.ProposedAction) -> Unit,
    onDeclineAction: () -> Unit,
    onAnswerClarify: (String) -> Unit,
    onAnswerQuestion: (String) -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val showEditor = questions == null && action == null && clarify == null && !thinking
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
                    clarify != null -> "还差一点"
                    action != null -> "加个日程？"
                    questions != null -> "Questions to sit with"
                    else -> "Untangle"
                },
                color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showEditor) {
                    Text("记下", color = if (text.isNotBlank()) GOLD else DIM, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { if (text.isNotBlank()) onSubmit(text) })
                }
                if (questions != null) {
                    Text("Done", color = GOLD, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onDismiss() })
                }
                Icon(Icons.Default.Close, contentDescription = "Close", tint = DIM,
                    modifier = Modifier.size(18.dp).clickable { onDismiss() })
            }
        }

        Text(prefillLocation.ifBlank { "Locating…" }, color = DIM, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp))

        when {
            // ── 澄清追问：缺关键参数，先问你，答完再补全操作 ──────────
            clarify != null -> {
                var custom by remember { mutableStateOf("") }
                Text(clarify.q, color = Color(0xFFE8E0D0), fontSize = 18.sp, lineHeight = 26.sp,
                    modifier = Modifier.padding(bottom = 16.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    clarify.options.forEach { opt ->
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF222222), RoundedCornerShape(20.dp))
                                .border(1.dp, GOLD.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                                .clickable { onAnswerClarify(opt) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) { Text(opt, color = Color(0xFFE0D8C0), fontSize = 15.sp) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                // 自定义回答
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(SURFACE, RoundedCornerShape(12.dp))
                        .border(1.dp, BORDER, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        BasicTextField(
                            value = custom, onValueChange = { custom = it }, singleLine = true,
                            textStyle = TextStyle(color = Color(0xFFE8E0D0), fontSize = 15.sp),
                            cursorBrush = SolidColor(GOLD), modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (custom.isEmpty()) Text("或自己写，比如「明早7点半」", color = DIM, fontSize = 15.sp)
                                inner()
                            },
                        )
                    }
                    if (custom.isNotBlank()) {
                        Text("确定", color = GOLD, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onAnswerClarify(custom) }.padding(start = 10.dp))
                    }
                }
            }

            // ── 加日程提议：AI 只提议，你确认了才创建 ────────────────
            action != null -> {
                val isReminder = action.type == "reminder"
                Text(
                    if (isReminder) "这条像是个提醒，要帮你放进日程吗？" else "这条像是件待办，要帮你建个任务吗？",
                    color = DIM, fontSize = 14.sp, modifier = Modifier.padding(bottom = 16.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(SURFACE, RoundedCornerShape(12.dp))
                        .border(1.dp, GOLD.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        (if (isReminder) "⏰  " else "☑  ") + action.title,
                        color = Color(0xFFE8E0D0), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp,
                    )
                    Text(prettyWhen(action.datetimeIso), color = GOLD, fontSize = 14.sp)
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

            // ── 追问：AI 把问题递还给你 ──────────────────────────────
            questions != null -> {
                var answer by remember { mutableStateOf("") }
                Text("这些不是答案，是帮你找到自己卡在哪。带着它们去试。",
                    color = DIM, fontSize = 13.sp, modifier = Modifier.padding(bottom = 14.dp))
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    questions.forEachIndexed { i, q ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(SURFACE, RoundedCornerShape(12.dp))
                                .border(1.dp, BORDER, RoundedCornerShape(12.dp))
                                .clickable { onAnswerQuestion(q) }   // 点问题即回答
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("${i + 1}", color = GOLD, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(q, color = Color(0xFFE8E0D0), fontSize = 16.sp, lineHeight = 26.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 回答输入 —— 用户答完可以点"继续"，AI 会基于整段对话往更深处再问
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(SURFACE, RoundedCornerShape(12.dp))
                            .border(1.dp, BORDER, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            BasicTextField(
                                value = answer, onValueChange = { answer = it }, singleLine = false,
                                textStyle = TextStyle(color = Color(0xFFE8E0D0), fontSize = 15.sp, lineHeight = 24.sp),
                                cursorBrush = SolidColor(GOLD), modifier = Modifier.fillMaxWidth(),
                                decorationBox = { inner ->
                                    if (answer.isEmpty()) Text("写下你的回答…", color = DIM, fontSize = 15.sp)
                                    inner()
                                },
                            )
                        }
                        if (answer.isNotBlank()) {
                            Text("继续", color = GOLD, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onAnswerQuestion(answer); answer = "" }.padding(start = 10.dp))
                        }
                    }
                }
            }

            // ── 思考中 ──────────────────────────────────────────────
            thinking -> {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(color = GOLD, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Text("在读你写的…", color = DIM, fontSize = 14.sp)
                    }
                }
            }

            // ── 编辑：写下凌乱的想法 ────────────────────────────────
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
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    "随手记点什么——默认只帮你顺一下错别字、存成笔记，不打扰你。\n\n想让它帮你理清思路、反过来问你几个问题，就在里面说一声（比如\"帮我理理\"\"问我几个问题\"）。",
                                    color = DIM, fontSize = 17.sp, lineHeight = 28.sp,
                                )
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
