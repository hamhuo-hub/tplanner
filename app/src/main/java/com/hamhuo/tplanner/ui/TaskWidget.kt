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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.designsystem.TPlannerTaskUnitModel
import com.hamhuo.tplanner.ui.components.TPlannerTaskUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Task Widget ───────────────────────────────────────────────────────────────

private fun taskStatus(e: ScheduleItem, now: Instant): String {
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
    events: List<ScheduleItem>,
    view: TaskView,
    onAddEvent: (String) -> Unit,
    onDelete: (String) -> Unit,
    onItemClick: (ScheduleItem) -> Unit,
    onTypeChange: (String, String) -> Unit = { _, _ -> },
    onViewPickerClick: () -> Unit = {},
    onModalVisibilityChange: (Boolean) -> Unit = {},
) {
    val now    = remember { Instant.now() }
    val today  = remember { appToday() }
    val zone   = remember { APP_ZONE }
    val fmt    = remember { DateTimeFormatter.ofPattern("HH:mm") }

    var showTypeSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var typeChangeTarget by remember { mutableStateOf<ScheduleItem?>(null) }
    val typeChangeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentOnModalVisibilityChange by rememberUpdatedState(onModalVisibilityChange)
    val hasVisibleModal = showTypeSheet || typeChangeTarget != null

    LaunchedEffect(hasVisibleModal) {
        currentOnModalVisibilityChange(hasVisibleModal)
    }
    DisposableEffect(Unit) {
        onDispose { currentOnModalVisibilityChange(false) }
    }

    val isToday = view is TaskView.Today
    val source = remember(events, view.key, today) { view.filter(events, today) }

    val groupNowLabel   = stringResource(R.string.group_now)
    val groupLaterLabel = stringResource(R.string.group_later)
    val groupPastLabel  = stringResource(R.string.group_past)
    val groupDoneLabel  = stringResource(R.string.group_done)

    val groups = remember(source, isToday, groupNowLabel, groupLaterLabel, groupPastLabel, groupDoneLabel) {
        val current  = mutableListOf<ScheduleItem>()
        val upcoming = mutableListOf<ScheduleItem>()
        val past     = mutableListOf<ScheduleItem>()
        val done     = mutableListOf<ScheduleItem>()
        // Every group is derived from the selected view. A custom list must never receive
        // overdue items from the global task dataset.
        source.forEach { e ->
            if (e.type == "task" && e.completed) { done += e; return@forEach }
            when (taskStatus(e, now)) {
                "now"  -> current += e
                "soon" -> upcoming += e
                "past" -> if (e.type == "task") past += e
                else   -> upcoming += e
            }
        }
        // Today: Now → Later → Past → Done. Other views: Past → Now → Later → Done.
        if (isToday) {
            mapOf(groupNowLabel to current, groupLaterLabel to upcoming, groupPastLabel to past, groupDoneLabel to done)
        } else {
            mapOf(groupPastLabel to past, groupNowLabel to current, groupLaterLabel to upcoming, groupDoneLabel to done)
        }
    }

    val nowExpanded   = rememberSaveable { mutableStateOf(true) }
    val pastExpanded  = rememberSaveable { mutableStateOf(true) }
    val laterExpanded = rememberSaveable { mutableStateOf(false) }
    val doneExpanded  = rememberSaveable { mutableStateOf(false) }

    val viewLabel = when (view) {
        is TaskView.Today -> stringResource(R.string.list_today)
        is TaskView.Inbox -> stringResource(R.string.list_inbox)
        is TaskView.CustomList -> view.name
    }

    val taskTotal = source.count { it.type == "task" }
    val taskDone  = source.count { it.type == "task" && it.completed }

    Column(Modifier.fillMaxSize()) {
        // 标题行
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier
                        .background(Color(0xFF252525), RoundedCornerShape(50.dp))
                        .border(1.dp, BORDER, RoundedCornerShape(50.dp))
                        .clickable(onClick = onViewPickerClick)
                        .padding(start = 10.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        viewLabel,
                        color = GOLD,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = GOLD,
                        modifier = Modifier.size(18.dp),
                    )
                }
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

        if (source.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.task_empty), color = Color(0xFF3A342A), fontSize = 16.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                groups.forEach { (label, list) ->
                    if (list.isEmpty()) return@forEach
                    val isNow  = label == groupNowLabel
                    val isPast  = label == groupPastLabel
                    val isLater = label == groupLaterLabel
                    val isDone  = label == groupDoneLabel
                    val expanded = when {
                        isNow   -> nowExpanded.value
                        isPast  -> pastExpanded.value
                        isLater -> laterExpanded.value
                        isDone  -> doneExpanded.value
                        else    -> true
                    }
                    item {
                        GroupHeader(
                            label = label,
                            count = list.size,
                            collapsible = true,
                            expanded = expanded,
                            onToggleExpanded = {
                                when {
                                    isNow   -> nowExpanded.value   = !nowExpanded.value
                                    isPast  -> pastExpanded.value  = !pastExpanded.value
                                    isLater -> laterExpanded.value = !laterExpanded.value
                                    isDone  -> doneExpanded.value  = !doneExpanded.value
                                }
                            }
                        )
                    }
                    if (expanded) {
                        items(list, key = { "${label}-${it.id}" }) { e ->
                            SwipeableTaskRow(
                                event = e, fmt = fmt, zone = zone, now = now,
                                onDelete = onDelete, onItemClick = onItemClick,
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
            CreateItemTypeSheet(
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
            ItemTypeChangeSheet(
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
    event: ScheduleItem,
    fmt: DateTimeFormatter,
    zone: ZoneId,
    now: Instant,
    onDelete: (String) -> Unit,
    onItemClick: (ScheduleItem) -> Unit,
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
        ) {
            TaskItem(
                event  = event,
                fmt    = fmt,
                zone   = zone,
                now    = now,
                onClick = { onItemClick(event) },
                onTypeChangeRequest = onTypeChangeRequest
            )
        }
    }
}

@Composable
fun TaskItem(
    event: ScheduleItem,
    fmt: DateTimeFormatter,
    zone: ZoneId,
    now: Instant,
    onClick: () -> Unit,
    onTypeChangeRequest: () -> Unit = {},
) {
    val status = taskStatus(event, now)
    val isDone = event.type == "task" && event.completed
    val startText = event.start.atZone(zone).format(fmt)
    val endText = event.end.atZone(zone).format(fmt)
    val statusLabel = when (status) {
        "now" -> stringResource(R.string.status_now)
        "soon" -> stringResource(R.string.status_soon)
        else -> ""
    }
    TPlannerTaskUnit(
        model = TPlannerTaskUnitModel(
            title = event.title.ifBlank { stringResource(R.string.untitled_event) },
            supportingText = "$startText \u2013 $endText",
            isTask = event.type == "task",
            showTaskCheckbox = false,
            completed = isDone,
            past = status == "past",
            current = status == "now",
            accentColor = EVENT_COLORS.getOrElse(event.colorId) { EVENT_COLORS[0] }.toArgb(),
            checklistDone = event.checklist.count { it.completed },
            checklistTotal = event.checklist.size,
            statusLabel = statusLabel,
            alarmEnabled = event.alarmEnabled && !isDone,
        ),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        onLeadingClick = if (event.type == "task") null else onTypeChangeRequest,
    )
}
