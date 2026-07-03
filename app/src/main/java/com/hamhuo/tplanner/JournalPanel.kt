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
import androidx.compose.foundation.layout.PaddingValues
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
    serverUrl: String,
    syncStatus: String,
    syncMsg: String,
    canSync: Boolean,
    onUrlChange: (String) -> Unit,
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
                Text(stringResource(R.string.sync_server_title), color = DIM, fontSize = 10.sp, letterSpacing = 0.1.sp)
                Text("✕", color = DIM, fontSize = 12.sp, modifier = Modifier.clickable { onClose() })
            }

            // 服务器地址
            MonoInput(
                value       = serverUrl,
                placeholder = LanSyncManager.DEFAULT_SERVER_URL,
                onValue     = onUrlChange,
                modifier    = Modifier.fillMaxWidth()
            )

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

// 编辑态渲染原始文本输入框，查看态渲染 Markdown 预览（WebView），点击切换两种渲染代码，
// 而不是做单一控件内的实时叠加 WYSIWYG。随手记和任务详情页的备注共用这一套组件——
// 安卓端手动输入不需要 MD 工具栏，但同步过来的内容可能携带 PC 端写的 MD，查看时要能正确渲染。
@Composable
fun MarkdownField(
    content: String,
    onSave: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 32.dp),
) {
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(content) }

    // 不在这里再追加 fillMaxSize()——调用方的 modifier 必须自带确定的尺寸
    // （weight()/明确的 height()），否则在可滚动的无限高度父级里会测不出尺寸，
    // 导致内部 BasicTextField 实际可点区域为零，看起来像“点了没反应、打不了字”。
    Box(modifier) {
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
                    .padding(contentPadding),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(placeholder, color = DIM, fontSize = 15.sp, lineHeight = 26.sp)
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
                    .padding(contentPadding)
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
