package com.qingxu.app.core.processing

import com.qingxu.app.core.ai.CloudDecisionClient
import com.qingxu.app.core.ai.CloudRequestGuard
import com.qingxu.app.core.ai.AiPreferences
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.decision.Decision
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.LocalClassifier
import com.qingxu.app.core.decision.CloudDecisionPolicy
import com.qingxu.app.core.decision.isLongTermHideEligible
import com.qingxu.app.data.local.NotificationDao
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.SenderPreferencePolicy
import com.qingxu.app.data.repository.SourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.qingxu.app.core.privacy.CloudPrivacyGate
import com.qingxu.app.core.privacy.CloudPermission
import com.qingxu.app.core.privacy.PrivacySettings

class NotificationProcessor(
    private val dao: NotificationDao,
    private val sources: SourcePreferences,
    private val preferences: AiPreferences,
    private val cloudAnalyze: suspend (AiSettings, String, NotificationEntry) -> Decision = { settings, key, entry -> CloudDecisionClient().classify(settings, key, entry) },
    private val connectionSnapshot: () -> Pair<AiSettings, String> = preferences::connectionSnapshot,
    private val privacySettings: () -> PrivacySettings = { PrivacySettings() },
    private val deviceLocked: () -> Boolean = { false },
    private val beforeProcessing: suspend () -> Unit = {},
    private val enqueue: () -> Unit,
) {
    // Also guards policy changes against late network results in the same process.
    val gate = Mutex()
    private val runner = Mutex()
    private val mutableActivity = MutableStateFlow(ProcessingActivity())
    val activity = mutableActivity.asStateFlow()
    var policyGeneration: Long = 0
        private set
    /** Call with gate held whenever local preferences or selected sources change. */
    fun invalidatePolicy() { policyGeneration++ }

    /** The retry budget belongs to each row, never to whichever WorkRequest happens to pick it up. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun processBatch(attempt: Int, includeRetry: Boolean = true, onlyRetry: Boolean = false): Boolean = runner.withLock {
        beforeProcessing()
        if (!deviceLocked() && dao.requeueCloudPaused() > 0 && onlyRetry) enqueue()
        val batch = dao.pending(8, includeRetry = includeRetry, onlyRetry = onlyRetry)
        val cloud = mutableListOf<PendingCloud>()
        // Finish the whole local pass before starting any slow network request.
        for (entry in batch) {
            gate.withLock localPass@ {
                if (!isCurrentPending(entry)) return@localPass
                if (!sources.accepts(entry.packageName)) {
                    finish(entry, Decision(DecisionBucket.REVIEW, "unknown", "该应用已停止整理", 0.0), "paused")
                    return@localPass
                }
                val snapshot = preferences.state.value
                val privacy = privacySettings()
                val local = LocalClassifier.classify(entry)
                val fallback = LocalClassifier.fallback(entry)
                val permission = CloudPrivacyGate.permission(entry, snapshot, privacy, deviceLocked())
                val sensitive = CloudPrivacyGate.containsSensitiveSecret(entry.title + " " + entry.body)
                val decision = if (sensitive && local?.bucket == DecisionBucket.HIDDEN) fallback else local ?: fallback
                val preferred = applyPreferences(entry, decision, snapshot)
                val priorityConversation = preferred.source == "preference" && preferred.bucket == DecisionBucket.NOW
                val status = when {
                    sensitive -> "local_only"
                    priorityConversation -> "done"
                    local != null -> "done"
                    permission == CloudPermission.ALLOWED -> entry.analysisStatus
                    permission == CloudPermission.LOCKED -> "privacy_waiting"
                    else -> "local_only"
                }
                finish(entry, preferred, status)
                if (!sensitive && !priorityConversation && local == null && permission == CloudPermission.ALLOWED) {
                    cloud += PendingCloud(entry, snapshot, privacy, policyGeneration, fallback)
                }
            }
        }
        // Yield between small network batches so a backlog cannot monopolize the processor for minutes.
        for (pending in cloud.take(MAX_CLOUD_REQUESTS)) {
            val (entry, snapshot, privacy, generation, fallback) = pending
            var requestStarted = false
            try {
                var status = "done"
                val decision = try {
                    val connection = gate.withLock {
                        if (isAuthorized(pending)) connectionSnapshot() else null
                    }
                    if (connection == null) { enqueue(); continue }
                    val guard = CloudRequestGuard {
                        gate.withLock { if (!isAuthorized(pending)) throw AuthorizationChanged() }
                    }
                    withContext(guard) {
                        guard.check()
                        mutableActivity.value = ProcessingActivity(running = true, settingsRevision = snapshot.revision,
                            provider = snapshot.provider)
                        requestStarted = true
                        cloudAnalyze(connection.first.copy(interests = CloudPrivacyGate.redact(connection.first.interests)),
                            connection.second, CloudPrivacyGate.prepare(entry))
                    }
                } catch (_: AuthorizationChanged) {
                    enqueue()
                    continue
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    recordOutcome(snapshot, ProcessingOutcome.FAILED)
                    status = when (entry.analysisStatus) { "retry" -> "retry2"; "retry2" -> "failed"; else -> "retry" }
                    // A provider failure never takes away the useful on-device result or requests manual sorting.
                    fallback.copy(reason = if (status == "failed")
                        "云端暂不可用，已按本机规则保留，可稍后重新整理"
                    else "已先按本机规则整理，云端稍后重试")
                }
                gate.withLock applyDecision@ {
                    if (preferences.state.value.revision != snapshot.revision || privacySettings().revision != privacy.revision ||
                        policyGeneration != generation || !sources.accepts(entry.packageName) || !isCurrentPending(entry)) return@applyDecision
                    if (privacy.blockCloudWhenLocked && deviceLocked()) {
                        finish(entry, applyPreferences(entry, fallback, snapshot), "privacy_waiting")
                        return@applyDecision
                    }
                    val applied = finish(entry, applyPreferences(entry, decision, snapshot), status)
                    if (applied > 0 && status == "done") recordOutcome(snapshot, ProcessingOutcome.SUCCEEDED)
                }
            } finally {
                // Cancellation, stale rows and revoked permission must never look like a successful sort.
                if (requestStarted && mutableActivity.value.running) recordOutcome(snapshot, ProcessingOutcome.DISCARDED)
            }
        }
        // A full/partial network batch must drain even when one unrelated row is waiting for retry.
        if (!onlyRetry && (batch.size == 8 || cloud.size > MAX_CLOUD_REQUESTS)) enqueue()
        dao.retryCount() > 0
    }

    private data class PendingCloud(
        val entry: NotificationEntry,
        val settings: AiSettings,
        val privacy: PrivacySettings,
        val generation: Long,
        val fallback: Decision,
    )

    private fun recordOutcome(settings: AiSettings, outcome: ProcessingOutcome) {
        mutableActivity.value = ProcessingActivity(settingsRevision = settings.revision,
            provider = settings.provider, lastOutcome = outcome, lastCompletedAt = System.currentTimeMillis())
    }

    private suspend fun isAuthorized(pending: PendingCloud): Boolean {
        val current = preferences.state.value
        return current.revision == pending.settings.revision && privacySettings().revision == pending.privacy.revision &&
            policyGeneration == pending.generation && sources.accepts(pending.entry.packageName) && isCurrentPending(pending.entry) &&
            CloudPrivacyGate.permission(pending.entry, current, privacySettings(), deviceLocked()) == CloudPermission.ALLOWED
    }

    private suspend fun applyPreferences(entry: NotificationEntry, input: Decision, settings: AiSettings): Decision {
        var decision = CloudDecisionPolicy.protect(entry, input)
        val latest = dao.find(entry.notificationKey) ?: return decision
        if (latest.handledAt != null || latest.labelSource == "manual" ||
            latest.contentVersion != entry.contentVersion || latest.contentHash != entry.contentHash) return decision
        if (decision.bucket == DecisionBucket.NOW) return decision
        // Only known chat sources with a readable, stable conversation title produce a local scope.
        // The hash is a local preference lookup and is never included in a cloud request.
        val scope = SenderPreferencePolicy.senderScope(latest)
        if (scope != null && LocalClassifier.fallback(latest).bucket != DecisionBucket.REVIEW &&
            dao.prioritySenders().any { it.packageName == latest.packageName && it.scopeHash == scope }) {
            return Decision(DecisionBucket.NOW, "message", "按你的偏好优先显示这个会话的新消息", 1.0, "preference")
        }
        // Legacy rules for delivery/message/transaction/etc. cannot lower or hide useful messages.
        // A marketing preference applies only after the original-notification safety gate agrees.
        val preference = if (decision.isLongTermHideEligible()) dao.rules().firstOrNull {
            it.packageName == entry.packageName && it.kind == "marketing" && it.hidden
        } else null
        if (decision.bucket == DecisionBucket.HIDDEN) {
            val terms = settings.interests.split(Regex("[,，;；\\n]")).map { it.trim() }.filter { it.length >= 2 }
            val content = entry.title + " " + entry.body
            if (latest.interested || terms.any { content.contains(it, ignoreCase = true) }) {
                decision = decision.copy(bucket = DecisionBucket.LATER, reason = "命中你的关注内容，保留稍后查看", source = "preference")
            } else if (preference != null) {
                decision = decision.copy(reason = "按你的偏好收起此应用的纯促销消息", source = "preference")
            }
        }
        return decision
    }

    private suspend fun isCurrentPending(entry: NotificationEntry): Boolean {
        if (entry.postedAt <= privacySettings().deletedBefore) return false
        val current = dao.find(entry.notificationKey) ?: return false
        return current.contentVersion == entry.contentVersion && current.contentHash == entry.contentHash &&
            current.handledAt == null && current.labelSource != "manual" && current.analysisStatus in setOf("pending", "retry", "retry2")
    }

    private class AuthorizationChanged : CancellationException("Cloud permission changed")

    private suspend fun finish(entry: NotificationEntry, decision: Decision, status: String) = dao.finishAnalysis(
        entry.notificationKey, entry.contentVersion, entry.contentHash, decision.bucket.name, decision.kind,
        decision.reason, decision.confidence, decision.source, status,
    )

    private companion object { const val MAX_CLOUD_REQUESTS = 2 }
}

internal fun containsVerificationCode(text: String): Boolean = CloudPrivacyGate.containsSensitiveSecret(text)
