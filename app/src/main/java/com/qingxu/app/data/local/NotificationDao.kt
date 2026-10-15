package com.qingxu.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.qingxu.app.core.privacy.NotificationCipher
import com.qingxu.app.core.privacy.NotificationEncryptionException
import com.qingxu.app.core.privacy.androidNotificationCipher
import com.qingxu.app.core.decision.Decision
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.displayBucket

@Dao
abstract class NotificationDao {
    private var cipher: NotificationCipher = androidNotificationCipher()

    /** Configure before first access. Production uses Android Keystore; tests inject a real AES key. */
    fun configureCipher(value: NotificationCipher) { cipher = value }

    @Query("SELECT * FROM notifications ORDER BY postedAt DESC")
    protected abstract fun observeStored(): Flow<List<NotificationEntry>>

    /** Reuse unchanged encrypted fields only while this collector is alive, never across sessions. */
    fun observeAll(): Flow<List<NotificationEntry>> = flow {
        var previous = emptyMap<String, Pair<NotificationEntry, NotificationEntry>>()
        var previousCipher = cipher
        try {
            observeStored().collect { stored ->
                if (previousCipher !== cipher) {
                    previous = emptyMap()
                    previousCipher = cipher
                }
                val next = stored.associate { entry ->
                    val cached = previous[entry.notificationKey]
                    entry.notificationKey to (entry to decrypt(entry, cached))
                }
                previous = next
                emit(next.values.map { it.second })
            }
        } finally {
            previous = emptyMap()
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    @Query("SELECT * FROM notifications WHERE notificationKey = :key")
    protected abstract suspend fun findStored(key: String): NotificationEntry?

    suspend fun find(key: String): NotificationEntry? = withContext(Dispatchers.IO) { findStored(key)?.let(::decrypt) }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun saveStored(entry: NotificationEntry)

    suspend fun save(entry: NotificationEntry) = withContext(Dispatchers.IO) { saveStored(encrypt(entry)) }

    @Transaction
    open suspend fun capture(incoming: CapturedNotification, initialDecision: ((NotificationEntry) -> Decision)? = null) {
        captureIfChanged(incoming, initialDecision)
    }

    /** Replayed snapshots must not re-encrypt history, invalidate the inbox, or reschedule analysis. */
    @Transaction
    open suspend fun captureIfChanged(incoming: CapturedNotification, initialDecision: ((NotificationEntry) -> Decision)? = null): Boolean {
        val previous = find(incoming.notificationKey)
        if (previous != null && previous.packageName == incoming.packageName &&
            previous.contentHash == incoming.contentHash && previous.appLabel == incoming.appLabel &&
            previous.title == incoming.title && previous.body == incoming.body &&
            previous.channelId == incoming.channelId && previous.postedAt == incoming.postedAt && previous.removedAt == null) return false
        val merged = mergeNotification(previous, incoming)
        // Give the inbox a useful local placement in the same transaction as capture. A slow
        // network request or delayed WorkManager job must not make categorizing a user's job.
        val decision = if (initialDecision != null && merged.labelSource != "manual" &&
            (previous == null || previous.contentHash != incoming.contentHash)) initialDecision(merged) else null
        save(if (decision == null) merged else merged.copy(
            machineBucket = decision.bucket.name, machineKind = decision.kind,
            machineReason = decision.reason, machineConfidence = decision.confidence,
            analysisSource = decision.source,
        ))
        return true
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

    @Query("UPDATE notifications SET removedAt = :at WHERE notificationKey = :key AND removedAt IS NULL")
    abstract suspend fun markRemoved(key: String, at: Long)

    @Query("""UPDATE notifications SET
        handledAt = CASE WHEN :handled THEN COALESCE(handledAt, :at) ELSE NULL END
        WHERE notificationKey = :key AND contentVersion = :expectedVersion AND contentHash = :expectedHash""")
    protected abstract suspend fun setHandledStored(
        key: String, handled: Boolean, at: Long, expectedVersion: Int, expectedHash: String,
    ): Int

    /** Complete only the content actually shown. A recycled notification key is a new message. */
    suspend fun setHandled(key: String, handled: Boolean, expectedVersion: Int, expectedHash: String): Int =
        setHandledStored(key, handled, System.currentTimeMillis(), expectedVersion, expectedHash)

    @Query("""SELECT * FROM notifications
        WHERE ((NOT :onlyRetry AND analysisStatus = 'pending')
            OR (:includeRetry AND analysisStatus IN ('retry', 'retry2')))
        AND labelSource != 'manual' AND handledAt IS NULL
        ORDER BY CASE WHEN analysisStatus = 'pending' THEN 0 ELSE 1 END, updatedAt ASC LIMIT :limit""")
    protected abstract suspend fun pendingStored(limit: Int, includeRetry: Boolean, onlyRetry: Boolean): List<NotificationEntry>

    suspend fun pending(limit: Int = 30, includeRetry: Boolean = true, onlyRetry: Boolean = false): List<NotificationEntry> =
        withContext(Dispatchers.IO) { pendingStored(limit, includeRetry, onlyRetry).map(::decrypt) }

    @Query("""SELECT * FROM notifications WHERE packageName IN (:packages) AND handledAt IS NULL
        ORDER BY postedAt DESC LIMIT :limit""")
    protected abstract suspend fun assistantCandidatesStored(packages: List<String>, limit: Int): List<NotificationEntry>

    suspend fun assistantCandidates(packages: List<String>, limit: Int = 80): List<NotificationEntry> =
        withContext(Dispatchers.IO) { assistantCandidatesStored(packages, limit.coerceIn(1, 200)).map(::decrypt) }

    @Query("""UPDATE notifications SET userLabel = :label, previousLabel = :previousLabel,
        labelSource = :labelSource, manualVersion = :manualVersion, analysisStatus = :status, handledAt = :handledAt
        WHERE notificationKey = :key""")
    protected abstract suspend fun writeAssistantState(
        key: String, label: UserLabel, previousLabel: UserLabel, labelSource: String,
        manualVersion: Int, status: String, handledAt: Long?,
    ): Int

    /** All comparisons and writes share one transaction; stale proposals and stale undo are skipped. */
    @Transaction
    open suspend fun applyAssistantEdits(edits: List<AssistantEntryEdit>): Int = applyAssistantEditsReturning(edits).size

    @Transaction
    open suspend fun applyAssistantEditsReturning(edits: List<AssistantEntryEdit>, beforeWrite: suspend () -> Unit = {}): List<AssistantEntryEdit> {
        val changed = mutableListOf<AssistantEntryEdit>()
        for ((before, after) in edits) {
            beforeWrite()
            if (before.notificationKey != after.notificationKey) continue
            val current = find(before.notificationKey) ?: continue
            if (current != before) continue
            val updated = current.copy(userLabel = after.userLabel, previousLabel = after.previousLabel,
                labelSource = after.labelSource, manualVersion = after.manualVersion,
                analysisStatus = after.analysisStatus, handledAt = after.handledAt)
            if (updated != current && writeAssistantState(updated.notificationKey, updated.userLabel, updated.previousLabel,
                updated.labelSource, updated.manualVersion, updated.analysisStatus, updated.handledAt) == 1)
                changed += AssistantEntryEdit(current, updated)
        }
        beforeWrite()
        return changed
    }

    @Query("""SELECT COUNT(*) FROM notifications WHERE analysisStatus IN ('pending', 'retry', 'retry2')
        AND labelSource != 'manual' AND handledAt IS NULL""")
    abstract suspend fun pendingCount(): Int

    @Query("""SELECT COUNT(*) FROM notifications WHERE analysisStatus IN ('retry', 'retry2')
        AND labelSource != 'manual' AND handledAt IS NULL""")
    abstract suspend fun retryCount(): Int

    @Query("""UPDATE notifications SET analysisStatus = 'pending'
        WHERE analysisStatus = 'privacy_waiting' AND labelSource != 'manual' AND handledAt IS NULL""")
    abstract suspend fun requeueCloudPaused(): Int

    /** Upgrade only unresolved legacy local results; do not replace completed cloud decisions. */
    @Query("""UPDATE notifications SET analysisStatus = 'pending'
        WHERE packageName IN (:packages) AND labelSource != 'manual' AND handledAt IS NULL AND machineBucket = 'REVIEW'
        AND analysisStatus NOT IN ('pending', 'retry', 'retry2')
        AND (analysisSource IN ('', 'local', 'cloud') OR analysisStatus = 'privacy_waiting')""")
    abstract suspend fun requeueLegacyLocalResults(packages: List<String>): Int

    /** A delayed result can never overwrite new content or a correction made while it was in flight. */
    @Query("""UPDATE notifications SET machineBucket = :bucket, machineKind = :kind,
        machineReason = :reason, machineConfidence = :confidence, analysisSource = :source,
        analysisStatus = :status
        WHERE notificationKey = :key AND contentVersion = :version AND contentHash = :hash
        AND labelSource != 'manual' AND handledAt IS NULL AND analysisStatus IN ('pending', 'retry', 'retry2')
        AND storageEncryptionVersion = 1""")
    protected abstract suspend fun finishStoredAnalysis(
        key: String, version: Int, hash: String, bucket: String, kind: String,
        reason: String, confidence: Double, source: String, status: String,
    ): Int

    suspend fun finishAnalysis(
        key: String, version: Int, hash: String, bucket: String, kind: String,
        reason: String, confidence: Double, source: String, status: String,
    ): Int = withContext(Dispatchers.IO) {
        finishStoredAnalysis(key, version, hash, bucket, kind,
            cipher.encrypt(reason, aad(key, "machineReason")), confidence, source, status)
    }

    @Query("""SELECT * FROM notifications
        WHERE packageName IN (:packages) AND labelSource != 'manual' AND handledAt IS NULL""")
    protected abstract suspend fun storedForPackages(packages: List<String>): List<NotificationEntry>

    @Transaction
    open suspend fun requeueForPackages(packages: List<String>) {
        withContext(Dispatchers.IO) {
            storedForPackages(packages).forEach { stored ->
                if (stored.storageEncryptionVersion != 1) {
                    save(decrypt(stored).copy(machineBucket = "REVIEW", machineKind = "unknown",
                        machineReason = "偏好已更新，等待重新整理", machineConfidence = 0.0,
                        analysisSource = "", analysisStatus = "pending"))
                    return@forEach
                }
                // Reasons are authenticated per key, so retain each original content ciphertext.
                val reason = cipher.encrypt("偏好已更新，等待重新整理", aad(stored.notificationKey, "machineReason"))
                requeueStored(stored.notificationKey, reason, true)
            }
        }
    }

    @Query("""UPDATE notifications SET machineBucket = 'REVIEW', machineReason = :reason, analysisStatus = 'pending',
        machineKind = CASE WHEN :resetDecision THEN 'unknown' ELSE machineKind END,
        machineConfidence = CASE WHEN :resetDecision THEN 0.0 ELSE machineConfidence END,
        analysisSource = CASE WHEN :resetDecision THEN '' ELSE analysisSource END
        WHERE notificationKey = :key AND labelSource != 'manual' AND handledAt IS NULL
        AND storageEncryptionVersion = 1""")
    protected abstract suspend fun requeueStored(key: String, reason: String, resetDecision: Boolean): Int

    @Transaction
    open suspend fun requeueOne(key: String) {
        withContext(Dispatchers.IO) {
            val current = findStored(key) ?: return@withContext
            if (current.labelSource != "manual" && current.handledAt == null) {
                if (current.storageEncryptionVersion == 1) {
                    requeueStored(key, cipher.encrypt("关注偏好已更新，等待整理", aad(key, "machineReason")), false)
                } else save(decrypt(current).copy(machineBucket = "REVIEW", machineReason = "关注偏好已更新，等待整理", analysisStatus = "pending"))
            }
        }
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

    @Query("SELECT * FROM sender_priority_rules ORDER BY packageName, scopeHash")
    abstract fun observePrioritySenders(): Flow<List<SenderPriorityRule>>

    @Query("SELECT * FROM sender_priority_rules ORDER BY packageName, scopeHash")
    abstract suspend fun prioritySenders(): List<SenderPriorityRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun savePrioritySenderStored(rule: SenderPriorityRule)

    suspend fun savePrioritySender(rule: SenderPriorityRule) {
        require(rule.packageName.isNotBlank() && rule.scopeHash.matches(Regex("[0-9a-f]{64}")))
        savePrioritySenderStored(rule)
    }

    @Query("DELETE FROM sender_priority_rules WHERE packageName = :packageName AND scopeHash = :scopeHash")
    abstract suspend fun deletePrioritySender(packageName: String, scopeHash: String)

    private fun aad(key: String, field: String) = "qingxu.notification.v1:$key:$field"

    /** Restrict surface reads to relevant rows; summary metadata needs no decryption at all. */
    @Query("""SELECT * FROM notifications WHERE packageName IN (:packages) AND handledAt IS NULL
        AND (machineBucket = 'NOW' OR userLabel IN ('IMPORTANT_URGENT', 'URGENT'))""")
    protected abstract suspend fun summaryCandidatesStored(packages: List<String>): List<NotificationEntry>

    suspend fun prioritySummaryCount(packages: List<String>, now: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            summaryCandidatesStored(packages).count { it.displayBucket(now) == DecisionBucket.NOW }
        }

    @Query("""SELECT * FROM notifications WHERE packageName IN (:packages) AND handledAt IS NULL
        AND removedAt IS NULL AND labelSource != 'manual' AND interested = 0 AND analysisStatus = 'done'
        AND analysisSource IN ('local', 'ai', 'jev') AND machineKind = 'marketing' AND machineBucket = 'HIDDEN'
        AND machineConfidence >= 0.95""")
    protected abstract suspend fun promotionCandidatesStored(packages: List<String>): List<NotificationEntry>

    suspend fun promotionCandidates(packages: List<String>): List<NotificationEntry> = withContext(Dispatchers.IO) {
        promotionCandidatesStored(packages).map(::decrypt)
    }

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

    private fun decrypt(entry: NotificationEntry, cached: Pair<NotificationEntry, NotificationEntry>?): NotificationEntry {
        if (entry.storageEncryptionVersion != 1 || cached?.first?.storageEncryptionVersion != 1) return decrypt(entry)
        val (stored, clear) = cached
        fun field(name: String, value: String, old: String, plaintext: String): String =
            if (value == old) plaintext else cipher.decrypt(value, aad(entry.notificationKey, name))
        return entry.copy(
            appLabel = field("appLabel", entry.appLabel, stored.appLabel, clear.appLabel),
            title = field("title", entry.title, stored.title, clear.title),
            body = field("body", entry.body, stored.body, clear.body),
            machineReason = field("machineReason", entry.machineReason, stored.machineReason, clear.machineReason),
        )
    }
}
