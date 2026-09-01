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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamhuo.tplanner.designsystem.TPlannerGeometry
import com.hamhuo.tplanner.designsystem.TPlannerTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListPickerSheet(
    selectedView: TaskView,
    userLists: List<UserList>,
    listSheetState: androidx.compose.material3.SheetState,
    onSelectView: (String) -> Unit,
    onDismiss: () -> Unit,
    onNewListRequest: () -> Unit,
    onDeleteList: (String) -> Unit,
    scope: CoroutineScope = rememberCoroutineScope(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = listSheetState,
        containerColor = SURFACE,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
        ) {
            // 拖拽把手
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width(36.dp).height(4.dp)
                        .background(DRAG_HANDLE, RoundedCornerShape(TPlannerGeometry.RadiusSmallDp.dp))
                )
            }
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.task_view_picker_title),
                    color = TEXT_PRIMARY,
                    fontSize = TPlannerTypography.PhoneSectionSp.sp,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    Icons.Default.Close, contentDescription = "Close", tint = DIM,
                    modifier = Modifier.size(18.dp).clickable { onDismiss() },
                )
            }
            Spacer(Modifier.height(12.dp))
            val renderItem: @Composable (TaskView) -> Unit = { item ->
                val isSelected = selectedView.key == item.key
                val isCustom = item is TaskView.CustomList
                val row = @Composable {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // The swipe background must never bleed through an idle row.
                            .background(SURFACE)
                            .clickable { onSelectView(item.key) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(52.dp)
                                .background(if (isSelected) GOLD else CONTROL_STRONG, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = when (item) {
                                    is TaskView.Today -> Icons.Filled.Today
                                    is TaskView.Inbox -> Icons.Filled.Inbox
                                    is TaskView.CustomList -> Icons.Filled.Inbox
                                },
                                contentDescription = null,
                                tint = if (isSelected) BG else TEXT_PRIMARY,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                when (item) {
                                    is TaskView.Today -> stringResource(R.string.list_today)
                                    is TaskView.Inbox -> stringResource(R.string.list_inbox)
                                    is TaskView.CustomList -> item.name
                                },
                                color = if (isSelected) GOLD else TEXT_PRIMARY,
                                fontSize = TPlannerTypography.PhoneBodySp.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                when (item) {
                                    is TaskView.Today -> stringResource(R.string.task_view_today_description)
                                    is TaskView.Inbox -> stringResource(R.string.task_view_inbox_description)
                                    is TaskView.CustomList -> stringResource(R.string.task_view_custom_list_description)
                                },
                                color = DIM, fontSize = TPlannerTypography.PhoneMetaSp.sp,
                            )
                        }
                    }
                }
                if (isCustom) {
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                scope.launch { onDeleteList(item.key) }
                            }
                            true
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        modifier = Modifier.fillMaxWidth(),
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(RED),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.cd_delete),
                                    tint = Color.White,
                                    modifier = Modifier.padding(end = 20.dp),
                                )
                            }
                        }
                    ) { row() }
                } else {
                    row()
                }
            }

            Text(
                stringResource(R.string.task_view_filters),
                color = DIM,
                fontSize = TPlannerTypography.PhoneCaptionSp.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            TaskView.FILTERS.forEach { renderItem(it) }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.task_view_custom_lists),
                color = DIM,
                fontSize = TPlannerTypography.PhoneCaptionSp.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            userLists.map { TaskView.CustomList(it.id, it.name) }.forEach { renderItem(it) }
            Spacer(Modifier.height(8.dp))
            // 新建清单
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    onDismiss()
                    onNewListRequest()
                }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier.size(52.dp)
                        .background(CONTROL_STRONG, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null,
                        tint = DIM, modifier = Modifier.size(26.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.list_new), color = TEXT_PRIMARY,
                        fontSize = TPlannerTypography.PhoneBodySp.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.list_new_description), color = DIM, fontSize = TPlannerTypography.PhoneMetaSp.sp)
                }
            }
        }
    }
}
