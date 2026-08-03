package com.hamhuo.tplanner

import android.annotation.SuppressLint
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import java.time.format.DateTimeFormatter

@Composable
fun NotesHeader(syncStatus: String, onPanelToggle: () -> Unit) {
    val datePattern = stringResource(R.string.date_pattern_full)
    val today = appToday().format(DateTimeFormatter.ofPattern(datePattern))
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
            Icon(Icons.Default.SwapHoriz, contentDescription = "Sync", tint = iconColor, modifier = Modifier.size(20.dp))
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
                Icon(Icons.Default.Close, contentDescription = "Close", tint = DIM, modifier = Modifier.size(14.dp).clickable { onClose() })
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

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun MarkdownViewer(
    content: String,
    onTap: () -> Unit = {},
    onRendered: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var webView   by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnRendered by rememberUpdatedState(onRendered)

    val context = LocalContext.current
    val gestureDetector = remember {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                currentOnTap()
                return false
            }
        })
    }

    LaunchedEffect(pageReady, content) {
        if (!pageReady) return@LaunchedEffect
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        webView?.evaluateJavascript("renderBase64('$b64')") {
            currentOnRendered(content)
        }
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
                var lastTouchY = 0f
                // The task detail page wraps this fixed-height preview in a Compose
                // verticalScroll. Keep drags in the WebView while it can scroll, then
                // hand them back to the outer page at the top/bottom edge.
                setOnTouchListener { view, event ->
                    gestureDetector.onTouchEvent(event)

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastTouchY = event.y
                            view.parent?.requestDisallowInterceptTouchEvent(
                                view.canScrollVertically(-1) || view.canScrollVertically(1)
                            )
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val deltaY = event.y - lastTouchY
                            val scrollDirection = when {
                                deltaY < 0f -> 1
                                deltaY > 0f -> -1
                                else -> 0
                            }
                            val canScroll = if (scrollDirection == 0) {
                                view.canScrollVertically(-1) || view.canScrollVertically(1)
                            } else {
                                view.canScrollVertically(scrollDirection)
                            }
                            view.parent?.requestDisallowInterceptTouchEvent(canScroll)
                            lastTouchY = event.y
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }

                    false // always let WebView handle the event for scrolling
                }
                loadUrl("file:///android_asset/md_viewer.html")
                webView = this
            }
        },
        update = { view -> webView = view }
    )
}

// 查看态渲染 Markdown 预览（WebView），编辑态渲染原始文本输入框，
// 点击 WebView 或编辑按钮进入编辑，返回手势保存并退出编辑。
// 随手记和任务详情页的备注共用这一套组件——安卓端手动输入不需要 MD 工具栏，
// 但同步过来的内容可能携带 PC 端写的 MD，查看时要能正确渲染。
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkdownField(
    content: String,
    onSave: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 32.dp),
    onDraftChange: (String) -> Unit = {},
) {
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(content) }
    var imeWasVisible by remember { mutableStateOf(false) }
    var exitRequested by remember { mutableStateOf(false) }
    var lastRenderedContent by remember { mutableStateOf<String?>(null) }
    var previewContent by remember { mutableStateOf(content) }
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun saveAndExitEditing() {
        if (!isEditing || exitRequested) return
        exitRequested = true
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onSave(draft)
        if (lastRenderedContent == draft) {
            exitRequested = false
            isEditing = false
        }
    }

    // IME 会优先消费第一次返回。键盘由可见变为隐藏时立即提交，
    // 因而一次返回即可同时保存、退出编辑并关闭键盘。
    LaunchedEffect(isEditing, imeVisible) {
        when {
            !isEditing -> imeWasVisible = false
            imeVisible -> imeWasVisible = true
            imeWasVisible -> saveAndExitEditing()
        }
    }
    BackHandler(enabled = isEditing) { saveAndExitEditing() }

    // 调用方的 modifier 必须自带确定的尺寸（weight()/明确的 height()），
    // 否则在可滚动的无限高度父级里会测不出尺寸。
    Box(modifier) {
        MarkdownViewer(
            content = when {
                exitRequested -> draft
                isEditing -> previewContent
                else -> content
            },
            onTap = {
                if (!isEditing) {
                    draft = content
                    previewContent = content
                    exitRequested = false
                    isEditing = true
                }
            },
            onRendered = { renderedContent ->
                lastRenderedContent = renderedContent
                if (exitRequested && renderedContent == draft) {
                    exitRequested = false
                    isEditing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        )

        if (isEditing) {
            val focusRequester = remember { FocusRequester() }
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    onDraftChange(it)
                },
                textStyle = TextStyle(color = Color(0xFFE8E0D0), fontSize = 15.sp, lineHeight = 26.sp),
                cursorBrush = SolidColor(GOLD),
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .padding(contentPadding)
                    .background(SURFACE)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(placeholder, color = DIM, fontSize = 15.sp, lineHeight = 26.sp)
                    }
                    inner()
                }
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            IconButton(
                onClick = {
                    draft = content
                    previewContent = content
                    exitRequested = false
                    isEditing = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = GOLD, modifier = Modifier.size(18.dp))
            }
        }
    }
}
