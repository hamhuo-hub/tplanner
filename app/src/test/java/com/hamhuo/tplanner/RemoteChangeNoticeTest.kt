package com.hamhuo.tplanner

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteChangeNoticeTest {
    @Test
    fun parsesRevisionAndDeduplicatesDatasets() {
        val notice = RemoteChangeNotice.fromJson(
            """{"revision":42,"datasets":["events","journals","events",""]}""",
        )

        assertEquals(42L, notice.revision)
        assertEquals(setOf("events", "journals"), notice.datasets)
    }

    @Test
    fun missingDatasetsProducesEmptyNotice() {
        val notice = RemoteChangeNotice.fromJson("""{"revision":7}""")

        assertEquals(7L, notice.revision)
        assertEquals(emptySet<String>(), notice.datasets)
    }
}
