package com.hamhuo.tplanner.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.SURFACE2

/** 转圈收起动画时长:结束后不是瞬间消失,而是滑出屏幕顶部。 */
private const val SYNC_INDICATOR_COLLAPSE_MILLIS = 250

/** Phone-home pull gesture shared by the Notes and Inbox pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TPlannerPullToSync(
    isSyncing: Boolean,
    onSync: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val currentOnSync by rememberUpdatedState(onSync)
    // 0 = 展示中,1 = 已滑出。material3 1.2.1 的 endRefresh() 会把 verticalOffset
    // 瞬时归零(没有结束动画参数),这里先用该进度把容器滑到收起位置再调用,视觉上无跳变。
    val collapse = remember { Animatable(0f) }

    LaunchedEffect(state.isRefreshing, enabled) {
        if (enabled && state.isRefreshing && !isSyncing) currentOnSync()
    }
    LaunchedEffect(isSyncing, enabled) {
        if (!enabled) {
            state.endRefresh()
        } else if (isSyncing) {
            collapse.snapTo(0f)
            state.startRefresh()
        } else if (state.isRefreshing) {
            collapse.animateTo(1f, tween(SYNC_INDICATOR_COLLAPSE_MILLIS))
            state.endRefresh()
            collapse.snapTo(0f)
        }
    }

    Box(
        modifier = modifier.then(
            if (enabled) Modifier.nestedScroll(state.nestedScrollConnection) else Modifier,
        ),
    ) {
        content()
        if (enabled) {
            PullToRefreshContainer(
                state = state,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        // 叠加在容器自身 translationY = verticalOffset - height 之上:
                        // 收起时按当前 verticalOffset 滑回原位,与回弹动画观感一致。
                        translationY = -collapse.value * state.verticalOffset
                    },
                containerColor = SURFACE2,
                contentColor = GOLD,
            )
        }
    }
}
