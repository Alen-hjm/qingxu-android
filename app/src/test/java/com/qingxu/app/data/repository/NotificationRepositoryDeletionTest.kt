package com.qingxu.app.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qingxu.app.core.privacy.AesGcmNotificationCipher
import com.qingxu.app.core.privacy.NotificationCipher
import com.qingxu.app.data.local.CapturedNotification
import com.qingxu.app.data.local.NotificationDao
import com.qingxu.app.data.local.QingxuDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationRepositoryDeletionTest {
    private lateinit var database: QingxuDatabase
    private lateinit var dao: NotificationDao
    private lateinit var sources: SourcePreferences
    private val cutoff = AtomicLong(0)
    private val gate = Mutex()
    private val cipher = AesGcmNotificationCipher { SecretKeySpec(ByteArray(32) { 23 }, "AES") }
    private val old = CapturedNotification("old", "example.shop", "商店", "旧标题", "旧正文", null, 99L, 500L, "old-hash")

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("notification_sources", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, QingxuDatabase::class.java).build()
        dao = database.notifications().apply { configureCipher(cipher) }
        sources = SourcePreferences(context).apply { setSelected(old.packageName, true) }
    }

    @After fun close() { database.close() }

    @Test fun waitingCaptureReadsDeletionCutoffOnlyAfterDeletionReleasesSharedGate() = runBlocking {
        withTimeout(10_000) {
            dao.capture(old)
            val ready = CompletableDeferred<Unit>()
            var captured = 0
            val repository = NotificationRepository(dao, sources, ensureReady = { ready.complete(Unit) },
                acceptCapture = { it.postedAt > cutoff.get() }, mutationGate = gate, onCaptured = { captured++ })
            // Deletion owns the same mutex before an already queued capture reaches persistence.
            gate.lock()
            val pendingCapture = async(start = CoroutineStart.UNDISPATCHED) { repository.capture(old) }
            ready.await()
            assertFalse(pendingCapture.isCompleted)
            cutoff.set(100L)
            assertEquals(1, dao.deleteNotificationsPostedAtOrBefore(cutoff.get()))
            gate.unlock()
            pendingCapture.await()
            assertNull(dao.find(old.notificationKey))
            assertEquals(0, captured)

            repository.capture(old.copy(notificationKey = "new", postedAt = 101L, contentHash = "new-hash"))
            assertEquals("旧正文", dao.find("new")!!.body)
            assertEquals(1, captured)
            database.openHelper.writableDatabase.query("SELECT body FROM notifications WHERE notificationKey = 'new'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(0).startsWith("qx1:"))
            }
        }
    }

    @Test fun deletionWaitsForAcceptedCaptureThenRemovesItBeforeAllowingNewCapture() = runBlocking {
        withTimeout(10_000) {
            val encryptionStarted = CountDownLatch(1)
            val releaseEncryption = CountDownLatch(1)
            val pauseOnce = AtomicBoolean(true)
            dao.configureCipher(object : NotificationCipher {
                override fun encrypt(plaintext: String, associatedData: String): String {
                    if (pauseOnce.compareAndSet(true, false)) {
                        encryptionStarted.countDown()
                        check(releaseEncryption.await(5, TimeUnit.SECONDS))
                    }
                    return cipher.encrypt(plaintext, associatedData)
                }
                override fun decrypt(ciphertext: String, associatedData: String) = cipher.decrypt(ciphertext, associatedData)
            })
            val repository = NotificationRepository(dao, sources,
                acceptCapture = { it.postedAt > cutoff.get() }, mutationGate = gate)
            val capture = async(Dispatchers.Default) { repository.capture(old) }
            try {
                assertTrue(encryptionStarted.await(5, TimeUnit.SECONDS))
                val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                    gate.withLock {
                        cutoff.set(100L)
                        dao.deleteNotificationsPostedAtOrBefore(cutoff.get())
                    }
                }
                // Capture has passed the predicate, but deletion cannot overtake its database write.
                assertFalse(deletion.isCompleted)
                releaseEncryption.countDown()
                capture.await()
                assertEquals(1, deletion.await())
                assertNull(dao.find("old"))
                repository.capture(old) // A listener reconnect must not resurrect it either.
                assertNull(dao.find("old"))
                repository.capture(old.copy(notificationKey = "new", postedAt = 101L))
                assertEquals(listOf("new"), dao.pending().map { it.notificationKey })
            } finally {
                releaseEncryption.countDown()
            }
        }
    }
}
