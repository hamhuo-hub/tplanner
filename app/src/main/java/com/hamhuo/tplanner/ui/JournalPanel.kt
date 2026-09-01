package com.hamhuo.tplanner

import android.annotation.SuppressLint
import android.util.Base64
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.webkit.WebResourceRequest
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.hamhuo.tplanner.designsystem.TPlannerGeometry
import com.hamhuo.tplanner.designsystem.TPlannerTypography
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import kotlin.math.abs

private const val MARKDOWN_VIEWER_URL = "file:///android_asset/md_viewer.html"

@Composable
fun NotesHeader(date: LocalDate, onPanelToggle: () -> Unit) {
    val datePattern = stringResource(R.string.date_pattern_full)
    val today = date.format(DateTimeFormatter.ofPattern(datePattern))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.tab_journal), color = GOLD, fontSize = TPlannerTypography.PhoneTitleSp.sp, fontWeight = FontWeight.Bold)
            Text(today, color = DIM, fontSize = TPlannerTypography.PhoneSupportingSp.sp)
        }
        IconButton(onClick = onPanelToggle) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.sync_server_title),
                tint = DIM,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun SyncSettingsPanel(
    modifier: Modifier,
    serverUrl: String,
    syncStatus: String,
    syncMsg: String,
    onUrlChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val msgColor = when (syncStatus) {
        "success" -> TEAL; "error" -> RED; else -> GOLD
    }

    Card(
        modifier  = modifier.width(270.dp).shadow(16.dp, RoundedCornerShape(TPlannerGeometry.RadiusPanelDp.dp)),
        shape     = RoundedCornerShape(TPlannerGeometry.RadiusPanelDp.dp),
        colors    = CardDefaults.cardColors(containerColor = SURFACE2),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // 标题行
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sync_server_title), color = DIM, fontSize = TPlannerTypography.PhoneMicroSp.sp, letterSpacing = 0.1.sp)
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
                Text(syncMsg, color = msgColor, fontSize = TPlannerTypography.PhoneMicroSp.sp, fontFamily = FontFamily.Monospace)
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
        textStyle     = TextStyle(color = TEXT_PRIMARY, fontSize = TPlannerTypography.PhoneBadgeSp.sp, fontFamily = FontFamily.Monospace),
        cursorBrush   = SolidColor(GOLD),
        modifier      = modifier
            .background(INPUT_SURFACE, RoundedCornerShape(TPlannerGeometry.RadiusControlDp.dp))
            .border(1.dp, BORDER, RoundedCornerShape(TPlannerGeometry.RadiusControlDp.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = DIM, fontSize = TPlannerTypography.PhoneBadgeSp.sp, fontFamily = FontFamily.Monospace)
            inner()
        }
    )
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun MarkdownViewer(
    content: String,
    onTap: () -> Unit = {},
    onPullRefresh: (() -> Unit)? = null,
    onRendered: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var webView   by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnPullRefresh by rememberUpdatedState(onPullRefresh)
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
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.domStorageEnabled = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = request.url.toString() != MARKDOWN_VIEWER_URL

                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        url != MARKDOWN_VIEWER_URL

                    override fun onPageFinished(view: WebView, url: String) {
                        pageReady = url == MARKDOWN_VIEWER_URL
                    }
                }
                var lastTouchY = 0f
                var pullStartX = 0f
                var pullStartY = 0f
                var pullEligible = false
                var pullTriggered = false
                val pullThreshold = 72f * resources.displayMetrics.density
                // The task detail page wraps this fixed-height preview in a Compose
                // verticalScroll. Keep drags in the WebView while it can scroll, then
                // hand them back to the outer page at the top/bottom edge.
                setOnTouchListener { view, event ->
                    gestureDetector.onTouchEvent(event)

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastTouchY = event.y
                            pullStartX = event.x
                            pullStartY = event.y
                            pullEligible = !view.canScrollVertically(-1)
                            pullTriggered = false
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
                            val pullDistance = event.y - pullStartY
                            if (
                                pullEligible &&
                                !pullTriggered &&
                                pullDistance >= pullThreshold &&
                                pullDistance > abs(event.x - pullStartX) * 1.2f
                            ) {
                                pullTriggered = true
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                currentOnPullRefresh?.invoke()
                            }
                            lastTouchY = event.y
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            pullEligible = false
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }

                    false // always let WebView handle the event for scrolling
                }
                loadUrl(MARKDOWN_VIEWER_URL)
                webView = this
            }
        },
        update = { view -> webView = view }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkdownEditor(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSaveAndClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 32.dp),
    showToolbar: Boolean = false,
) {
    var finishRequested by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    fun finishEditing() {
        if (finishRequested) return
        finishRequested = true
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onSaveAndClose(value)
    }

    BackHandler {
        if (imeVisible) {
            keyboardController?.hide()
        } else {
            finishEditing()
        }
    }

    Column(modifier.background(SURFACE)) {
        if (showToolbar) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::finishEditing) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = TEXT_PRIMARY,
                        )
                    }
                    Text(
                        stringResource(R.string.section_note),
                        color = TEXT_PRIMARY,
                        fontSize = TPlannerTypography.PhoneTitleSp.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Box(
                    modifier = Modifier
                        .background(GOLD, RoundedCornerShape(TPlannerGeometry.RadiusPillDp.dp))
                        .clickable(onClick = {
                            if (imeVisible) {
                                keyboardController?.hide()
                            } else {
                                finishEditing()
                            }
                        })
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.action_done),
                        color = BG,
                        fontSize = TPlannerTypography.PhoneSupportingSp.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = finishRequested,
            textStyle = TextStyle(
                color = TEXT_EDITOR,
                fontSize = TPlannerTypography.PhoneTaskTitleSp.sp,
                lineHeight = TPlannerTypography.PhoneBodyLineHeightSp.sp,
            ),
            cursorBrush = SolidColor(GOLD),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(contentPadding)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        color = DIM,
                        fontSize = TPlannerTypography.PhoneTaskTitleSp.sp,
                        lineHeight = TPlannerTypography.PhoneBodyLineHeightSp.sp,
                    )
                }
                inner()
            }
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

