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
import com.qingxu.app.data.local.MIGRATION_3_4
import com.qingxu.app.notification.NotificationDelivery
import com.qingxu.app.notification.NotificationSurface
import com.qingxu.app.notification.NotificationSurfacePreferences
import com.qingxu.app.data.local.initializeNotificationEncryption
import com.qingxu.app.data.local.compactNotificationStorage
import com.qingxu.app.core.decision.LocalClassifier
import com.qingxu.app.core.decision.CloudDecisionPolicy
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.notification.NotificationConnectionRecovery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QingxuApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    val container by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        NotificationConnectionRecovery.install(this)
        AnalysisScheduler.installRepair(this)
        AnalysisScheduler.enqueue(this)
        container.surface.refresh()
    }
}

class AppContainer(application: Application) {
    private val storagePrefs = application.getSharedPreferences("notification_storage", android.content.Context.MODE_PRIVATE)
    private val database = Room.databaseBuilder(
        application, QingxuDatabase::class.java, "qingxu.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    val dao = database.notifications()
    private val storageGate = Mutex()
    private val storageMutationGate = Mutex()
    private var storageReady = false
    suspend fun ensureStorageReady() = withContext(Dispatchers.IO) {
        storageGate.withLock {
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
                if (!storagePrefs.getBoolean("automatic_sorting_v2", false)) {
                    dao.requeueLegacyLocalResults(sources.selected.value.toList())
                    check(storagePrefs.edit().putBoolean("automatic_sorting_v2", true).commit())
                }
                storageReady = true
            }
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
    val delivery = NotificationDelivery(application)
    val surfacePreferences = NotificationSurfacePreferences(application)
    val processor = NotificationProcessor(dao, sources, ai,
        privacySettings = { privacy.state.value }, deviceLocked = { deviceIsPrivate(application) },
        beforeProcessing = { maintainStorage() }) { AnalysisScheduler.enqueue(application) }
    val repository = NotificationRepository(dao, sources, ensureReady = { ensureStorageReady() }, mutationGate = storageMutationGate,
        initialDecision = { entry ->
            val decision = CloudDecisionPolicy.protect(entry,
                LocalClassifier.classify(entry) ?: LocalClassifier.fallback(entry))
            val interests = ai.state.value.interests.split(Regex("[,，;；\\n]"))
                .map { it.trim() }.filter { it.length >= 2 }
            if (decision.bucket == DecisionBucket.HIDDEN && (entry.interested ||
                interests.any { (entry.title + " " + entry.body).contains(it, ignoreCase = true) })) {
                decision.copy(bucket = DecisionBucket.LATER, reason = "命中你的关注内容，保留稍后查看", source = "preference")
            } else decision
        },
        acceptCapture = { it.postedAt > privacy.state.value.deletedBefore &&
            (privacy.state.value.retentionDays == 0 || it.postedAt >= System.currentTimeMillis() - privacy.state.value.retentionDays * 86_400_000L) },
        onPersisted = { delivery.recordPersisted(it.postedAt) }) {
        surface.refresh()
        AnalysisScheduler.enqueue(application)
    }
    val surface = NotificationSurface(application, dao, surfacePreferences, processor.gate,
        selected = { sources.selected.value }, ensureReady = { ensureStorageReady() })
}
