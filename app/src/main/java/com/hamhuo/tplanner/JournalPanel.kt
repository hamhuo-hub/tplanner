package com.hamhuo.tplanner

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun NotesHeader(syncStatus: String, onPanelToggle: () -> Unit) {
    val datePattern = stringResource(R.string.date_pattern_full)
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern(datePattern))
    val iconColor = when (syncStatus) {
        "success" -> TEAL
        "error"   -> RED
        "syncing" -> GOLD
        else      -> DIM
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.tab_journal), color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(today, color = DIM, fontSize = 14.sp)
        }
        IconButton(onClick = onPanelToggle) {
            Text("⇄", color = iconColor, fontSize = 17.sp)
        }
    }
}

@Composable
fun SyncPanel(
    modifier: Modifier,
    scanning: Boolean,
    peers: List<LanSyncManager.Peer>,
    selected: LanSyncManager.Peer?,
    manualIp: String,
    manualPort: String,
    syncStatus: String,
    syncMsg: String,
    canSync: Boolean,
    onScan: () -> Unit,
    onSelect: (LanSyncManager.Peer) -> Unit,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    val msgColor = when (syncStatus) {
        "success" -> TEAL; "error" -> RED; else -> GOLD
    }

    Card(
        modifier  = modifier.width(270.dp).shadow(16.dp, RoundedCornerShape(10.dp)),
        shape     = RoundedCornerShape(10.dp),
        colors    = CardDefaults.cardColors(containerColor = SURFACE2),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // 标题行
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lan_sync_title), color = DIM, fontSize = 10.sp, letterSpacing = 0.1.sp)
                Text("✕", color = DIM, fontSize = 12.sp, modifier = Modifier.clickable { onClose() })
            }

            // 扫描按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(5.dp))
                    .clickable(enabled = !scanning) { onScan() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (scanning) stringResource(R.string.scanning_label) else stringResource(R.string.scan_lan_label),
                    color = if (scanning) DIM else Color(0xFFE0D8C8),
                    fontSize = 11.sp
                )
            }

            // 发现的服务器列表
            if (peers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    peers.forEach { peer ->
                        val isSelected = selected?.ip == peer.ip && selected?.port == peer.port
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) Color(0x265B8FCC) else Color(0x08FFFFFF),
                                    RoundedCornerShape(5.dp)
                                )
                                .border(1.dp, if (isSelected) Color(0x805B8FCC) else BORDER, RoundedCornerShape(5.dp))
                                .clickable { onSelect(peer) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(peer.name, color = Color(0xFFE0D8C8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("${peer.ip}:${peer.port}", color = DIM, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text(stringResource(R.string.journal_count_template, peer.journalCount), color = DIM, fontSize = 9.sp)
                        }
                    }
                }
            } else if (!scanning) {
                Text(stringResource(R.string.no_devices_found), color = DIM, fontSize = 10.sp)
            }

            // 手动 IP 输入
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoInput(
                    value       = manualIp,
                    placeholder = "192.168.x.x",
                    onValue     = onIpChange,
                    modifier    = Modifier.weight(1f)
                )
                MonoInput(
                    value       = manualPort,
                    placeholder = "37401",
                    onValue     = onPortChange,
                    modifier    = Modifier.width(60.dp)
                )
            }

            // 状态
            if (syncMsg.isNotBlank()) {
                Text(syncMsg, color = msgColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            // 同步按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (canSync) BLUE else Color(0xFF2A2A2A), RoundedCornerShape(5.dp))
                    .clickable(enabled = canSync) { onSync() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = if (syncStatus == "syncing") stringResource(R.string.syncing_label) else stringResource(R.string.sync_now_label),
                    color = if (canSync) Color.White else DIM,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun MonoInput(value: String, placeholder: String, onValue: (String) -> Unit, modifier: Modifier) {
    BasicTextField(
        value         = value,
        onValueChange = onValue,
        singleLine    = true,
        textStyle     = TextStyle(color = Color(0xFFE0D8C8), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        cursorBrush   = SolidColor(GOLD),
        modifier      = modifier
            .background(Color(0xFF111111), RoundedCornerShape(4.dp))
            .border(1.dp, BORDER, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            inner()
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownViewer(content: String, modifier: Modifier = Modifier) {
    var webView   by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    LaunchedEffect(pageReady, content) {
        if (!pageReady) return@LaunchedEffect
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        webView?.evaluateJavascript("renderBase64('$b64')", null)
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory  = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(0x00000000)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { pageReady = true }
                }
                loadUrl("file:///android_asset/md_viewer.html")
                webView = this
            }
        },
        update = { view -> webView = view }
    )
}

// 与 PC 端同一套思路：编辑态渲染原始文本输入框，查看态渲染 Markdown 预览
// （WebView），点击切换两种渲染代码，而不是做单一控件内的实时叠加 WYSIWYG。
@Composable
fun JournalEditor(content: String, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(content) }

    Box(modifier.fillMaxSize()) {
        if (isEditing) {
            val focusRequester = remember { FocusRequester() }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                // 字号/行高需要跟 md_viewer.html 的 body 样式（15px、1.75 行高）保持一致，
                // 内边距统一交给下方 MarkdownViewer 调用处的 Compose padding（同一份数值），
                // 不再各自在 HTML/Compose 两端分别设置，避免 CSS px 与 dp 没法保证 1:1 对齐。
                textStyle = TextStyle(color = Color(0xFFE8E0D0), fontSize = 15.sp, lineHeight = 26.sp),
                cursorBrush = SolidColor(GOLD),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 32.dp),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(stringResource(R.string.journal_edit_hint), color = DIM, fontSize = 15.sp, lineHeight = 26.sp)
                    }
                    inner()
                }
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            IconButton(
                onClick = { isEditing = false; onSave(draft) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
            ) {
                Text("✓", color = GOLD, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // 内边距统一在 Compose 侧加（md_viewer.html 自身不再设 padding），
            // 否则 WebView 里的 CSS px 和这里的 dp 不保证 1:1，对不齐。
            MarkdownViewer(
                content = content,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 32.dp)
            )
            // WebView 会吞掉点击事件，叠一层透明可点层用来进入编辑态（整卡可点，不受内边距影响）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { draft = content; isEditing = true }
            )
        }
    }
}
