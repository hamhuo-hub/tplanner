package com.hamhuo.tplanner

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.UUID

@Composable
private fun typeLabel(type: String): String = when (type) {
    "task"     -> stringResource(R.string.type_task)
    "event"    -> stringResource(R.string.type_event)
    "reminder" -> stringResource(R.string.type_reminder)
    else       -> stringResource(R.string.type_generic)
}

private fun typeIcon(type: String): ImageVector = when (type) {
    "task"     -> Icons.Outlined.CheckCircle
    "event"    -> Icons.Outlined.CalendarMonth
    "reminder" -> Icons.Outlined.Alarm
    else       -> Icons.Outlined.CheckCircle
}

// ── 新建类型选择面板（点击任务面板 + 号后弹出） ──────────────────────────────────
@Composable
fun AddEventTypeSheet(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
    ) {
        // 拖拽把手
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .width(36.dp).height(4.dp)
                    .background(Color(0xFF444444), RoundedCornerShape(2.dp))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.label_new), color = Color(0xFFE0D8C8), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("✕", color = DIM, fontSize = 16.sp, modifier = Modifier.clickable { onDismiss() })
        }

        Spacer(Modifier.height(12.dp))

        AddTypeItem(
            icon   = Icons.Outlined.CheckCircle,
            title  = stringResource(R.string.type_task),
            desc   = stringResource(R.string.desc_task),
            onClick = { onSelect("task") }
        )
        AddTypeItem(
            icon   = Icons.Outlined.CalendarMonth,
            title  = stringResource(R.string.type_event),
            desc   = stringResource(R.string.desc_event),
            onClick = { onSelect("event") }
        )
        AddTypeItem(
            icon   = Icons.Outlined.Alarm,
            title  = stringResource(R.string.type_reminder),
            desc   = stringResource(R.string.desc_reminder),
            onClick = { onSelect("reminder") }
        )
    }
}

@Composable
private fun AddTypeItem(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(0xFF2E2E2E), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFFE0D8C8), modifier = Modifier.size(26.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFFE0D8C8), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = DIM, fontSize = 13.sp)
        }
    }
}

