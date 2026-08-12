package com.hamhuo.tplanner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MainLayout(
    isPhone: Boolean,
    phoneTab: Int,
    onPhoneTabSelected: (Int) -> Unit,
    onListSheetRequest: () -> Unit,
    chromeHidden: Boolean,
    chromeMode: ChromeMode,
    onNavigationRequested: () -> Unit,
    notesCard: @Composable () -> Unit,
    taskCard: @Composable () -> Unit,
    timelineCard: @Composable () -> Unit,
) {
    if (isPhone) {
        Box(Modifier.fillMaxSize().imePadding()) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp)
                    .padding(bottom = 10.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SURFACE),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                val tabStateHolder = rememberSaveableStateHolder()
                tabStateHolder.SaveableStateProvider(phoneTab) {
                    when (phoneTab) {
                        0 -> notesCard()
                        1 -> taskCard()
                        2 -> timelineCard()
                        else -> notesCard()
                    }
                }
            }

            PhoneTabBar(
                selected = phoneTab,
                onSelect = { selected ->
                    if (selected == 1 && phoneTab == 1) onListSheetRequest()
                    onPhoneTabSelected(selected)
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                presentation = when {
                    chromeHidden -> PhoneTabBarPresentation.Hidden
                    chromeMode == ChromeMode.PrimaryNavigation ->
                        PhoneTabBarPresentation.Expanded
                    else -> PhoneTabBarPresentation.HandleOnly
                },
                onExpandRequest = onNavigationRequested,
            )
        }
    } else {
        Box(Modifier.fillMaxSize().padding(10.dp).imePadding()) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(
                    modifier = Modifier.weight(1.618f).fillMaxHeight(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SURFACE),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) { notesCard() }

                Card(
                    modifier = Modifier.weight(1.0f).fillMaxHeight(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SURFACE),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) { taskCard() }
            }
        }
    }
}
