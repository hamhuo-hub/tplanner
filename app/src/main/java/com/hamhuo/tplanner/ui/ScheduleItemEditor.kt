@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.hamhuo.tplanner

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleItemDetailScreen(
    event: ScheduleItem,
    userLists: List<UserList>,
    onDraftChange: (ScheduleItem) -> Unit,
    onSave: (ScheduleItem, (Boolean) -> Unit) -> Unit,
    onDelete: ((Boolean) -> Unit) -> Unit,
    onNoteSave: (ScheduleItem, (Boolean) -> Unit) -> Unit,
    onCreateList: (String, (UserList?) -> Unit) -> Unit,
) {
    val initialNote = event.note
    var title     by remember { mutableStateOf(event.title) }
    var renaming  by remember { mutableStateOf(false) }
    var start     by remember { mutableStateOf(event.start) }
    var end       by remember { mutableStateOf(event.end) }
    var checklist by remember { mutableStateOf(event.checklist) }
    var completed by remember { mutableStateOf(event.completed) }
    var note      by remember(event.id) { mutableStateOf(initialNote) }
    var noteEditorDraft by remember(event.id) { mutableStateOf(initialNote) }
    var noteEditorOpen by remember { mutableStateOf(false) }
    var noteEditorCloseRequested by remember { mutableStateOf(false) }
    var colorId   by remember { mutableStateOf(event.colorId) }
    var type      by remember { mutableStateOf(event.type) }
    var listId    by remember(event.id) { mutableStateOf(event.listId) }
    var alarmEnabled by remember { mutableStateOf(event.alarmEnabled) }
    var alarmOffsetMinutes by remember { mutableStateOf(event.alarmOffsetMinutes) }
    var recurrenceType by remember(event.id) {
        mutableStateOf(
            event.extras["recurrenceType"]
                ?.toString()
                ?.lowercase()
                ?.takeIf { it in setOf("daily", "weekly", "monthly") }
                ?: "none",
        )
    }
    var recurrenceCount by remember(event.id) {
        val raw = event.extras["recurrenceCount"]
        val count = if (raw is Number) raw.toInt() else raw?.toString()?.toIntOrNull() ?: 1
        mutableStateOf(count.coerceIn(1, MAX_TASK_RECURRENCE_COUNT))
    }

    var showTypeSheet by remember { mutableStateOf(false) }
    val typeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showListPicker by remember { mutableStateOf(false) }
    val listPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showNewListNameSheet by remember { mutableStateOf(false) }

    val zone    = remember { APP_ZONE }
    val dateTimePattern = stringResource(R.string.date_pattern_month_day_time)
    val dateFmt = remember(dateTimePattern) { DateTimeFormatter.ofPattern(dateTimePattern) }
    val context = LocalContext.current
    var saveRequested by remember { mutableStateOf(false) }
    var deleteRequested by remember { mutableStateOf(false) }

    fun pickDateTime(initial: Instant, onPicked: (Instant) -> Unit) {
        val cal = Calendar.getInstance(appLegacyTimeZone()).apply {
            timeInMillis = initial.toEpochMilli()
        }
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                cal.set(y, m, d, h, min)
                onPicked(Instant.ofEpochMilli(cal.timeInMillis))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun buildResult(updatedAt: Long = System.currentTimeMillis()): ScheduleItem {
        val nextExtras = event.extras.toMutableMap().apply {
            if (recurrenceType == "none") {
                remove("recurrenceType")
                remove("recurrenceCount")
            } else {
                put("recurrenceType", recurrenceType)
                put("recurrenceCount", recurrenceCount.coerceIn(1, MAX_TASK_RECURRENCE_COUNT))
            }
            remove("groupId")
        }
        return event.copy(
            title     = title.ifBlank { event.title },
            type      = type,
            start     = start,
            end       = end,
            // Non-task editors hide checklist controls, but the data must survive a
            // temporary type conversion so converting back to a task is lossless.
            checklist = checklist,
            completed = if (type == "task") completed else false,
            note      = if (noteEditorOpen) noteEditorDraft else note,
            colorId   = colorId,
            listId    = listId,
            alarmEnabled = alarmEnabled,
            alarmOffsetMinutes = alarmOffsetMinutes.coerceIn(0, MAX_ALARM_OFFSET_MINUTES),
            updatedAt = updatedAt,
            extras = nextExtras,
        )
    }

    fun persistDraft() {
        if (!saveRequested && !noteEditorCloseRequested) {
            // Enqueue from the input callback itself. A LaunchedEffect can be cancelled before it
            // observes the last accepted keystroke when the Activity is stopped or recreated.
            onDraftChange(buildResult(updatedAt = event.updatedAt))
        }
    }

    fun commitResult() {
        if (saveRequested || deleteRequested) return
        saveRequested = true
        onSave(buildResult()) { completed ->
            if (!completed) saveRequested = false
        }
    }

    fun deleteAndClose() {
        if (saveRequested || deleteRequested) return
        deleteRequested = true
        onDelete { completed ->
            if (!completed) deleteRequested = false
        }
    }

    fun closeNoteEditor(updatedNote: String = noteEditorDraft) {
        if (!noteEditorOpen || noteEditorCloseRequested) return
        note = updatedNote
        noteEditorDraft = updatedNote
        noteEditorCloseRequested = true
        onNoteSave(buildResult().copy(note = updatedNote)) { completed ->
            if (!completed) {
                // The durable draft remains intact. Remount the editor so its local finish latch
                // cannot leave the text permanently read-only after a conflict or I/O failure.
                noteEditorCloseRequested = false
                noteEditorOpen = false
            } else {
                // The Room commit is the completion authority. Preview rendering may fail or be
                // delayed and must not leave the editor permanently read-only.
                noteEditorCloseRequested = false
                noteEditorOpen = false
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (noteEditorOpen) closeNoteEditor() else commitResult()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val imeVisible = WindowInsets.isImeVisible

        fun saveAndClose() {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            commitResult()
        }

        BackHandler(enabled = !showTypeSheet && !showListPicker && !showNewListNameSheet && !noteEditorOpen) {
            if (imeVisible) {
                keyboardController?.hide()
            } else {
                saveAndClose()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(BG)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {
                // 顶部栏：返回 + 完成
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::saveAndClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color(0xFFE0D8C8))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .background(RED, RoundedCornerShape(50.dp))
                                .clickable(enabled = !saveRequested && !deleteRequested, onClick = ::deleteAndClose)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                stringResource(R.string.cd_delete),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(GOLD, RoundedCornerShape(50.dp))
                                .clickable(enabled = !saveRequested && !deleteRequested, onClick = ::saveAndClose)
                                .padding(horizontal = 22.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.action_done), color = Color(0xFF0E0E0E), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
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
                                )
                                .clickable {
                                    renaming = false
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    showTypeSheet = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                typeIcon(type), contentDescription = null,
                                tint = Color(0xFF0E0E0E), modifier = Modifier.size(26.dp)
                            )
                        }
                        if (renaming) {
                            val titleFocusRequester = remember { FocusRequester() }
                            BasicTextField(
                                value = title,
                                onValueChange = {
                                    title = it
                                    persistDraft()
                                },
                                textStyle = TextStyle(
                                    color = Color(0xFFE0D8C8), fontSize = 22.sp, fontWeight = FontWeight.Bold
                                ),
                                cursorBrush = SolidColor(GOLD),
                                singleLine  = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { saveAndClose() }),
                                modifier    = Modifier
                                    .weight(1f)
                                    .clipToBounds()
                                    .focusRequester(titleFocusRequester)
                            )
                            LaunchedEffect(Unit) { titleFocusRequester.requestFocus() }
                        } else {
                            Text(
                                title.ifBlank { stringResource(R.string.untitled_placeholder) },
                                color = Color(0xFFE0D8C8), fontSize = 22.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { renaming = true }
                            )
                        }
                        if (type == "task") {
                            IconButton(onClick = {
                                completed = !completed
                                persistDraft()
                            }) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            if (completed) GOLD else Color.Transparent,
                                            RoundedCornerShape(5.dp),
                                        )
                                        .border(
                                            1.5.dp,
                                            if (completed) GOLD else BORDER,
                                            RoundedCornerShape(5.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (completed) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(R.string.cd_mark_incomplete),
                                            tint = Color(0xFF0E0E0E),
                                            modifier = Modifier.size(17.dp),
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(R.string.cd_mark_complete),
                                            tint = Color.Transparent,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // Empty listId means unclassified. Inbox and Today are filters, not lists.
                    DetailSectionLabel(stringResource(R.string.section_list))
                    Spacer(Modifier.height(10.dp))
                    val currentList = userLists.firstOrNull { it.id == listId }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1F1F1F), RoundedCornerShape(10.dp))
                            .border(1.dp, BORDER, RoundedCornerShape(10.dp))
                            .clickable {
                                renaming = false
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                showTypeSheet = false
                                showListPicker = true
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            currentList?.name ?: stringResource(R.string.list_none),
                            color = if (currentList != null) Color(0xFFE0D8C8) else DIM,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = DIM,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 时间
                    DetailSectionLabel(stringResource(R.string.section_time))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeChip(
                            label = stringResource(R.string.label_start), value = start.atZone(zone).format(dateFmt),
                            onClick = { pickDateTime(start) { newStart ->
                                start = newStart
                                // 开始时间不能晚于结束时间，否则自动把结束时间往后推 1 小时
                                if (!newStart.isBefore(end)) {
                                    end = newStart.plusSeconds(3600)
                                }
                                persistDraft()
                            } }
                        )
                        TimeChip(
                            label = stringResource(R.string.label_end), value = end.atZone(zone).format(dateFmt),
                            onClick = { pickDateTime(end) { newEnd ->
                                end = newEnd
                                // 结束时间不能早于开始时间，否则自动把开始时间往前推 1 小时
                                if (!newEnd.isAfter(start)) {
                                    start = newEnd.minusSeconds(3600)
                                }
                                persistDraft()
                            } }
                        )
                    }

                    if (type == "task") {
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = BORDER)
                        Spacer(Modifier.height(20.dp))

                        DetailSectionLabel(stringResource(R.string.section_recurrence))
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "none" to stringResource(R.string.recurrence_none),
                                "daily" to stringResource(R.string.recurrence_daily),
                                "weekly" to stringResource(R.string.recurrence_weekly),
                                "monthly" to stringResource(R.string.recurrence_monthly),
                            ).forEach { (value, label) ->
                                ListAssignmentChip(
                                    label = label,
                                    selected = recurrenceType == value,
                                    onClick = {
                                        recurrenceType = value
                                        if (value != "none" && recurrenceCount < 2) {
                                            recurrenceCount = 2
                                        }
                                        persistDraft()
                                    },
                                )
                            }
                        }
                        if (recurrenceType != "none") {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    stringResource(R.string.recurrence_count, recurrenceCount),
                                    modifier = Modifier.weight(1f),
                                    color = Color(0xFFE0D8C8),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Color(0xFF1F1F1F), RoundedCornerShape(9.dp))
                                        .border(1.dp, BORDER, RoundedCornerShape(9.dp))
                                        .clickable(enabled = recurrenceCount > 1) {
                                            recurrenceCount--
                                            persistDraft()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("−", color = Color(0xFFE0D8C8), fontSize = 20.sp)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Color(0xFF1F1F1F), RoundedCornerShape(9.dp))
                                        .border(1.dp, BORDER, RoundedCornerShape(9.dp))
                                        .clickable(enabled = recurrenceCount < MAX_TASK_RECURRENCE_COUNT) {
                                            recurrenceCount++
                                            persistDraft()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("+", color = Color(0xFFE0D8C8), fontSize = 18.sp)
                                }
                            }
                            Text(
                                stringResource(R.string.recurrence_independent_hint),
                                color = DIM,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 与任务生命周期绑定的系统闹铃：改时间自动重排，完成/删除自动取消。
                    DetailSectionLabel(stringResource(R.string.section_alarm))
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.alarm_enabled_title),
                                color = Color(0xFFE0D8C8),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.alarm_enabled_description),
                                color = DIM,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                        Switch(
                            checked = alarmEnabled,
                            onCheckedChange = {
                                alarmEnabled = it
                                persistDraft()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0E0E0E),
                                checkedTrackColor = GOLD,
                                uncheckedThumbColor = DIM,
                                uncheckedTrackColor = Color(0xFF252525),
                            ),
                        )
                    }
                    if (alarmEnabled) {
                        val offsets = remember(alarmOffsetMinutes) {
                            (listOf(0, 5, 10, 15, 30, 60) + alarmOffsetMinutes)
                                .filter { it in 0..MAX_ALARM_OFFSET_MINUTES }
                                .distinct()
                                .sorted()
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            offsets.forEach { minutes ->
                                val selected = minutes == alarmOffsetMinutes
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (selected) GOLD else Color(0xFF1F1F1F),
                                            RoundedCornerShape(20.dp),
                                        )
                                        .border(1.dp, if (selected) GOLD else BORDER, RoundedCornerShape(20.dp))
                                        .clickable {
                                            alarmOffsetMinutes = minutes
                                            persistDraft()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        alarmOffsetLabel(minutes),
                                        color = if (selected) Color(0xFF0E0E0E) else Color(0xFFE0D8C8),
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        if (!TaskAlarmScheduler.canScheduleExactAlarms(context)) {
                            Text(
                                stringResource(R.string.alarm_exact_permission_hint),
                                color = GOLD,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .clickable { TaskAlarmScheduler.requestExactAlarmAccess(context) },
                            )
                        }
                    }

                    // 清单只对「任务」类型有意义——事件/提醒不是待办事项，不需要子项打勾。
                    if (type == "task") {
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = BORDER)
                        Spacer(Modifier.height(20.dp))

                        DetailSectionLabel(stringResource(R.string.section_checklist))
                        Spacer(Modifier.height(10.dp))
                        checklist.forEachIndexed { idx, item ->
                            ChecklistRow(
                                item = item,
                                onToggle = {
                                    checklist = checklist.toMutableList()
                                        .also { it[idx] = item.copy(completed = !item.completed) }
                                    persistDraft()
                                },
                                onTextChange = { newText ->
                                    checklist = checklist.toMutableList()
                                        .also { it[idx] = item.copy(text = newText) }
                                    persistDraft()
                                },
                                onDelete = {
                                    checklist = checklist.toMutableList().also { it.removeAt(idx) }
                                    persistDraft()
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
                                    persistDraft()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = DIM, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.action_add_item), color = DIM, fontSize = 14.sp)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = BORDER)
                    Spacer(Modifier.height(20.dp))

                    // 备注——手动输入不需要 MD 工具栏，但同步来的内容可能携带 PC 端写的 MD，
                    // 查看态要渲染，而不是把 "##"/"**" 之类原样显示给用户。
                    DetailSectionLabel(stringResource(R.string.section_note))
                    Spacer(Modifier.height(10.dp))
                    MarkdownField(
                        content = note,
                        onSave = { newNote ->
                            note = newNote
                            persistDraft()
                        },
                        placeholder = stringResource(R.string.note_placeholder),
                        contentPadding = PaddingValues(0.dp),
                        // 详情页仅在这里预览；编辑交给根层的全屏 MarkdownEditor。
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        onEditRequest = {
                            renaming = false
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            showTypeSheet = false
                            noteEditorDraft = note
                            noteEditorCloseRequested = false
                            noteEditorOpen = true
                        },
                    )

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
                                    .clickable {
                                        colorId = idx
                                        persistDraft()
                                    }
                            )
                        }
                    }

                    Spacer(Modifier.height(40.dp))
                }
            }

            // 修改类型底部面板
            if (showTypeSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showTypeSheet = false },
                    sheetState       = typeSheetState,
                    containerColor   = Color(0xFF1A1A1A),
                    dragHandle       = null,
                ) {
                    ItemTypeChangeSheet(
                        currentType = type,
                        onSelect = { newType ->
                            if (newType != type) {
                                type = newType
                                // 切换到非任务类型时清空清单和完成状态
                                if (newType != "task") {
                                    checklist = emptyList()
                                    completed = false
                                }
                                persistDraft()
                            }
                            showTypeSheet = false
                        },
                        onDismiss = { showTypeSheet = false }
                    )
                }
            }

            // 清单选择底部面板——只列出自定义清单；没有清单时复用「新建清单」入口
            if (showListPicker) {
                ModalBottomSheet(
                    onDismissRequest = { showListPicker = false },
                    sheetState       = listPickerSheetState,
                    containerColor   = Color(0xFF1A1A1A),
                    dragHandle       = null,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    ) {
                        // 顶部居中的拖拽把手（统一弹窗样式）
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .background(Color(0xFF4A4A4A), RoundedCornerShape(2.dp)),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.list_picker_title),
                                color = Color(0xFFE0D8C8), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                            )
                            Icon(
                                Icons.Default.Close, contentDescription = "Close", tint = DIM,
                                modifier = Modifier.size(18.dp).clickable { showListPicker = false },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        userLists.forEach { list ->
                            val selected = list.id == listId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!selected) {
                                            listId = list.id
                                            persistDraft()
                                        }
                                        showListPicker = false
                                    }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    list.name,
                                    color = if (selected) GOLD else Color(0xFFE0D8C8),
                                    fontSize = 16.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check, contentDescription = null,
                                        tint = GOLD, modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        // 与清单项分隔线隔开，突出「新建清单」操作入口
                        Box(
                            Modifier
                                .padding(horizontal = 20.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BORDER),
                        )
                        Spacer(Modifier.height(4.dp))
                        // 新建清单——沿用任务页的命名面板流程
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                showListPicker = false
                                showNewListNameSheet = true
                            }.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(52.dp)
                                    .background(Color(0xFF2E2E2E), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null,
                                    tint = DIM, modifier = Modifier.size(26.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    stringResource(R.string.list_new), color = Color(0xFFE0D8C8),
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.8.sp,
                                )
                                Text(
                                    stringResource(R.string.list_new_description),
                                    color = DIM, fontSize = 13.sp, letterSpacing = 0.3.sp,
                                )
                            }
                        }
                    }
                }
            }

            if (showNewListNameSheet) {
                NameInputSheet(
                    type = "task",
                    entityLabel = stringResource(R.string.list_entity_name),
                    initialText = "",
                    onDraftChange = {},
                    onCancel = { showNewListNameSheet = false },
                    onConfirm = { name ->
                        onCreateList(name.trim()) { created ->
                            showNewListNameSheet = false
                            if (created != null) {
                                listId = created.id
                                persistDraft()
                            }
                        }
                    },
                )
            }

            if (noteEditorOpen) {
                MarkdownEditor(
                    value = noteEditorDraft,
                    onValueChange = { text ->
                        noteEditorDraft = text
                        persistDraft()
                    },
                    placeholder = stringResource(R.string.note_placeholder),
                    onSaveAndClose = ::closeNoteEditor,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f)
                        .imePadding(),
                    showToolbar = true,
                )
            }
        }
    }
}

@Composable
private fun DetailSectionLabel(text: String) {
    Text(text, color = Color(0xFF6B5928), fontSize = 13.sp, letterSpacing = 0.12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ListAssignmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) GOLD else Color(0xFF1F1F1F),
                RoundedCornerShape(20.dp),
            )
            .border(1.dp, if (selected) GOLD else BORDER, RoundedCornerShape(20.dp))
            .clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF0E0E0E) else Color(0xFFE0D8C8),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun alarmOffsetLabel(minutes: Int): String = when {
    minutes == 0 -> stringResource(R.string.alarm_at_start)
    minutes % (24 * 60) == 0 -> {
        val days = minutes / (24 * 60)
        pluralStringResource(R.plurals.alarm_days_before, days, days)
    }
    minutes % 60 == 0 -> stringResource(R.string.alarm_hours_before, minutes / 60)
    else -> stringResource(R.string.alarm_minutes_before, minutes)
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
