@file:OptIn(ExperimentalMaterial3Api::class)
package com.hamhuo.tplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CreateItemTypeSheet(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Choose a type",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF5F5F7)),
            )
            AddTypeItem(Icons.Filled.Star, "Reminder", "Remind you at the selected time") {
                onSelect("event")
            }
            AddTypeItem(Icons.Filled.Check, "Status", "Record your current status") {
                onSelect("status")
            }
            AddTypeItem(Icons.Filled.AutoAwesome, "Task", "Create a task you can complete") {
                onSelect("task")
            }
        }
    }
}

@Composable
private fun AddTypeItem(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color(0xFF202022), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFFFD60A), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF5F5F7)))
            Text(desc, style = TextStyle(fontSize = 12.sp, color = Color(0xFF8E8E93)))
        }
    }
}

@Composable
fun NameInputSheet(
    initialTitle: String,
    suggestedTitle: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var value by remember { mutableStateOf(initialTitle.ifBlank { suggestedTitle ?: "" }) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Name your item",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF5F5F7)),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .background(Color(0xFF202022), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    textStyle = TextStyle(fontSize = 17.sp, color = Color(0xFFF5F5F7)),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { value = "" }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Clear", tint = Color(0xFF8E8E93))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                PillButton("Cancel", filled = false) { onDismiss() }
                PillButton("Confirm", filled = true) { onConfirm(value.trim()) }
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (filled) Color(0xFFFFD60A) else Color.Transparent,
                RoundedCornerShape(20.dp),
            )
            .then(if (!filled) Modifier.border(1.dp, Color(0xFF8E8E93), RoundedCornerShape(20.dp)) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (filled) Color.Black else Color(0xFFF5F5F7)),
        )
    }
}
