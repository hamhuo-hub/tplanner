package com.hamhuo.tplanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSyncPresentationTimingTest {
    @Test
    fun quickSyncKeepsAnimationVisibleForRemainingTime() {
        assertEquals(2_250L, manualSyncResultDelayMillis(1_000L, 1_750L))
    }

    @Test
    fun slowSyncShowsResultImmediatelyWhenItFinishes() {
        assertEquals(0L, manualSyncResultDelayMillis(1_000L, 4_500L))
    }

    @Test
    fun backwardsClockCannotExtendAnimationPastMinimum() {
        assertEquals(3_000L, manualSyncResultDelayMillis(2_000L, 1_500L))
    }
}
