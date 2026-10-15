package com.qingxu.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMergeTest {
    private fun capture(hash: String = "hash-1", body: String = "会议将在十分钟后开始") = CapturedNotification(
        notificationKey = "key-1", packageName = "example.calendar", appLabel = "日历",
        title = "会议提醒", body = body, channelId = "reminders",
        postedAt = 100L, capturedAt = 110L, contentHash = hash,
    )

    @Test fun newNotificationStartsUnclassified() {
        val entry = mergeNotification(null, capture())
        assertEquals(UserLabel.UNSORTED, entry.userLabel)
        assertEquals(1, entry.contentVersion)
        assertEquals("unclassified", entry.labelSource)
    }

    @Test fun duplicateCallbackDoesNotIncrementContentVersion() {
        val existing = mergeNotification(null, capture())
        val result = mergeNotification(existing, capture().copy(capturedAt = 200L))
        assertEquals(existing.notificationKey, result.notificationKey)
        assertEquals(1, result.contentVersion)
        assertEquals(200L, result.updatedAt)
    }

    @Test fun changedTextPreservesManualClassificationAndInterest() {
        val existing = mergeNotification(null, capture()).copy(
            userLabel = UserLabel.IMPORTANT_URGENT, labelSource = "manual", interested = true,
        )
        val result = mergeNotification(existing, capture("hash-2", "会议改期了"))
        assertEquals("会议改期了", result.body)
        assertEquals(2, result.contentVersion)
        assertEquals(UserLabel.IMPORTANT_URGENT, result.userLabel)
        assertEquals("manual", result.labelSource)
        assertTrue(result.interested)
    }

    @Test fun filteredNotificationStaysFilteredAcrossUpdates() {
        val existing = mergeNotification(null, capture()).copy(
            userLabel = UserLabel.FILTERED, previousLabel = UserLabel.IMPORTANT, labelSource = "manual",
        )
        val result = mergeNotification(existing, capture("hash-2", "已更新"))
        assertEquals(UserLabel.FILTERED, result.userLabel)
        assertEquals(UserLabel.IMPORTANT, result.previousLabel)
    }

    @Test fun repostMakesNotificationActiveAgainWithoutResettingDecisions() {
        val existing = mergeNotification(null, capture()).copy(removedAt = 120L, userLabel = UserLabel.URGENT)
        val result = mergeNotification(existing, capture().copy(postedAt = 300L))
        assertNull(result.removedAt)
        assertEquals(UserLabel.URGENT, result.userLabel)
        assertEquals(300L, result.postedAt)
    }

    @Test fun changedContentClearsOldMachineHiddenBeforeAnalysis() {
        val existing = mergeNotification(null, capture()).copy(
            machineBucket = "HIDDEN", machineKind = "marketing", machineReason = "促销",
            machineConfidence = 0.99, analysisStatus = "done", analysisSource = "ai",
        )
        val result = mergeNotification(existing, capture("hash-2", "退款失败"))
        assertEquals("REVIEW", result.machineBucket)
        assertEquals("pending", result.analysisStatus)
        assertEquals("unknown", result.machineKind)
        assertEquals("", result.analysisSource)
    }

    @Test fun duplicateCallbackKeepsCompletedMachineDecision() {
        val existing = mergeNotification(null, capture()).copy(machineBucket = "NOW", analysisStatus = "done")
        val result = mergeNotification(existing, capture())
        assertEquals("NOW", result.machineBucket)
        assertEquals("done", result.analysisStatus)
    }

    @Test fun duplicateSnapshotPreservesCompletionButChangedContentReturnsToInbox() {
        val handled = mergeNotification(null, capture()).copy(handledAt = 200L)
        assertEquals(200L, mergeNotification(handled, capture().copy(capturedAt = 300L)).handledAt)
        val changed = mergeNotification(handled, capture("hash-2", "新的待办事项"))
        assertNull(changed.handledAt)
        assertEquals(2, changed.contentVersion)
        assertEquals("pending", changed.analysisStatus)
    }
}
