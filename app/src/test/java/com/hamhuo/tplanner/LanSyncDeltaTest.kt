package com.hamhuo.tplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSyncDeltaTest {
    private data class Item(val id: String, val content: String)

    @Test
    fun unchangedSnapshotPullDoesNotUploadAnything() {
        val local = listOf(Item("a", "same"), Item("b", "same-too"))

        val uploadIds = uploadEntityIds(
            local = local,
            baseKeys = mapOf("a" to "same", "b" to "same-too"),
            capturedIds = emptySet(),
            idOf = Item::id,
            contentKeyOf = Item::content,
        )

        assertTrue(uploadIds.isEmpty())
    }

    @Test
    fun uploadsOnlyOutboxAndLocallyDriftedEntities() {
        val local = listOf(
            Item("unchanged", "base"),
            Item("edited", "new"),
            Item("legacy-new", "created"),
        )

        val uploadIds = uploadEntityIds(
            local = local,
            baseKeys = mapOf("unchanged" to "base", "edited" to "old"),
            capturedIds = setOf("outbox-delete"),
            idOf = Item::id,
            contentKeyOf = Item::content,
        )

        assertEquals(setOf("outbox-delete", "edited", "legacy-new"), uploadIds)
    }
}