// ── 命名半屏面板（选完类型后，先命名再进详情页） ──────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameInputSheet(type: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val label = typeLabel(type)
    val defaultName = stringResource(R.string.default_name_template, label)
    var text by remember {
        mutableStateOf(TextFieldValue(defaultName, selection = TextRange(0, defaultName.length)))
    }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState       = sheetState,
        containerColor   = Color(0xFF1A1A1A),
        dragHandle       = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.name_prompt_template, label),
                color      = Color(0xFFE0D8C8),
                fontSize   = 19.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))
            BasicTextField(
                value         = text,
                onValueChange = { text = it },
                textStyle     = TextStyle(
                    color      = GOLD,
                    fontSize   = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                ),
                cursorBrush = SolidColor(GOLD),
                singleLine  = true,
                modifier    = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(220.dp).height(1.dp).background(BORDER))
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(label = stringResource(R.string.action_cancel), filled = false, onClick = onCancel)
                PillButton(label = stringResource(R.string.action_create), filled = true, onClick = {
                    val name = text.text.trim()
                    if (name.isNotEmpty()) onConfirm(name)
                })
            }
        }
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (filled) GOLD else Color.Transparent, RoundedCornerShape(50.dp))
            .border(1.dp, if (filled) GOLD else BORDER, RoundedCornerShape(50.dp))
            .clickable { onClick() }
            .padding(horizontal = 30.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color      = if (filled) Color(0xFF0E0E0E) else Color(0xFFE0D8C8),
            fontSize   = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── 任务详情页：时间 / 清单 / 备注 / 颜色 ──────────────────────────────────────
@Composable
fun EventDetailScreen(event: TaskEvent, onSave: (TaskEvent) -> Unit) {
    var title     by remember { mutableStateOf(event.title) }
    var renaming  by remember { mutableStateOf(false) }
    var start     by remember { mutableStateOf(event.start) }
    var end       by remember { mutableStateOf(event.end) }
    var checklist by remember { mutableStateOf(event.checklist) }
    var note      by remember { mutableStateOf(event.note) }
    var colorId   by remember { mutableStateOf(event.colorId) }

    val zone    = remember { ZoneId.systemDefault() }
    val dateTimePattern = stringResource(R.string.date_pattern_month_day_time)
    val dateFmt = remember(dateTimePattern) { DateTimeFormatter.ofPattern(dateTimePattern) }
    val context = LocalContext.current

    fun pickDateTime(initial: Instant, onPicked: (Instant) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initial.toEpochMilli() }
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                cal.set(y, m, d, h, min)
                onPicked(Instant.ofEpochMilli(cal.timeInMillis))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun buildResult() = event.copy(
        title     = title.ifBlank { event.title },
        start     = start,
        end       = end,
        checklist = checklist,
        note      = note,
        colorId   = colorId,
        updatedAt = System.currentTimeMillis(),
    )

    Dialog(
        onDismissRequest = { onSave(buildResult()) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(BG)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部栏：返回 + 完成
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onSave(buildResult()) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color(0xFFE0D8C8))
                    }
                    Box(
                        modifier = Modifier
                            .background(GOLD, RoundedCornerShape(50.dp))
                            .clickable { onSave(buildResult()) }
                            .padding(horizontal = 22.dp, vertical = 8.dp)
                    ) {
                        Text(stringResource(R.string.action_done), color = Color(0xFF0E0E0E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    // 标题行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    EVENT_COLORS.getOrElse(colorId) { EVENT_COLORS[0] },
                                    RoundedCornerShape(14.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                typeIcon(event.type), contentDescription = null,
                                tint = Color(0xFF0E0E0E), modifier = Modifier.size(26.dp)
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            if (renaming) {
                                BasicTextField(
                                    value = title,
                                    onValueChange = { title = it },
                                    textStyle = TextStyle(
                                        color = Color(0xFFE0D8C8), fontSize = 22.sp, fontWeight = FontWeight.Bold
                                    ),
                                    cursorBrush = SolidColor(GOLD),
                                    singleLine  = true,
                                    modifier    = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    title.ifBlank { stringResource(R.string.untitled_placeholder) },
                                    color = Color(0xFFE0D8C8), fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF252525), RoundedCornerShape(50.dp))
                                .border(1.dp, BORDER, RoundedCornerShape(50.dp))
                                .clickable { renaming = !renaming }
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        ) {
                            Text(
                                if (renaming) stringResource(R.string.action_done) else stringResource(R.string.action_change),
                                color = Color(0xFFE0D8C8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 时间
                    DetailSectionLabel(stringResource(R.string.section_time))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeChip(
                            label = stringResource(R.string.label_start), value = start.atZone(zone).format(dateFmt),
                            onClick = { pickDateTime(start) { start = it } }
                        )
                        TimeChip(
                            label = stringResource(R.string.label_end), value = end.atZone(zone).format(dateFmt),
                            onClick = { pickDateTime(end) { end = it } }
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 清单
                    DetailSectionLabel(stringResource(R.string.section_checklist))
                    Spacer(Modifier.height(10.dp))
                    checklist.forEachIndexed { idx, item ->
                        ChecklistRow(
                            item = item,
                            onToggle = {
                                checklist = checklist.toMutableList()
                                    .also { it[idx] = item.copy(completed = !item.completed) }
                            },
                            onTextChange = { newText ->
                                checklist = checklist.toMutableList()
                                    .also { it[idx] = item.copy(text = newText) }
                            },
                            onDelete = {
                                checklist = checklist.toMutableList().also { it.removeAt(idx) }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clickable {
                                checklist = checklist + CheckItem(
                                    id = UUID.randomUUID().toString(), text = "", completed = false
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = DIM, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.action_add_item), color = DIM, fontSize = 14.sp)
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 备注
                    DetailSectionLabel(stringResource(R.string.section_note))
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                        if (note.isEmpty()) {
                            Text(stringResource(R.string.note_placeholder), color = DIM, fontSize = 15.sp)
                        }
                        BasicTextField(
                            value = note,
                            onValueChange = { note = it },
                            textStyle  = TextStyle(color = Color(0xFFE0D8C8), fontSize = 15.sp),
                            cursorBrush = SolidColor(GOLD),
                            modifier   = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 颜色
                    DetailSectionLabel(stringResource(R.string.section_color))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EVENT_COLORS.forEachIndexed { idx, c ->
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(c, CircleShape)
                                    .border(if (idx == colorId) 2.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { colorId = idx }
                            )
                        }
                    }

                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailSectionLabel(text: String) {
    Text(text, color = Color(0xFF6B5928), fontSize = 13.sp, letterSpacing = 0.12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TimeChip(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .background(Color(0xFF1F1F1F), RoundedCornerShape(10.dp))
            .border(1.dp, BORDER, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, color = DIM, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Color(0xFFE0D8C8), fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChecklistRow(item: CheckItem, onToggle: () -> Unit, onTextChange: (String) -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(if (item.completed) GOLD else Color.Transparent, RoundedCornerShape(4.dp))
                .border(1.5.dp, if (item.completed) GOLD else BORDER, RoundedCornerShape(4.dp))
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            if (item.completed) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF0E0E0E), modifier = Modifier.size(14.dp))
            }
        }
        BasicTextField(
            value = item.text,
            onValueChange = onTextChange,
            textStyle = TextStyle(
                color = if (item.completed) DIM else Color(0xFFE0D8C8),
                fontSize = 15.sp,
                textDecoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None
            ),
            cursorBrush = SolidColor(GOLD),
            singleLine  = true,
            modifier    = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete),
            tint = DIM, modifier = Modifier.size(18.dp).clickable { onDelete() }
        )
    }
}