// 查看态渲染 Markdown 预览（WebView），编辑态复用 MarkdownEditor。
// onEditRequest 非空时，本组件只负责预览，由调用方在更高层展示全屏编辑器。
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkdownField(
    content: String,
    onSave: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 32.dp),
    onEditStart: suspend () -> String? = { null },
    onDraftChange: (String) -> Unit = {},
    onEditingChange: (Boolean) -> Unit = {},
    onPullRefresh: (() -> Unit)? = null,
    onEditRequest: (() -> Unit)? = null,
    onPreviewRendered: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    var editOpening by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(content) }
    var exitRequested by remember { mutableStateOf(false) }
    var lastRenderedContent by remember { mutableStateOf<String?>(null) }
    var previewContent by remember { mutableStateOf(content) }

    fun beginEditing() {
        if (onEditRequest != null) {
            onEditRequest()
            return
        }
        if (editOpening || isEditing) return
        editOpening = true
        scope.launch {
            try {
                // Capture the authoritative revision before the editor can accept input. This
                // keeps a sync that finishes while editing from silently becoming the draft base.
                val sessionContent = onEditStart() ?: content
                draft = sessionContent
                previewContent = sessionContent
                exitRequested = false
                isEditing = true
                onEditingChange(true)
            } finally {
                editOpening = false
            }
        }
    }

    fun saveAndExitEditing(updatedDraft: String) {
        if (!isEditing || exitRequested) return
        draft = updatedDraft
        exitRequested = true
        onSave(updatedDraft)
        // Durable save/conflict handling belongs to the Store callback. WebView rendering is only
        // presentation and must never be the gate that lets the user leave the editor.
        exitRequested = false
        isEditing = false
        onEditingChange(false)
    }

    // 调用方的 modifier 必须自带确定的尺寸（weight()/明确的 height()），
    // 否则在可滚动的无限高度父级里会测不出尺寸。
    Box(modifier) {
        MarkdownViewer(
            content = when {
                exitRequested -> draft
                isEditing -> previewContent
                else -> content
            },
            onTap = { if (!isEditing) beginEditing() },
            onPullRefresh = onPullRefresh,
            onRendered = { renderedContent ->
                lastRenderedContent = renderedContent
                onPreviewRendered(renderedContent)
                if (exitRequested && renderedContent == draft) {
                    exitRequested = false
                    isEditing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )

        if (isEditing) {
            MarkdownEditor(
                value = draft,
                onValueChange = {
                    draft = it
                    onDraftChange(it)
                },
                placeholder = placeholder,
                onSaveAndClose = ::saveAndExitEditing,
                modifier = Modifier.fillMaxSize().zIndex(1f),
                contentPadding = contentPadding,
            )
        } else {
            IconButton(
                onClick = ::beginEditing,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = GOLD, modifier = Modifier.size(18.dp))
            }
        }
    }
}
