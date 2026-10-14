package com.qingxu.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import com.qingxu.app.core.privacy.NotificationCipher
import com.qingxu.app.core.privacy.NotificationEncryptionException
import com.qingxu.app.core.privacy.androidNotificationCipher

@Dao
abstract class NotificationDao {
    private var cipher: NotificationCipher = androidNotificationCipher()

    /** Configure before first access. Production uses Android Keystore; tests inject a real AES key. */
    fun configureCipher(value: NotificationCipher) { cipher = value }

    @Query("SELECT * FROM notifications ORDER BY postedAt DESC")
    protected abstract fun observeStored(): Flow<List<NotificationEntry>>

    fun observeAll(): Flow<List<NotificationEntry>> = observeStored()
        .map { entries -> entries.map(::decrypt) }.flowOn(Dispatchers.IO)

    @Query("SELECT * FROM notifications WHERE notificationKey = :key")
    protected abstract suspend fun findStored(key: String): NotificationEntry?

    suspend fun find(key: String): NotificationEntry? = findStored(key)?.let(::decrypt)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun saveStored(entry: NotificationEntry)

    suspend fun save(entry: NotificationEntry) = saveStored(encrypt(entry))

    @Transaction
    open suspend fun capture(incoming: CapturedNotification) {
        save(mergeNotification(find(incoming.notificationKey), incoming))
    }

    @Query("""UPDATE notifications SET
        previousLabel = CASE WHEN :label = 'FILTERED' AND userLabel != 'FILTERED'
            THEN CASE WHEN labelSource = 'manual' THEN userLabel
                WHEN machineBucket = 'NOW' THEN 'IMPORTANT_URGENT'
                WHEN machineBucket IN ('LATER', 'HIDDEN') THEN 'IMPORTANT'
                ELSE userLabel END
            ELSE previousLabel END,
        userLabel = :label, labelSource = 'manual', manualVersion = contentVersion,
        analysisStatus = 'manual'
        WHERE notificationKey = :key
        AND (:expectedVersion IS NULL OR contentVersion = :expectedVersion)
        AND (:expectedHash IS NULL OR contentHash = :expectedHash)""")
    abstract suspend fun classify(key: String, label: UserLabel, expectedVersion: Int? = null, expectedHash: String? = null): Int

    @Query("""UPDATE notifications SET
        userLabel = CASE WHEN userLabel = 'FILTERED' THEN previousLabel ELSE 'IMPORTANT' END,
        labelSource = 'manual', manualVersion = contentVersion, analysisStatus = 'manual'
        WHERE notificationKey = :key AND (userLabel = 'FILTERED'
            OR (labelSource != 'manual' AND machineBucket = 'HIDDEN'))
        AND (:expectedVersion IS NULL OR contentVersion = :expectedVersion)
        AND (:expectedHash IS NULL OR contentHash = :expectedHash)""")
    abstract suspend fun restore(key: String, expectedVersion: Int? = null, expectedHash: String? = null): Int

    @Query("UPDATE notifications SET interested = :value WHERE notificationKey = :key")
    abstract suspend fun setInterested(key: String, value: Boolean)

    @Query("UPDATE notifications SET removedAt = :at WHERE notificationKey = :key")
    abstract suspend fun markRemoved(key: String, at: Long)

    @Query("""SELECT * FROM notifications WHERE analysisStatus IN ('pending', 'retry')
        AND labelSource != 'manual' ORDER BY updatedAt ASC LIMIT :limit""")
    protected abstract suspend fun pendingStored(limit: Int): List<NotificationEntry>

    suspend fun pending(limit: Int = 30): List<NotificationEntry> = pendingStored(limit).map(::decrypt)

    @Query("""SELECT COUNT(*) FROM notifications WHERE analysisStatus IN ('pending', 'retry')
        AND labelSource != 'manual'""")
    abstract suspend fun pendingCount(): Int

    @Query("""UPDATE notifications SET analysisStatus = 'pending'
        WHERE analysisStatus = 'privacy_waiting' AND labelSource != 'manual'""")
    abstract suspend fun requeueCloudPaused(): Int

