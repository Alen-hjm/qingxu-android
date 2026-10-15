package com.qingxu.app.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qingxu.app.core.privacy.NotificationCipher
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationPerformanceTest {
    private lateinit var database: QingxuDatabase
    private lateinit var dao: NotificationDao
    private val encryptions = AtomicInteger()
    private val decryptions = AtomicInteger()
    private val incoming = CapturedNotification("key", "example.app", "示例", "提醒", "正文", null, 1L, 2L, "hash-1")

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), QingxuDatabase::class.java).build()
        val cipher = testNotificationCipher()
        dao = database.notifications().apply {
            configureCipher(object : NotificationCipher {
                override fun encrypt(plaintext: String, associatedData: String): String {
                    encryptions.incrementAndGet()
                    return cipher.encrypt(plaintext, associatedData)
                }
                override fun decrypt(ciphertext: String, associatedData: String): String {
                    decryptions.incrementAndGet()
                    return cipher.decrypt(ciphertext, associatedData)
                }
            })
        }
    }
    @After fun close() { database.close() }

    private fun contentCiphertext(): List<String> = database.openHelper.writableDatabase.query(
        "SELECT appLabel, title, body FROM notifications WHERE notificationKey = 'key'",
    ).use { cursor -> check(cursor.moveToFirst()); (0..2).map(cursor::getString) }

    @Test fun replayedSnapshotDoesNotRewriteCiphertextOrRefreshRetentionAge() = runBlocking {
        assertTrue(dao.captureIfChanged(incoming))
        val ciphertext = contentCiphertext()
        val writes = encryptions.get()
        assertFalse(dao.captureIfChanged(incoming.copy(capturedAt = 900L)))
        assertEquals(writes, encryptions.get())
        assertEquals(ciphertext, contentCiphertext())
        assertEquals(2L, dao.find("key")!!.updatedAt)
        // The same content posted again, or restored after removal, is still a real update.
        assertTrue(dao.captureIfChanged(incoming.copy(postedAt = 100L, capturedAt = 101L)))
        dao.markRemoved("key", 102L)
        assertTrue(dao.captureIfChanged(incoming.copy(postedAt = 100L, capturedAt = 103L)))
        assertNull(dao.find("key")!!.removedAt)
        // Do not rely on contentHash alone if a caller supplied an inconsistent snapshot.
        assertTrue(dao.captureIfChanged(incoming.copy(postedAt = 100L, body = "更新正文")))
    }

    @Test fun metadataOnlyUpdatesReusePlaintextAndReasonUpdateDecryptsOnlyThatField() = runBlocking {
        withTimeout(10_000) {
            dao.capture(incoming)
            val emissions = Channel<List<NotificationEntry>>(Channel.UNLIMITED)
            val job = launch(Dispatchers.IO) { dao.observeAll().collect { emissions.send(it) } }
            try {
                assertEquals("正文", emissions.receive().single().body)
                assertEquals(4, decryptions.get())
                dao.setInterested("key", true)
                assertTrue(emissions.receive().single().interested)
                assertEquals(4, decryptions.get())
                dao.finishAnalysis("key", 1, "hash-1", "LATER", "message", "新的解释", .9, "local", "done")
                assertEquals("新的解释", emissions.receive().single().machineReason)
                assertEquals(5, decryptions.get())
                dao.classify("key", UserLabel.IMPORTANT)
                assertEquals(UserLabel.IMPORTANT, emissions.receive().single().userLabel)
                assertEquals(5, decryptions.get())
            } finally { job.cancelAndJoin() }
            // A new collector has no retained plaintext cache from the previous UI session.
            val fresh = Channel<List<NotificationEntry>>(Channel.UNLIMITED)
            val next = launch(Dispatchers.IO) { dao.observeAll().collect { fresh.send(it) } }
            try {
                assertEquals(1, fresh.receive().size)
                assertEquals(9, decryptions.get())
            } finally { next.cancelAndJoin() }
        }
    }

    @Test fun requeueOnlyEncryptsReasonAndKeepsOriginalContentCiphertext() = runBlocking {
        dao.capture(incoming)
        val ciphertext = contentCiphertext()
        val writes = encryptions.get()
        val reads = decryptions.get()
        dao.requeueForPackages(listOf("example.app"))
        assertEquals(writes + 1, encryptions.get())
        assertEquals(reads, decryptions.get())
        assertEquals(ciphertext, contentCiphertext())
        dao.requeueOne("key")
        assertEquals(writes + 2, encryptions.get())
        assertEquals(reads, decryptions.get())
        assertEquals(ciphertext, contentCiphertext())
        assertEquals("关注偏好已更新，等待整理", dao.find("key")!!.machineReason)
    }

    @Test fun summaryCountsMetadataWithoutDecryptingAndPromotionReadIsNarrowed() = runBlocking {
        val now = 1_000_000L
        suspend fun add(key: String, bucket: String, kind: String, posted: Long = now) {
            dao.capture(incoming.copy(notificationKey = key, postedAt = posted))
            dao.finishAnalysis(key, 1, "hash-1", bucket, kind, "解释", .99, "local", "done")
        }
        add("now", "NOW", "transaction")
        add("old", "NOW", "message", now - 40 * 60_000L)
        add("manual", "LATER", "message")
        dao.classify("manual", UserLabel.URGENT)
        add("handled", "NOW", "transaction")
        dao.setHandled("handled", true, 1, "hash-1")
        add("promo", "HIDDEN", "marketing")
        add("removed", "HIDDEN", "marketing")
        dao.markRemoved("removed", now)
        add("interested", "HIDDEN", "marketing")
        dao.setInterested("interested", true)
        val reads = decryptions.get()
        assertEquals(2, dao.prioritySummaryCount(listOf("example.app"), now))
        assertEquals(0, dao.prioritySummaryCount(listOf("other.app"), now))
        assertEquals(reads, decryptions.get())
        assertEquals(listOf("promo"), dao.promotionCandidates(listOf("example.app")).map { it.notificationKey })
        assertEquals(reads + 4, decryptions.get())
    }

    @Test fun assistantCandidatesAreRecentBoundedSelectedAndUnhandled() = runBlocking {
        dao.capture(incoming)
        dao.capture(incoming.copy(notificationKey = "new", postedAt = 10L))
        dao.capture(incoming.copy(notificationKey = "handled", postedAt = 20L))
        dao.setHandled("handled", true, 1, "hash-1")
        dao.capture(incoming.copy(notificationKey = "other", packageName = "other.app", postedAt = 30L))
        assertEquals(listOf("new"), dao.assistantCandidates(listOf("example.app"), 1).map { it.notificationKey })
        assertEquals(listOf("new", "key"), dao.assistantCandidates(listOf("example.app")).map { it.notificationKey })
        assertTrue(dao.assistantCandidates(emptyList()).isEmpty())
    }

    @Test fun assistantEditsSkipStaleRowsAndUndoOnlyAnUnchangedAppliedState() = runBlocking {
        dao.capture(incoming)
        dao.capture(incoming.copy(notificationKey = "stale"))
        val current = dao.find("key")!!
        val stale = dao.find("stale")!!
        val after = current.copy(userLabel = UserLabel.IMPORTANT_URGENT, previousLabel = current.userLabel,
            labelSource = "manual", manualVersion = current.contentVersion, analysisStatus = "manual")
        dao.setInterested("stale", true)
        val writes = encryptions.get()
        assertEquals(1, dao.applyAssistantEdits(listOf(AssistantEntryEdit(current, after),
            AssistantEntryEdit(stale, stale.copy(handledAt = 4L)))))
        assertEquals(writes, encryptions.get())
        assertEquals(after, dao.find("key"))
        assertNull(dao.find("stale")!!.handledAt)
        assertEquals(1, dao.applyAssistantEdits(listOf(AssistantEntryEdit(after, current))))
        assertEquals(current, dao.find("key"))
        assertEquals(1, dao.applyAssistantEdits(listOf(AssistantEntryEdit(current, after))))
        dao.capture(incoming.copy(body = "新消息", contentHash = "hash-2"))
        assertEquals(0, dao.applyAssistantEdits(listOf(AssistantEntryEdit(after, current))))
        assertEquals("新消息", dao.find("key")!!.body)
    }

    @Test fun assistantCannotOverwriteContentOrDecisionFieldsThroughItsAfterSnapshot() = runBlocking {
        dao.capture(incoming)
        val before = dao.find("key")!!
        assertEquals(1, dao.applyAssistantEdits(listOf(AssistantEntryEdit(before, before.copy(
            body = "替换正文", contentHash = "wrong", machineBucket = "NOW", handledAt = 4L,
        )))))
        assertEquals(before.copy(handledAt = 4L), dao.find("key"))
        assertEquals(0, dao.applyAssistantEdits(listOf(AssistantEntryEdit(before.copy(handledAt = 4L),
            before.copy(notificationKey = "other")))))
    }
}
