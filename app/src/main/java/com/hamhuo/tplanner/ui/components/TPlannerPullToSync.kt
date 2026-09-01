package com.hamhuo.tplanner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hamhuo.tplanner.GOLD
import com.hamhuo.tplanner.SURFACE2
import kotlinx.coroutines.flow.distinctUntilChanged

/** One app-level pull gesture shared by Notes, Inbox, and Timeline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TPlannerPullToSync(
    isSyncing: Boolean,
    operationId: String?,
    onSync: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // This state belongs only to the physical pull gesture. Business progress is rendered from
    // [isSyncing] below and never calls startRefresh()/feeds back into this state.
    val gestureState = rememberPullToRefreshState()
    val currentOnSync by rememberUpdatedState(onSync)

    LaunchedEffect(gestureState, enabled) {
        var sawRestingState = !gestureState.isRefreshing
        snapshotFlow { gestureState.isRefreshing }
            .distinctUntilChanged()
            .collect { refreshing ->
                when {
                    !enabled -> {
                        if (refreshing) gestureState.endRefresh()
                        sawRestingState = true
                    }
                    !refreshing -> sawRestingState = true
                    sawRestingState -> {
                        // A single rising edge is the user gesture event. End the gesture state
                        // immediately; the coordinator's transaction state owns all later UI.
                        sawRestingState = false
                        currentOnSync()
                        gestureState.endRefresh()
                    }
                    else -> gestureState.endRefresh()
                }
            }
    }

    Box(
        modifier = modifier.then(
            if (enabled) Modifier.nestedScroll(gestureState.nestedScrollConnection) else Modifier,
        ),
    ) {
        content()
        if (enabled && !isSyncing) {
            PullToRefreshContainer(
                state = gestureState,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = SURFACE2,
                contentColor = GOLD,
            )
        }
        if (isSyncing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(40.dp)
                    .background(SURFACE2, CircleShape)
                    .semantics {
                        stateDescription = "sync-operation:${operationId.orEmpty()}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = GOLD,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
