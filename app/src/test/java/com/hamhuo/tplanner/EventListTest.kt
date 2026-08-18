package com.hamhuo.tplanner

import org.junit.Assert.assertEquals
import org.junit.Test

class EventListTest {
    @Test
    fun onlyCustomListsArePersistedOnItems() {
        assertEquals("", EventList.Inbox.assignmentId())
        assertEquals("", EventList.Today.assignmentId())
        assertEquals("list-1", EventList.Custom("list-1", "Work").assignmentId())
    }

    @Test
    fun missingCustomListFallsBackToInbox() {
        assertEquals(EventList.Inbox, EventList.fromKey("missing", emptyList()))
    }
}
