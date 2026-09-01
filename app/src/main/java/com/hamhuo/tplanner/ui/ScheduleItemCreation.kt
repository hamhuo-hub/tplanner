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
import com.hamhuo.tplanner.designsystem.TPlannerGeometry
import com.hamhuo.tplanner.designsystem.TPlannerTypography
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.UUID

@Composable
internal fun typeLabel(type: String): String = when (type) {
    "task"   -> stringResource(R.string.type_task)
    "event"  -> stringResource(R.string.type_event)
    "status" -> stringResource(R.string.type_status)
    else     -> stringResource(R.string.type_generic)
}

internal fun typeIcon(type: String): ImageVector = when (type) {
    "task"   -> Icons.Outlined.CheckCircle
    "event"  -> Icons.Outlined.Alarm
    "status" -> Icons.Filled.Star
    else     -> Icons.Outlined.CheckCircle
}

// ── 新建类型选择面板（点击任务面板 + 号后弹出） ──────────────────────────────────
@Composable
fun CreateItemTypeSheet(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
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
                    .background(DRAG_HANDLE, RoundedCornerShape(TPlannerGeometry.RadiusSmallDp.dp))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.label_new),
                color = TEXT_PRIMARY,
                fontSize = TPlannerTypography.PhoneSectionSp.sp,
                fontWeight = FontWeight.Bold,
            )
            Icon(Icons.Default.Close, contentDescription = "Close", tint = DIM, modifier = Modifier.size(18.dp).clickable { onDismiss() })
        }

        Spacer(Modifier.height(12.dp))

        AddTypeItem(
            icon   = Icons.Outlined.Alarm,
            title  = stringResource(R.string.type_event),
            desc   = stringResource(R.string.desc_event),
            onClick = { onSelect("event") }
        )
        AddTypeItem(
            icon   = Icons.Filled.Star,
            title  = stringResource(R.string.type_status),
            desc   = stringResource(R.string.desc_status),
            onClick = { onSelect("status") }
        )
        AddTypeItem(
            icon   = Icons.Outlined.CheckCircle,
            title  = stringResource(R.string.type_task),
            desc   = stringResource(R.string.desc_task),
            onClick = { onSelect("task") }
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
                .background(CONTROL_STRONG, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = TEXT_PRIMARY, modifier = Modifier.size(26.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TEXT_PRIMARY, fontSize = TPlannerTypography.PhoneBodySp.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = DIM, fontSize = TPlannerTypography.PhoneMetaSp.sp)
        }
    }
}

// ── 修改类型选择面板（点击已有事件的类型指示器后弹出） ──────────────────────────
@Composable
fun ItemTypeChangeSheet(currentType: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
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
                    .background(DRAG_HANDLE, RoundedCornerShape(TPlannerGeometry.RadiusSmallDp.dp))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.change_type_title),
                color = TEXT_PRIMARY,
                fontSize = TPlannerTypography.PhoneSectionSp.sp,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                Icons.Default.Close, contentDescription = "Close",
                tint = DIM, modifier = Modifier.size(18.dp).clickable { onDismiss() }
            )
        }

        Spacer(Modifier.height(12.dp))

        val types = listOf("event", "status", "task")
        types.forEach { type ->
            val isCurrent = type == currentType
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(type) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            if (isCurrent) GOLD else CONTROL_STRONG,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        typeIcon(type), contentDescription = null,
                        tint = if (isCurrent) BG else TEXT_PRIMARY,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            typeLabel(type),
                            color = TEXT_PRIMARY,
                            fontSize = TPlannerTypography.PhoneBodySp.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isCurrent) {
                            Box(
                                Modifier
                                    .background(GOLD, RoundedCornerShape(TPlannerGeometry.RadiusAccentMarkerDp.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    stringResource(R.string.current_label),
                                    color = BG,
                                    fontSize = TPlannerTypography.PhoneBadgeSp.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        when (type) {
                            "event"  -> stringResource(R.string.desc_event)
                            "status" -> stringResource(R.string.desc_status)
                            "task"   -> stringResource(R.string.desc_task)
                            else     -> ""
                        },
                        color = DIM, fontSize = TPlannerTypography.PhoneMetaSp.sp
                    )
                }
            }
        }
    }
}

// ── 命名半屏面板（选完类型后，先命名再进详情页） ──────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameInputSheet(
    type: String,
    entityLabel: String? = null,
    initialText: String? = null,
    onDraftChange: (String) -> Unit = {},
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val label = entityLabel ?: typeLabel(type)
    val defaultName = stringResource(R.string.default_name_template, label)
    val startingText = initialText?.takeIf { it.isNotBlank() } ?: defaultName
    var text by remember(type) {
        mutableStateOf(TextFieldValue(startingText, selection = TextRange(0, startingText.length)))
    }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState       = sheetState,
        containerColor   = SURFACE,
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
                color      = TEXT_PRIMARY,
                fontSize   = TPlannerTypography.PhoneModalTitleSp.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))
            BasicTextField(
                value         = text,
                onValueChange = {
                    text = it
                    onDraftChange(it.text)
                },
                textStyle     = TextStyle(
                    color      = GOLD,
                    fontSize   = TPlannerTypography.PhoneDisplaySp.sp,
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
            .background(if (filled) GOLD else Color.Transparent, RoundedCornerShape(TPlannerGeometry.RadiusPillDp.dp))
            .border(1.dp, if (filled) GOLD else BORDER, RoundedCornerShape(TPlannerGeometry.RadiusPillDp.dp))
            .clickable { onClick() }
            .padding(horizontal = 30.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color      = if (filled) BG else TEXT_PRIMARY,
            fontSize   = TPlannerTypography.PhoneTaskTitleSp.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── 任务详情页：时间 / 清单 / 备注 / 颜色 ──────────────────────────────────────
