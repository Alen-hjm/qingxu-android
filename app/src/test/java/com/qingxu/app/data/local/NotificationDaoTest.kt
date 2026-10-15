package com.qingxu.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.Decision
import com.qingxu.app.core.decision.effectiveBucket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationDaoTest {
    private lateinit var database: QingxuDatabase
    private lateinit var dao: NotificationDao
    private val incoming = CapturedNotification(
        "key", "example.app", "示例", "提醒", "正文", "channel", 1L, 2L, "hash-1",
    )
    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), QingxuDatabase::class.java).build()
        dao = database.notifications()
        dao.configureCipher(testNotificationCipher())
    }
    @After fun tearDown() { database.close() }

    @Test fun filteringTwiceAndDuplicateCallbackStillRestoresOriginalLabel() = runBlocking {
        dao.capture(incoming)
        dao.classify("key", UserLabel.IMPORTANT_URGENT)
        dao.setInterested("key", true)
        dao.classify("key", UserLabel.FILTERED)
        dao.classify("key", UserLabel.FILTERED)
        dao.capture(incoming.copy(capturedAt = 3L))
        assertEquals(UserLabel.FILTERED, dao.find("key")!!.userLabel)
        dao.restore("key")
        val restored = dao.find("key")!!
        assertEquals(UserLabel.IMPORTANT_URGENT, restored.userLabel)
        assertEquals("正文", restored.body)
        assertEquals(1, restored.contentVersion)
        assertTrue(restored.interested)
    }

    @Test fun restoringUnfilteredRecordDoesNotReplaceItsLabel() = runBlocking {
        dao.capture(incoming)
        dao.classify("key", UserLabel.URGENT)
        dao.restore("key")
        assertEquals(UserLabel.URGENT, dao.find("key")!!.userLabel)
    }

    private suspend fun finish(version: Int = 1, hash: String = "hash-1", bucket: String = "HIDDEN") =
        dao.finishAnalysis("key", version, hash, bucket, "marketing", "识别到促销邀请", 0.96, "local", "done")

    @Test fun staleAnalysisCannotHideChangedContent() = runBlocking {
        dao.capture(incoming)
        dao.capture(incoming.copy(body = "退款失败", contentHash = "hash-2"))
        assertEquals(0, finish())
        assertEquals(DecisionBucket.REVIEW, dao.find("key")!!.effectiveBucket())
        assertEquals(1, finish(2, "hash-2", "NOW"))
        assertEquals(DecisionBucket.NOW, dao.find("key")!!.effectiveBucket())
    }

    @Test fun tappingStaleCardCannotApplyCorrectionToNewContent() = runBlocking {
        dao.capture(incoming)
        dao.capture(incoming.copy(body = "退款失败", contentHash = "hash-2"))
        assertEquals(0, dao.classify("key", UserLabel.FILTERED, 1, "hash-1"))
        assertEquals(DecisionBucket.REVIEW, dao.find("key")!!.effectiveBucket())
        assertEquals(1, dao.classify("key", UserLabel.FILTERED, 2, "hash-2"))
        assertEquals(0, dao.restore("key", 1, "hash-1"))
        assertEquals(DecisionBucket.HIDDEN, dao.find("key")!!.effectiveBucket())
        assertEquals(1, dao.restore("key", 2, "hash-2"))
        assertEquals(DecisionBucket.REVIEW, dao.find("key")!!.effectiveBucket())
    }

    @Test fun userCorrectionWinsAgainstInFlightAnalysis() = runBlocking {
        dao.capture(incoming)
        dao.classify("key", UserLabel.IMPORTANT)
        assertEquals(0, finish())
        assertEquals(DecisionBucket.LATER, dao.find("key")!!.effectiveBucket())
        assertEquals(0, dao.pendingCount())
    }

    @Test fun secondResultForSameVersionCannotReplaceCompletedResult() = runBlocking {
        dao.capture(incoming)
        assertEquals(1, finish(bucket = "LATER"))
        assertEquals(0, finish(bucket = "HIDDEN"))
        assertEquals(DecisionBucket.LATER, dao.find("key")!!.effectiveBucket())
    }

    @Test fun newContentExpiresOnlySingleMessageCorrectionAndKeepsInterest() = runBlocking {
        dao.capture(incoming)
        dao.classify("key", UserLabel.FILTERED)
        dao.setInterested("key", true)
        dao.capture(incoming.copy(body = "退款失败", contentHash = "hash-2"))
        val changed = dao.find("key")!!
        assertEquals(DecisionBucket.REVIEW, changed.effectiveBucket())
        assertEquals(UserLabel.UNSORTED, changed.userLabel)
        assertEquals(1, dao.pendingCount())
        assertTrue(changed.interested)
    }

    @Test fun restoringMachineHiddenMakesCurrentMessageVisible() = runBlocking {
        dao.capture(incoming)
        finish()
        dao.restore("key")
        val restored = dao.find("key")!!
        assertEquals(DecisionBucket.LATER, restored.effectiveBucket())
        assertEquals("manual", restored.labelSource)
        assertEquals(1, restored.manualVersion)
    }

    @Test fun manualFilterThenRestoreKeepsPreviousMachinePlacement() = runBlocking {
        dao.capture(incoming)
        finish(bucket = "NOW")
        dao.classify("key", UserLabel.FILTERED)
        dao.classify("key", UserLabel.FILTERED)
        dao.restore("key")
        assertEquals(DecisionBucket.NOW, dao.find("key")!!.effectiveBucket())
    }

    @Test fun changingPreferenceRevealsOldMachineHiddenButPreservesManualHidden() = runBlocking {
        dao.capture(incoming)
        finish()
        dao.requeueForPackages(listOf("example.app"))
        assertEquals(DecisionBucket.REVIEW, dao.find("key")!!.effectiveBucket())
        assertEquals(1, dao.pendingCount())
        dao.classify("key", UserLabel.FILTERED)
        dao.requeueForPackages(listOf("example.app"))
        assertEquals(DecisionBucket.HIDDEN, dao.find("key")!!.effectiveBucket())
        assertEquals(0, dao.pendingCount())
    }

    @Test fun rulesAreScopedByApplicationAndContentKind() = runBlocking {
        dao.saveRule(SourceKindRule("shop.a", "marketing", true))
        dao.saveRule(SourceKindRule("shop.a", "delivery", false))
        dao.saveRule(SourceKindRule("shop.b", "marketing", false))
        dao.saveRule(SourceKindRule("shop.a", "marketing", false))
        assertEquals(3, dao.rules().size)
        assertEquals(false, dao.rules().first { it.packageName == "shop.a" && it.kind == "marketing" }.hidden)
        dao.deleteRule("shop.a", "marketing")
        assertEquals(2, dao.rules().size)
    }

    @Test fun retryableAnalysisRemainsInQueueAndCanFinish() = runBlocking {
        dao.capture(incoming)
        dao.finishAnalysis("key", 1, "hash-1", "REVIEW", "unknown", "稍后重试", 0.0, "", "retry")
        assertEquals(1, dao.pendingCount())
        assertEquals("retry", dao.pending().single().analysisStatus)
        assertEquals(1, finish())
        assertEquals(0, dao.pendingCount())
    }

    @Test fun capturePublishesLocalPlacementBeforeBackgroundAnalysisAndKeepsCorrections() = runBlocking {
        val initial = { _: NotificationEntry -> Decision(DecisionBucket.NOW, "message", "明确急事", .92) }
        dao.capture(incoming, initial)
        assertEquals(DecisionBucket.NOW, dao.find("key")!!.effectiveBucket())
        assertEquals("pending", dao.find("key")!!.analysisStatus)
        assertEquals("local", dao.find("key")!!.analysisSource)
        // Background analysis still owns the final machine result; duplicate snapshots don't reset it.
        finish(bucket = "LATER")
        dao.capture(incoming, initial)
        assertEquals(DecisionBucket.LATER, dao.find("key")!!.effectiveBucket())
        dao.classify("key", UserLabel.FILTERED)
        dao.capture(incoming, initial)
        assertEquals(DecisionBucket.HIDDEN, dao.find("key")!!.effectiveBucket())
        // A new message reusing the notification key expires only the correction for the old text.
        dao.capture(incoming.copy(body = "请马上回复", contentHash = "hash-2"), initial)
        assertEquals(DecisionBucket.NOW, dao.find("key")!!.effectiveBucket())
        assertEquals(2, dao.find("key")!!.contentVersion)
    }

    @Test fun freshNotificationsAreIndependentOfRetryBacklogAndSecondRetryCanFinish() = runBlocking {
        dao.capture(incoming)
        dao.finishAnalysis("key", 1, "hash-1", "LATER", "unknown", "本机已整理", .5, "local", "retry2")
        dao.capture(incoming.copy(notificationKey = "fresh", capturedAt = 3L))
        assertEquals(listOf("fresh", "key"), dao.pending().map { it.notificationKey })
        assertEquals(listOf("fresh"), dao.pending(includeRetry = false).map { it.notificationKey })
        assertEquals(listOf("key"), dao.pending(onlyRetry = true).map { it.notificationKey })
        assertEquals(2, dao.pendingCount())
        assertEquals(1, dao.retryCount())
        assertEquals(1, finish(bucket = "NOW"))
        assertEquals(0, dao.retryCount())
    }

    @Test fun upgradingSortingRevisitsOnlyUnresolvedLocalResultsFromSelectedApps() = runBlocking {
        suspend fun add(key: String, source: String = "local", bucket: String = "REVIEW") {
            dao.capture(incoming.copy(notificationKey = key))
            dao.finishAnalysis(key, 1, "hash-1", bucket, "unknown", "此前规则不足", .0, source, "local_only")
        }
        add("local")
        add("cloud", "ai")
        add("manual")
        dao.classify("manual", UserLabel.UNSORTED)
        add("classified", bucket = "LATER")
        dao.capture(incoming.copy(notificationKey = "unselected", packageName = "other.app"))
        dao.finishAnalysis("unselected", 1, "hash-1", "REVIEW", "unknown", "此前规则不足", .0, "local", "local_only")
        assertEquals(1, dao.requeueLegacyLocalResults(listOf("example.app")))
        assertEquals(listOf("local"), dao.pending().map { it.notificationKey })
        assertEquals(DecisionBucket.LATER, dao.find("classified")!!.effectiveBucket())
        assertEquals("manual", dao.find("manual")!!.analysisStatus)
    }

    @Test fun handlingUsesDisplayedContentAndLateAnalysisCannotReplaceHandledMessage() = runBlocking {
        dao.capture(incoming)
        assertEquals(1, dao.setHandled("key", true, 1, "hash-1"))
        val handledAt = dao.find("key")!!.handledAt
        assertNotNull(handledAt)
        assertEquals(0, finish(bucket = "NOW"))
        assertEquals(0, dao.pendingCount())
        assertTrue(dao.pending().isEmpty())
        dao.capture(incoming.copy(capturedAt = 10L))
        assertEquals(handledAt, dao.find("key")!!.handledAt)

        dao.capture(incoming.copy(body = "新消息", contentHash = "hash-2"))
        assertNull(dao.find("key")!!.handledAt)
        assertEquals(0, dao.setHandled("key", true, 1, "hash-1"))
        assertEquals(0, dao.setHandled("key", true, 2, "hash-1"))
        assertEquals(1, dao.pendingCount())
        assertEquals(1, dao.setHandled("key", true, 2, "hash-2"))
        assertEquals(0, dao.setHandled("key", false, 1, "hash-1"))
        assertNotNull(dao.find("key")!!.handledAt)
        assertEquals(1, dao.setHandled("key", false, 2, "hash-2"))
        assertNull(dao.find("key")!!.handledAt)
        assertEquals(1, dao.pendingCount())
    }

    @Test fun handledMessagesStayOutOfEveryAutomaticRequeueAndRetry() = runBlocking {
        suspend fun add(key: String, status: String) {
            dao.capture(incoming.copy(notificationKey = key))
            dao.finishAnalysis(key, 1, "hash-1", "REVIEW", "unknown", "本机结果", .0, "local", status)
            dao.setHandled(key, true, 1, "hash-1")
        }
        add("retry", "retry2")
        add("paused", "privacy_waiting")
        add("legacy", "local_only")
        assertEquals(0, dao.pendingCount())
        assertEquals(0, dao.retryCount())
        assertTrue(dao.pending(onlyRetry = true).isEmpty())
        assertEquals(0, dao.requeueCloudPaused())
        assertEquals(0, dao.requeueLegacyLocalResults(listOf("example.app")))
        dao.requeueForPackages(listOf("example.app"))
        dao.requeueOne("legacy")
        assertEquals("retry2", dao.find("retry")!!.analysisStatus)
        assertEquals("privacy_waiting", dao.find("paused")!!.analysisStatus)
        assertEquals("local_only", dao.find("legacy")!!.analysisStatus)
        assertEquals(0, dao.pendingCount())
    }

    @Test fun conversationPriorityStoresOnlyScopedDigestsAndDoesNotSpreadAcrossApps() = runBlocking {
        val chat = NotificationEntry("chat", "com.tencent.mm", "微信", "家人", "今天回家吃饭吗", null, 1, 1, "chat-hash")
        val scope = SenderPreferencePolicy.senderScope(chat)!!
        val qqScope = SenderPreferencePolicy.senderScope(chat.copy(packageName = "com.tencent.mobileqq", appLabel = "QQ"))!!
        dao.savePrioritySender(SenderPriorityRule(chat.packageName, scope))
        dao.savePrioritySender(SenderPriorityRule(chat.packageName, scope))
        dao.savePrioritySender(SenderPriorityRule("com.tencent.mobileqq", qqScope))
        assertEquals(2, dao.prioritySenders().size)
        database.openHelper.writableDatabase.query("SELECT packageName, scopeHash FROM sender_priority_rules").use {
            while (it.moveToNext()) {
                assertEquals(64, it.getString(1).length)
                assertTrue(!it.getString(1).contains("家人"))
            }
        }
        dao.deletePrioritySender(chat.packageName, scope)
        assertEquals(listOf(SenderPriorityRule("com.tencent.mobileqq", qqScope)), dao.prioritySenders())
    }
}
