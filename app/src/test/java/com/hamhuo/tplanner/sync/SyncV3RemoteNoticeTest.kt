package com.hamhuo.tplanner

import com.hamhuo.tplanner.syncv3.SyncV3NotificationClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncV3RemoteNoticeTest {
    @Test
    fun `stale notices are dropped when the version was already installed`() {
        // Worker 已抢先安装 342:长轮询(以 341 发出)带回的 342 是过期通知。
        assertFalse(shouldHandleNotice(SyncV3NotificationClient.NotificationResult(true, 342), 342))
        assertFalse(shouldHandleNotice(SyncV3NotificationClient.NotificationResult(true, 342), 343))
        assertTrue(shouldHandleNotice(SyncV3NotificationClient.NotificationResult(true, 342), 341))
    }

    @Test
    fun `no-version notices are never handled`() {
        assertFalse(shouldHandleNotice(SyncV3NotificationClient.NotificationResult(false, 342), 341))
    }
}
