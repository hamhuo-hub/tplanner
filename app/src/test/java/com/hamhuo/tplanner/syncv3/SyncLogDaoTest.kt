package com.hamhuo.tplanner.syncv3

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncLogDaoTest {
    private lateinit var db: TPlannerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TPlannerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun entry(createdAt: Long) = SyncLogEntity(
        createdAt = createdAt,
        level = "info",
        source = "test",
        message = "message-$createdAt",
    )

    @Test
    fun `append trims to the retention window with newest first`() {
        val dao = db.syncV3Dao()
        for (i in 1L..10L) {
            dao.appendLog(entry(i), keep = 5)
        }
        assertEquals(5, dao.logCount())
        val recent = dao.recentLogs(5)
        assertEquals(10L, recent.first().createdAt)
        assertEquals(6L, recent.last().createdAt)
    }

    @Test
    fun `clearLogs empties the table`() {
        val dao = db.syncV3Dao()
        dao.appendLog(entry(1L), keep = 5)
        dao.appendLog(entry(2L), keep = 5)
        assertEquals(2, dao.logCount())
        dao.clearLogs()
        assertEquals(0, dao.logCount())
        assertEquals(emptyList<SyncLogEntity>(), dao.recentLogs(10))
    }
}
