package com.hamhuo.tplanner.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.SURFACE2

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

    LaunchedEffect(state.isRefreshing, enabled) {
        if (enabled && state.isRefreshing && !isSyncing) currentOnSync()
    }
    LaunchedEffect(isSyncing, enabled) {
        if (!enabled) {
            state.endRefresh()
        } else if (isSyncing) {
            state.startRefresh()
        } else {
            state.endRefresh()
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
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = SURFACE2,
                contentColor = GOLD,
            )
        }
    }
}
