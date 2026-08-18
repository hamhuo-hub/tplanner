package com.hamhuo.tplanner

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalDayRolloverTest {
    private val yesterday = LocalDate.of(2026, 8, 18)
    private val today = LocalDate.of(2026, 8, 19)

    @Test
    fun activeEditorKeepsItsOriginalDate() {
        assertNull(
            planJournalDayRollover(
                displayedDate = yesterday,
                today = today,
                isEditing = true,
                hasDraft = true,
                content = "unfinished",
            )
        )
    }

    @Test
    fun savedNoteAdvancesWithoutCreatingAnotherDraft() {
        assertEquals(
            JournalDayRolloverPlan(yesterday, today, draftContent = null),
            planJournalDayRollover(
                displayedDate = yesterday,
                today = today,
                isEditing = false,
                hasDraft = false,
                content = "already saved",
            )
        )
    }

    @Test
    fun recoveredDraftIsCommittedToThePreviousDateBeforeAdvancing() {
        assertEquals(
            JournalDayRolloverPlan(yesterday, today, draftContent = "unfinished"),
            planJournalDayRollover(
                displayedDate = yesterday,
                today = today,
                isEditing = false,
                hasDraft = true,
                content = "unfinished",
            )
        )
    }

    @Test
    fun currentDayNeverRollsOver() {
        assertNull(
            planJournalDayRollover(
                displayedDate = today,
                today = today,
                isEditing = false,
                hasDraft = true,
                content = "today",
            )
        )
    }
}
