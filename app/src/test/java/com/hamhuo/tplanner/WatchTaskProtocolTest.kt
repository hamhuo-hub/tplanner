package com.hamhuo.tplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTaskProtocolTest {
    @Test
    fun deleteRequest_usesDeletePathAndParsesRequestId() {
        val request = WatchTaskProtocol.Request(
            requestId = "delete-request-1",
            createdAtEpochMs = 123L,
            taskId = "task-1",
        )

        val path = WatchTaskProtocol.requestPath(request)

        assertEquals("/tplanner/task/delete/delete-request-1", path)
        assertEquals("delete-request-1", WatchTaskProtocol.requestIdFromPath(
            path,
            WatchTaskProtocol.DELETE_REQUEST_PATH_PREFIX,
        ))
        assertTrue(request.isDelete)
        assertEquals("task-1", request.taskId)
    }

    @Test
    fun requestIdFromPath_rejectsNestedOrWrongPaths() {
        assertNull(WatchTaskProtocol.requestIdFromPath(
            "/tplanner/task/delete/a/b",
            WatchTaskProtocol.DELETE_REQUEST_PATH_PREFIX,
        ))
        assertNull(WatchTaskProtocol.requestIdFromPath(
            "/tplanner/task/create/request-1",
            WatchTaskProtocol.DELETE_REQUEST_PATH_PREFIX,
        ))
    }
}
