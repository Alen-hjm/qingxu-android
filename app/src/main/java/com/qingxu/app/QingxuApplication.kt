package com.qingxu.app

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import com.qingxu.app.data.local.QingxuDatabase
import com.qingxu.app.data.repository.NotificationRepository
import com.qingxu.app.data.repository.OnboardingPreferences
import com.qingxu.app.data.repository.SourcePreferences
import com.qingxu.app.data.local.MIGRATION_1_2
import com.qingxu.app.core.ai.AiPreferences
import com.qingxu.app.core.processing.AnalysisScheduler
import com.qingxu.app.core.processing.NotificationProcessor
import com.qingxu.app.core.privacy.PrivacyPreferences
import com.qingxu.app.core.privacy.ContentSession
import com.qingxu.app.core.privacy.deviceIsPrivate
import com.qingxu.app.data.local.MIGRATION_2_3
import com.qingxu.app.data.local.initializeNotificationEncryption
import com.qingxu.app.data.local.compactNotificationStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class QingxuApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    val container by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        AnalysisScheduler.installRepair(this)
        AnalysisScheduler.enqueue(this)
    }
}

class AppContainer(application: Application) {
    private val storagePrefs = application.getSharedPreferences("notification_storage", android.content.Context.MODE_PRIVATE)
    private val database = Room.databaseBuilder(
        application, QingxuDatabase::class.java, "qingxu.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    val dao = database.notifications()
    private val storageGate = Mutex()
    private val storageMutationGate = Mutex()
    private var storageReady = false
    suspend fun ensureStorageReady() = storageGate.withLock {
        if (!storageReady) {
            // Replay the committed deletion marker before any row can be observed or uploaded.
            if (privacy.state.value.deletedBefore > 0) {
                if (privacy.state.value.deletedBefore > storagePrefs.getLong("deletion_cutoff_compacted", 0)) {
                    check(storagePrefs.edit().putBoolean("deletion_compaction_pending", true).commit())
                }
                dao.deleteNotificationsPostedAtOrBefore(privacy.state.value.deletedBefore)
            }
            database.initializeNotificationEncryption()
            if (!storagePrefs.getBoolean("v3_pages_compacted", false)) {
                database.compactNotificationStorage()
                check(storagePrefs.edit().putBoolean("v3_pages_compacted", true).commit())
            }
            if (storagePrefs.getBoolean("deletion_compaction_pending", false)) {
                database.compactNotificationStorage()
                check(storagePrefs.edit().putBoolean("deletion_compaction_pending", false)
                    .putLong("deletion_cutoff_compacted", privacy.state.value.deletedBefore).commit())
            }
            storageReady = true
        }
    }
    suspend fun maintainStorage() {
        ensureStorageReady()
        storageMutationGate.withLock {
            val days = privacy.state.value.retentionDays
            if (days > 0) dao.deleteNotificationsBefore(System.currentTimeMillis() - days * 86_400_000L)
        }
    }
    suspend fun clearHistory() {
        ensureStorageReady()
        storageMutationGate.withLock {
            privacy.markDeleted(System.currentTimeMillis())
            check(storagePrefs.edit().putBoolean("deletion_compaction_pending", true).commit())
            dao.deleteNotificationsPostedAtOrBefore(privacy.state.value.deletedBefore)
            database.compactNotificationStorage()
            check(storagePrefs.edit().putBoolean("deletion_compaction_pending", false)
                .putLong("deletion_cutoff_compacted", privacy.state.value.deletedBefore).commit())
        }
    }
    val sources = SourcePreferences(application)
    val onboarding = OnboardingPreferences(application)
    val ai = AiPreferences(application)
    val privacy = PrivacyPreferences(application)
    val contentSession = ContentSession()
    val processor = NotificationProcessor(dao, sources, ai,
        privacySettings = { privacy.state.value }, deviceLocked = { deviceIsPrivate(application) },
        beforeProcessing = { maintainStorage() }) { AnalysisScheduler.enqueue(application) }
    val repository = NotificationRepository(dao, sources, ensureReady = { ensureStorageReady() }, mutationGate = storageMutationGate,
        acceptCapture = { it.postedAt > privacy.state.value.deletedBefore &&
            (privacy.state.value.retentionDays == 0 || it.postedAt >= System.currentTimeMillis() - privacy.state.value.retentionDays * 86_400_000L) }) {
        AnalysisScheduler.enqueue(application)
    }
}
