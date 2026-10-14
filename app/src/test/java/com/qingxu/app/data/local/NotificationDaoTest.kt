package com.qingxu.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.effectiveBucket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
}
