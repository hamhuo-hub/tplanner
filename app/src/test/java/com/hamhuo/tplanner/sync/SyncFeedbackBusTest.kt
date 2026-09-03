package com.hamhuo.tplanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFeedbackBusTest {
    @Test
    fun `interactive feedback events reach an active collector in order`() = runBlocking {
        val received = mutableListOf<SyncFeedbackEvent>()
        val collector = launch(Dispatchers.Unconfined) {
            SyncFeedbackBus.events.collect { received.add(it) }
        }
        try {
            SyncFeedbackBus.publish(SyncFeedbackEvent.Sending)
            SyncFeedbackBus.publish(SyncFeedbackEvent.CloudAccepted("pi.local"))
            SyncFeedbackBus.publish(SyncFeedbackEvent.FailedLocally("ERROR003"))

            assertEquals(
                listOf(
                    SyncFeedbackEvent.Sending,
                    SyncFeedbackEvent.CloudAccepted("pi.local"),
                    SyncFeedbackEvent.FailedLocally("ERROR003"),
                ),
                received,
            )
        } finally {
            collector.cancelAndJoin()
        }
    }
}
