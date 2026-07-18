package com.hamhuo.tplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Task Widget ───────────────────────────────────────────────────────────────

private fun taskStatus(e: TaskEvent, now: Instant): String {
    return when {
        e.end.isBefore(now)                                -> "past"
        !e.start.isAfter(now) && !e.end.isBefore(now)     -> "now"
        e.start.epochSecond - now.epochSecond <= 5 * 60   -> "soon"
        else                                               -> "future"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskWidget(
    events: List<TaskEvent>,
    onToggle: (String, Boolean) -> Unit,
    onAddEvent: (String) -> Unit,
    onDelete: (String) -> Unit,
    onItemClick: (TaskEvent) -> Unit,
    onTypeChange: (String, String) -> Unit = { _, _ -> },
) {
    val now    = remember { Instant.now() }
    val today  = remember { LocalDate.now() }
    val zone   = remember { ZoneId.systemDefault() }
    val fmt    = remember { DateTimeFormatter.ofPattern("HH:mm") }

    var showTypeSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var typeChangeTarget by remember { mutableStateOf<TaskEvent?>(null) }
    val typeChangeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val todayEvents    = remember(events) { events.forToday() }

    val groupNowLabel   = stringResource(R.string.group_now)
    val groupLaterLabel = stringResource(R.string.group_later)
    val groupPastLabel  = stringResource(R.string.group_past)
    val groupDoneLabel  = stringResource(R.string.group_done)

    val groups = remember(events, groupNowLabel, groupLaterLabel, groupPastLabel, groupDoneLabel) {
        val current  = mutableListOf<TaskEvent>()
        val upcoming = mutableListOf<TaskEvent>()
        val past     = mutableListOf<TaskEvent>()
        val done     = mutableListOf<TaskEvent>()
        // Past & Later: 取全部未删除事件，不限于今天。
        // 未完成就是未完成——不管哪天。
        events.filter { it.deletedAt == 0L }.forEach { e ->
            if (e.type == "task" && e.completed) { done += e; return@forEach }
            when (taskStatus(e, now)) {
                "now"  -> current += e
                "soon" -> upcoming += e
                "past" -> past += e
                else   -> upcoming += e
            }
        }
        mapOf(groupNowLabel to current, groupLaterLabel to upcoming, groupPastLabel to past, groupDoneLabel to done)
    }

    val pastExpanded = remember { mutableStateOf(true) }
    val laterExpanded = remember { mutableStateOf(false) }

    val taskTotal = events.count { it.deletedAt == 0L && it.type == "task" }
    val taskDone  = events.count { it.deletedAt == 0L && it.type == "task" && it.completed }

    Column(Modifier.fillMaxSize()) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.tab_tasks), color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    today.format(DateTimeFormatter.ofPattern(stringResource(R.string.date_pattern_month_day_weekday))),
                    color = DIM, fontSize = 15.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (taskTotal > 0) {
                    Text("$taskDone/$taskTotal", color = DIM, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                }
                // 右侧 + 按钮
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0xFF252525), CircleShape)
                        .border(1.dp, BORDER, CircleShape)
                        .clickable { showTypeSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.label_new), tint = GOLD, modifier = Modifier.size(20.dp))
                }
            }
        }

        HorizontalDivider(color = BORDER, thickness = 1.dp)

        if (todayEvents.isEmpty() && groups.values.all { it.isEmpty() }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.task_empty), color = Color(0xFF3A342A), fontSize = 16.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                if (todayEvents.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)) {
                            Text(stringResource(R.string.task_empty), color = Color(0xFF3A342A), fontSize = 14.sp)
                        }
                    }
                }
                groups.forEach { (label, list) ->
                    if (list.isEmpty()) return@forEach
                    val isPast  = label == groupPastLabel
                    val isLater = label == groupLaterLabel
                    val collapsible = isPast || isLater
                    val expanded = when {
                        isPast  -> pastExpanded.value
                        isLater -> laterExpanded.value
                        else    -> true
                    }
                    item {
                        GroupHeader(
                            label = label,
                            count = list.size,
                            collapsible = collapsible,
                            expanded = expanded,
                            onToggleExpanded = {
                                when {
                                    isPast  -> pastExpanded.value  = !pastExpanded.value
                                    isLater -> laterExpanded.value = !laterExpanded.value
                                }
                            }
                        )
                    }
                    if (expanded) {
                        items(list, key = { e -> if (collapsible) "${label}-${e.id}" else e.id }) { e ->
                            SwipeableTaskRow(
                                event = e, fmt = fmt, zone = zone, now = now,
                                onToggle = onToggle, onDelete = onDelete, onItemClick = onItemClick,
                                onTypeChangeRequest = { typeChangeTarget = e }
                            )
                        }
                    }
                }
            }
        }
    }

    // 底部弹出面板 — 选择新建类型
    if (showTypeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTypeSheet = false },
            sheetState       = sheetState,
            containerColor   = Color(0xFF1A1A1A),
            dragHandle       = null,
        ) {
            AddEventTypeSheet(
                onSelect = { type ->
                    showTypeSheet = false
                    onAddEvent(type)
                },
                onDismiss = { showTypeSheet = false }
            )
        }
    }

    // 底部弹出面板 — 修改已有事件的类型
    if (typeChangeTarget != null) {
        ModalBottomSheet(
            onDismissRequest = { typeChangeTarget = null },
            sheetState       = typeChangeSheetState,
            containerColor   = Color(0xFF1A1A1A),
            dragHandle       = null,
        ) {
            TypeChangeSheet(
                currentType = typeChangeTarget!!.type,
                onSelect = { newType ->
                    val ev = typeChangeTarget!!
                    if (newType != ev.type) {
                        onTypeChange(ev.id, newType)
                    }
                    typeChangeTarget = null
                },
                onDismiss = { typeChangeTarget = null }
            )
        }
    }
}

