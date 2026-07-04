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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── 情绪快速标签 ──────────────────────────────────────────────────────────
private val EMOTION_TAGS = listOf(
    "焦虑", "恐惧", "羞耻", "愤怒", "悲伤", "无助",
    "内疚", "孤独", "绝望", "烦躁", "麻木", "紧张",
)

// ── 身体症状快速标签 ─────────────────────────────────────────────────────
private val SYMPTOM_TAGS = listOf(
    "心慌", "胸闷", "手抖", "出汗", "头晕",
    "呼吸困难", "胃紧", "发冷", "发热", "发抖",
)

// ── 思维钢印全量列表（用于芯片行展示）─────────────────────────────────────
private val DISTORTION_LABELS = DistortionType.entries.map { it.label }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnxietyInputSheet(
    prefillLocation: String,
    onDismiss: () -> Unit,
    onSubmit: (text: String, intensity: Int, selectedEmotions: List<String>, selectedSymptoms: List<String>) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var intensity by remember { mutableIntStateOf(0) }
    var selectedEmotions by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSymptoms by remember { mutableStateOf<List<String>>(emptyList()) }

    // 实时本地关键词检测
    val distortionHits = remember(text) { CognitiveDistortionDetector.detect(text) }
    val hitLabels = distortionHits.flatMap { it.matchedKeywords }.toSet()

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .padding(horizontal = 20.dp)
    ) {
        // ── 顶栏 ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📋", fontSize = 20.sp)
                Text("焦虑记录", color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("提交", color = GOLD, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        if (text.isNotBlank()) onSubmit(text, intensity, selectedEmotions, selectedSymptoms)
                    })
                Text("✕", color = DIM, fontSize = 16.sp, modifier = Modifier.clickable { onDismiss() })
            }
        }

        // 地点 + 时间（预填）
        Text(
            text = prefillLocation.ifBlank { "📍 位置获取中..." },
            color = DIM, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // ── 输入区 ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(SURFACE, RoundedCornerShape(12.dp))
                .border(1.dp, BORDER, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(
                    color = Color(0xFFE8E0D0),
                    fontSize = 17.sp,
                    lineHeight = 28.sp,
                ),
                cursorBrush = SolidColor(GOLD),
                modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            "此刻发生了什么？你在想什么？",
                            color = DIM, fontSize = 17.sp, lineHeight = 28.sp,
                        )
                    }
                    inner()
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── 思维钢印芯片行（实时高亮）──────────────────────────────────────
        if (text.isNotBlank()) {
            Text("思维钢印", color = DIM, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DISTORTION_LABELS.forEach { label ->
                    val isHit = DistortionType.fromLabel(label)?.keywords?.any { text.contains(it) } == true
                    Chip(
                        label = label,
                        active = isHit,
                        onClick = { /* 仅供参考，不可点击 */ }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── 情绪标签 ──────────────────────────────────────────────────────
        Text("情绪", color = DIM, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EMOTION_TAGS.forEach { tag ->
                val selected = tag in selectedEmotions
                EmotionChip(
                    label = tag,
                    selected = selected,
                    onClick = {
                        selectedEmotions = if (selected) selectedEmotions - tag else selectedEmotions + tag
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── 身体症状标签 ──────────────────────────────────────────────────
        Text("身体症状", color = DIM, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SYMPTOM_TAGS.forEach { tag ->
                val selected = tag in selectedSymptoms
                EmotionChip(
                    label = tag,
                    selected = selected,
                    onClick = {
                        selectedSymptoms = if (selected) selectedSymptoms - tag else selectedSymptoms + tag
                    }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 焦虑强度滑块 ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("焦虑强度", color = DIM, fontSize = 13.sp)
            // 简易数字选择器（0/25/50/75/100）
            listOf(0, 25, 50, 75, 100).forEach { level ->
                val sel = intensity == level
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (sel) GOLD else Color(0xFF2A2A2A))
                        .clickable { intensity = level },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$level",
                        color = if (sel) Color.Black else DIM,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

// ── 思维钢印芯片（不可点击，仅高亮） ──────────────────────────────────────
@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) GOLD.copy(alpha = 0.2f) else Color(0xFF222222))
            .border(1.dp, if (active) GOLD else Color(0xFF333333), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (active) GOLD else DIM,
            fontSize = 12.sp,
        )
    }
}

// ── 情绪/症状可选芯片 ─────────────────────────────────────────────────────
@Composable
private fun EmotionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) GOLD else Color(0xFF222222))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else DIM,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
