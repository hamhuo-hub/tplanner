package com.hamhuo.tplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchScheduleRefreshProtocolTest {
    private val requestId = "7f8a9b2c-3d4e-5f6a-7b8c-9d0e1f2a3b4c"
    private val hash = "a".repeat(64)
    private val snapshot = """{"version":42,"hash":"$hash"}"""

    @Test
    fun requestResponseAndReceiptRoundTrip() {
        val request = WatchScheduleRefreshProtocol.Request(requestId, 1_700_000_000_000L)
        assertEquals(
            request,
            WatchScheduleRefreshProtocol.decodeRequest(
                WatchScheduleRefreshProtocol.encodeRequest(request),
            ),
        )

        val response = WatchScheduleRefreshProtocol.Response(
            requestId = requestId,
            snapshot = snapshot,
        )
        assertEquals(
            response,
            WatchScheduleRefreshProtocol.decodeResponse(
                WatchScheduleRefreshProtocol.encodeResponse(response),
            ),
        )

        val receipt = WatchScheduleRefreshProtocol.receiptFor(
            requestId = requestId,
            snapshot = snapshot,
            acceptedAtEpochMs = 1_700_000_000_100L,
        )
        assertEquals(
            receipt,
            WatchScheduleRefreshProtocol.decodeReceipt(
                WatchScheduleRefreshProtocol.encodeReceipt(receipt),
            ),
        )
    }

    @Test
    fun responseAndReceiptPathsAreBoundToTheRequest() {
        assertEquals(
            requestId,
            WatchScheduleRefreshProtocol.requestIdFromPath(
                WatchScheduleRefreshProtocol.responseMessagePath(requestId),
                WatchScheduleRefreshProtocol.RESPONSE_MESSAGE_PATH_PREFIX,
            ),
        )
        assertEquals(
            requestId,
            WatchScheduleRefreshProtocol.requestIdFromPath(
                WatchScheduleRefreshProtocol.receiptMessagePath(requestId),
                WatchScheduleRefreshProtocol.RECEIPT_MESSAGE_PATH_PREFIX,
            ),
        )
        assertNull(
            WatchScheduleRefreshProtocol.requestIdFromPath(
                "/tplanner/schedule/refresh/response/not/one/id",
                WatchScheduleRefreshProtocol.RESPONSE_MESSAGE_PATH_PREFIX,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedSnapshotIdentityCannotBeAcknowledged() {
        WatchScheduleRefreshProtocol.snapshotIdentity(
            """{"version":42,"hash":"not-a-hash"}""",
        )
    }
}
