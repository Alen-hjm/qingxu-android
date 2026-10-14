package com.qingxu.app.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qingxu.app.core.privacy.NotificationCipher
import com.qingxu.app.core.privacy.NotificationEncryptionException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EncryptedStorageTest {
    private lateinit var db: QingxuDatabase
    private lateinit var dao: NotificationDao
    private val incoming = CapturedNotification("key", "example.app", "私密应用名称", "私密标题", "私密正文",
        "channel", 1L, 2L, "sha256-content-hash")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), QingxuDatabase::class.java).build()
        dao = db.notifications().apply { configureCipher(testNotificationCipher()) }
    }
    @After fun close() { db.close() }

    private fun rawFields(): List<String> = db.openHelper.writableDatabase.query(
        "SELECT appLabel, title, body, machineReason FROM notifications WHERE notificationKey = 'key'",
    ).use { cursor ->
        check(cursor.moveToFirst())
        (0..3).map(cursor::getString)
    }

    @Test fun captureAndEveryReasonWritePersistOnlyCiphertext() = runBlocking {
        dao.capture(incoming)
        assertEquals(incoming.body, dao.find("key")!!.body)
        assertTrue(rawFields().all { it.startsWith("qx1:") })
        assertFalse(rawFields().any { it.contains("私密") })
        dao.finishAnalysis("key", 1, incoming.contentHash, "HIDDEN", "marketing", "私密决策理由", 0.99, "ai", "done")
        assertEquals("私密决策理由", dao.find("key")!!.machineReason)
        assertTrue(rawFields().all { it.startsWith("qx1:") })
        dao.requeueForPackages(listOf("example.app"))
        assertEquals("偏好已更新，等待重新整理", dao.find("key")!!.machineReason)
        assertTrue(rawFields().all { it.startsWith("qx1:") })
        dao.requeueOne("key")
        assertEquals("关注偏好已更新，等待整理", dao.find("key")!!.machineReason)
        assertTrue(rawFields().all { it.startsWith("qx1:") })
    }

    @Test fun legacyPlaintextBecomesEncryptedAndRepeatedMigrationIsIdempotent() = runBlocking {
        dao.capture(incoming)
        db.openHelper.writableDatabase.execSQL("""UPDATE notifications SET appLabel = '旧应用',
            title = '旧标题', body = '旧正文', machineReason = '旧理由', storageEncryptionVersion = 0""")
        assertEquals("旧正文", dao.find("key")!!.body)
        assertEquals(1, dao.encryptLegacyRecords())
        assertEquals("旧正文", dao.find("key")!!.body)
        assertEquals(0, dao.encryptLegacyRecords())
        assertTrue(rawFields().all { it.startsWith("qx1:") })
    }

    @Test fun unavailableKeyCannotStorePlaintext() {
        dao.configureCipher(object : NotificationCipher {
            override fun encrypt(plaintext: String, associatedData: String): String = throw NotificationEncryptionException()
            override fun decrypt(ciphertext: String, associatedData: String): String = throw NotificationEncryptionException()
        })
        assertThrows(NotificationEncryptionException::class.java) { runBlocking { dao.capture(incoming) } }
        db.openHelper.writableDatabase.query("SELECT count(*) FROM notifications").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test fun encryptionFailureRollsBackLegacyMigrationWithoutLosingRecords() = runBlocking {
        dao.capture(incoming)
        dao.capture(incoming.copy(notificationKey = "second"))
        db.openHelper.writableDatabase.execSQL("""UPDATE notifications SET appLabel = '旧应用',
            title = '旧标题', body = '旧正文', machineReason = '旧理由', storageEncryptionVersion = 0""")
        val working = testNotificationCipher()
        var writes = 0
        dao.configureCipher(object : NotificationCipher {
            override fun encrypt(plaintext: String, associatedData: String): String {
                if (++writes > 4) throw NotificationEncryptionException()
                return working.encrypt(plaintext, associatedData)
            }
            override fun decrypt(ciphertext: String, associatedData: String) = working.decrypt(ciphertext, associatedData)
        })
        try {
            dao.encryptLegacyRecords()
            throw AssertionError("Expected encryption failure")
        } catch (_: NotificationEncryptionException) { }
        db.openHelper.writableDatabase.query("SELECT count(*) FROM notifications WHERE storageEncryptionVersion = 0").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }
        dao.configureCipher(working)
        assertEquals(2, dao.encryptLegacyRecords())
        assertEquals("旧正文", dao.find("key")!!.body)
    }

    @Test fun retentionAndClearDeleteNotificationsButKeepPreferences() = runBlocking {
        dao.capture(incoming)
        dao.capture(incoming.copy(notificationKey = "new", capturedAt = 200L))
        dao.saveRule(SourceKindRule("example.app", "marketing", true))
        assertEquals(1, dao.deleteNotificationsBefore(100L))
        assertEquals("new", dao.pending().single().notificationKey)
        assertEquals(1, dao.deleteAllNotifications())
        assertEquals(0, dao.pendingCount())
        assertEquals(1, dao.rules().size)
    }

    @Test fun privacyPausedAnalysisResumesWithoutChangingEncryptedReason() = runBlocking {
        dao.capture(incoming)
        dao.finishAnalysis("key", 1, incoming.contentHash, "REVIEW", "unknown", "应用锁定中", 0.0, "", "privacy_waiting")
        val before = rawFields()
        assertEquals(0, dao.pendingCount())
        assertEquals(1, dao.requeueCloudPaused())
        assertEquals(1, dao.pendingCount())
        assertEquals(before, rawFields())
        assertEquals("应用锁定中", dao.find("key")!!.machineReason)
    }

    @Test fun persistedDeletionCutoffUsesPostedTimeAndKeepsNewNotifications() = runBlocking {
        // A reconnect refreshes updatedAt, but must not revive pre-deletion content.
        dao.capture(incoming.copy(notificationKey = "old-refreshed", postedAt = 99L, capturedAt = 500L))
        dao.capture(incoming.copy(notificationKey = "at-cutoff", postedAt = 100L, capturedAt = 700L))
        dao.capture(incoming.copy(notificationKey = "after-cutoff", postedAt = 101L, capturedAt = 101L))
        dao.saveRule(SourceKindRule("example.app", "marketing", true))
        assertEquals(2, dao.deleteNotificationsPostedAtOrBefore(100L))
        assertEquals(listOf("after-cutoff"), dao.pending().map { it.notificationKey })
        assertEquals(0, dao.deleteNotificationsPostedAtOrBefore(100L))
        assertEquals(1, dao.rules().size)
    }

    @Test fun cutoffCanDeleteUpgradeLegacyRowsWithoutDecryptingThem() = runBlocking {
        dao.capture(incoming.copy(notificationKey = "legacy", postedAt = 100L, capturedAt = 900L))
        db.openHelper.writableDatabase.execSQL("""UPDATE notifications SET appLabel = '旧应用',
            title = '旧标题', body = '旧正文', machineReason = '旧理由', storageEncryptionVersion = 0""")
        dao.capture(incoming.copy(notificationKey = "new", postedAt = 101L))
        assertEquals(1, dao.deleteNotificationsPostedAtOrBefore(100L))
        assertEquals(listOf("new"), dao.pending().map { it.notificationKey })
    }
}