    /** A delayed result can never overwrite new content or a correction made while it was in flight. */
    @Query("""UPDATE notifications SET machineBucket = :bucket, machineKind = :kind,
        machineReason = :reason, machineConfidence = :confidence, analysisSource = :source,
        analysisStatus = :status
        WHERE notificationKey = :key AND contentVersion = :version AND contentHash = :hash
        AND labelSource != 'manual' AND analysisStatus IN ('pending', 'retry')
        AND storageEncryptionVersion = 1""")
    protected abstract suspend fun finishStoredAnalysis(
        key: String, version: Int, hash: String, bucket: String, kind: String,
        reason: String, confidence: Double, source: String, status: String,
    ): Int

    suspend fun finishAnalysis(
        key: String, version: Int, hash: String, bucket: String, kind: String,
        reason: String, confidence: Double, source: String, status: String,
    ): Int = finishStoredAnalysis(key, version, hash, bucket, kind,
        cipher.encrypt(reason, aad(key, "machineReason")), confidence, source, status)

    @Query("""SELECT * FROM notifications
        WHERE packageName IN (:packages) AND labelSource != 'manual'""")
    protected abstract suspend fun storedForPackages(packages: List<String>): List<NotificationEntry>

    @Transaction
    open suspend fun requeueForPackages(packages: List<String>) {
        storedForPackages(packages).forEach { stored ->
            save(decrypt(stored).copy(machineBucket = "REVIEW", machineKind = "unknown",
                machineReason = "偏好已更新，等待重新整理", machineConfidence = 0.0,
                analysisSource = "", analysisStatus = "pending"))
        }
    }

    @Transaction
    open suspend fun requeueOne(key: String) {
        val current = find(key) ?: return
        if (current.labelSource != "manual") save(current.copy(machineBucket = "REVIEW",
            machineReason = "关注偏好已更新，等待整理", analysisStatus = "pending"))
    }

    @Query("SELECT * FROM notifications WHERE storageEncryptionVersion = 0 LIMIT 100")
    protected abstract suspend fun legacyStored(): List<NotificationEntry>

    /** Invoked before collection/processing on startup; the transaction cannot leave half-encrypted rows. */
    @Transaction
    open suspend fun encryptLegacyRecords(): Int {
        var migrated = 0
        while (true) {
            val batch = legacyStored()
            if (batch.isEmpty()) return migrated
            batch.forEach { save(it) }
            migrated += batch.size
        }
    }

    @Query("DELETE FROM notifications")
    abstract suspend fun deleteAllNotifications(): Int

    @Query("DELETE FROM notifications WHERE updatedAt < :before")
    abstract suspend fun deleteNotificationsBefore(before: Long): Int

    /** Replays a persisted clear-history cutoff after a crash, retaining notifications posted later. */
    @Query("DELETE FROM notifications WHERE postedAt <= :cutoff")
    abstract suspend fun deleteNotificationsPostedAtOrBefore(cutoff: Long): Int

    @Query("SELECT * FROM source_kind_rules ORDER BY packageName, kind")
    abstract fun observeRules(): Flow<List<SourceKindRule>>

    @Query("SELECT * FROM source_kind_rules ORDER BY packageName, kind")
    abstract suspend fun rules(): List<SourceKindRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveRule(rule: SourceKindRule)

    @Query("DELETE FROM source_kind_rules WHERE packageName = :packageName AND kind = :kind")
    abstract suspend fun deleteRule(packageName: String, kind: String)

    private fun aad(key: String, field: String) = "qingxu.notification.v1:$key:$field"

    private fun encrypt(entry: NotificationEntry): NotificationEntry = entry.copy(
        appLabel = cipher.encrypt(entry.appLabel, aad(entry.notificationKey, "appLabel")),
        title = cipher.encrypt(entry.title, aad(entry.notificationKey, "title")),
        body = cipher.encrypt(entry.body, aad(entry.notificationKey, "body")),
        machineReason = cipher.encrypt(entry.machineReason, aad(entry.notificationKey, "machineReason")),
        storageEncryptionVersion = 1,
    )

    private fun decrypt(entry: NotificationEntry): NotificationEntry = when (entry.storageEncryptionVersion) {
        0 -> entry // Compatibility only; startup must immediately encrypt legacy rows before use.
        1 -> entry.copy(
            appLabel = cipher.decrypt(entry.appLabel, aad(entry.notificationKey, "appLabel")),
            title = cipher.decrypt(entry.title, aad(entry.notificationKey, "title")),
            body = cipher.decrypt(entry.body, aad(entry.notificationKey, "body")),
            machineReason = cipher.decrypt(entry.machineReason, aad(entry.notificationKey, "machineReason")),
        )
        else -> throw NotificationEncryptionException()
    }
}
