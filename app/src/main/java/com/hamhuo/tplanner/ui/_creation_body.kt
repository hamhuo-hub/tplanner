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
            Text(
                stringResource(R.string.change_type_title),
                color = Color(0xFFE0D8C8), fontSize = 20.sp, fontWeight = FontWeight.Bold
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
                            if (isCurrent) GOLD else Color(0xFF2E2E2E),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        typeIcon(type), contentDescription = null,
                        tint = if (isCurrent) Color(0xFF0E0E0E) else Color(0xFFE0D8C8),
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
                            color = Color(0xFFE0D8C8),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isCurrent) {
                            Box(
                                Modifier
                                    .background(GOLD, RoundedCornerShape(3.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    stringResource(R.string.current_label),
                                    color = Color(0xFF0E0E0E),
                                    fontSize = 11.sp,
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
                        color = DIM, fontSize = 13.sp
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
    initialText: String? = null,
    onDraftChange: (String) -> Unit = {},
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val label = typeLabel(type)
    val defaultName = stringResource(R.string.default_name_template, label)
    val startingText = initialText?.takeIf { it.isNotBlank() } ?: defaultName
    var text by remember(type) {
        mutableStateOf(TextFieldValue(startingText, selection = TextRange(0, startingText.length)))
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
                onValueChange = {
                    text = it
                    onDraftChange(it.text)
                },
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
@OptIn(ExperimentalMaterial3Api::class)