@Composable
private fun GroupHeader(
    label: String,
    count: Int,
    collapsible: Boolean = false,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (collapsible) Modifier.clickable { onToggleExpanded() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (collapsible) {
            Text(if (expanded) "▾" else "▸", color = Color(0xFF6B5928), fontSize = 13.sp)
        }
        Text(label, color = Color(0xFF6B5928), fontSize = 13.sp, letterSpacing = 0.12.sp)
        Box(
            Modifier
                .background(Color(0x1FC9A84C), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text("$count", color = Color(0xFF6B5928), fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskRow(
    event: TaskEvent,
    fmt: DateTimeFormatter,
    zone: ZoneId,
    now: Instant,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onItemClick: (TaskEvent) -> Unit,
    onTypeChangeRequest: () -> Unit = {},
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete(event.id)
            true
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(RED),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete),
                    tint = Color.White,
                    modifier = Modifier.padding(end = 20.dp)
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SURFACE2)
                .clickable { onItemClick(event) }
        ) {
            TaskItem(
                event  = event,
                fmt    = fmt,
                zone   = zone,
                now    = now,
                onToggle = onToggle,
                onTypeChangeRequest = onTypeChangeRequest
            )
        }
    }
}

@Composable
fun TaskItem(
    event: TaskEvent,
    fmt: DateTimeFormatter,
    zone: ZoneId,
    now: Instant,
    onToggle: (String, Boolean) -> Unit,
    onTypeChangeRequest: () -> Unit = {},
) {
    val status     = taskStatus(event, now)
    val isDone     = event.type == "task" && event.completed
    val isPast     = status == "past"
    val isNow      = status == "now"
    val color      = EVENT_COLORS.getOrElse(event.colorId) { EVENT_COLORS[0] }
    val doneCount  = event.checklist.count { it.completed }
    val totalCheck = event.checklist.size

    val rowAlpha   = if (isDone || isPast) 0.45f else 1f
    val bgColor    = if (isNow) Color(0x0F5B8FCC) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .then(if (isNow) Modifier.border(
                width = 1.dp,
                color = Color(0x305B8FCC),
                shape = RoundedCornerShape(0.dp)
            ) else Modifier)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 勾选框 / 颜色条
        if (event.type == "task") {
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .background(
                        if (isDone) GOLD else Color.Transparent,
                        RoundedCornerShape(2.dp)
                    )
                    .border(1.5.dp, if (isDone) GOLD else BORDER, RoundedCornerShape(2.dp))
                    .clickable { onToggle(event.id, !event.completed) },
                contentAlignment = Alignment.Center
            ) {
                if (isDone) Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.Black, modifier = Modifier.size(12.dp))
            }
        } else {
            Box(
                Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .background(color, RoundedCornerShape(1.dp))
                    .clickable { onTypeChangeRequest() }
            )
        }

        Column(Modifier.weight(1f).then(if (rowAlpha < 1f) Modifier.then(Modifier) else Modifier)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text       = event.title.ifBlank { stringResource(R.string.untitled_event) },
                    color      = if (isDone) DIM else Color(0xFFE0D8C8),
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 1,
                    modifier   = Modifier.weight(1f, fill = false),
                    style      = if (isDone) TextStyle(
                        color          = DIM,
                        fontSize       = 15.sp,
                        textDecoration = TextDecoration.LineThrough
                    ) else TextStyle(color = Color(0xFFE0D8C8), fontSize = 15.sp)
                )
                if (totalCheck > 0) {
                    val allDone = doneCount == totalCheck
                    Box(
                        Modifier
                            .background(
                                if (allDone) Color(0x334A7C59) else Color(0x1FC9A84C),
                                RoundedCornerShape(2.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "$doneCount/$totalCheck",
                            color    = if (allDone) Color(0xFF4A7C59) else Color(0xFF6B5928),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                if (event.alarmEnabled && !isDone) {
                    Icon(
                        Icons.Outlined.Alarm,
                        contentDescription = stringResource(R.string.section_alarm),
                        tint = GOLD,
                        modifier = Modifier.size(13.dp),
                    )
                }
                if (isNow) Text(stringResource(R.string.status_now), color = Color(0xFF8BB8E8), fontSize = 11.sp)
                else if (status == "soon") Text(stringResource(R.string.status_soon), color = GOLD, fontSize = 11.sp)
            }
            val startFmt = event.start.atZone(zone).format(fmt)
            val endFmt   = event.end.atZone(zone).format(fmt)
            Text(
                "$startFmt – $endFmt",
                color = DIM, fontSize = 14.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}
