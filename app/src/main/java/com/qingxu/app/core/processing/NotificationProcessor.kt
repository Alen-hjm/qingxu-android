package com.qingxu.app.core.processing

import com.qingxu.app.core.ai.CloudDecisionClient
import com.qingxu.app.core.ai.CloudRequestGuard
import com.qingxu.app.core.ai.AiPreferences
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.decision.Decision
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.LocalClassifier
import com.qingxu.app.core.decision.CloudDecisionPolicy
import com.qingxu.app.data.local.NotificationDao
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.repository.SourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    var policyGeneration: Long = 0
        private set
    /** Call with gate held whenever local preferences or selected sources change. */
    fun invalidatePolicy() { policyGeneration++ }

    suspend fun processBatch(attempt: Int): Boolean = runner.withLock {
        beforeProcessing()
        if (!deviceLocked()) dao.requeueCloudPaused()
        var retry = false
        val batch = dao.pending(8)
        for (entry in batch) {
            if (!sources.accepts(entry.packageName)) {
                gate.withLock {
                    if (!sources.accepts(entry.packageName)) finish(entry,
                        Decision(DecisionBucket.REVIEW, "unknown", "该应用已停止整理", 0.0), "paused")
                }
                continue
            }
            val snapshot = preferences.state.value
            val privacy = privacySettings()
            val generation = policyGeneration
            val local = LocalClassifier.classify(entry)
            val permission = CloudPrivacyGate.permission(entry, snapshot, privacy, deviceLocked())
            var status = "done"
            var usedCloud = false
            var decision = if (CloudPrivacyGate.containsSensitiveSecret(entry.title + " " + entry.body)) {
                Decision(DecisionBucket.REVIEW, "unknown", "可能包含验证码，保留在本机供你查看", 0.0)
            } else local ?: if (permission == CloudPermission.ALLOWED) {
                try {
                    val connection = gate.withLock {
                        val current = preferences.state.value
                        if (current.revision == snapshot.revision && privacySettings().revision == privacy.revision && policyGeneration == generation &&
                            CloudPrivacyGate.permission(entry, current, privacySettings(), deviceLocked()) == CloudPermission.ALLOWED &&
                            sources.accepts(entry.packageName) && isCurrentPending(entry)) {
                            connectionSnapshot()
                        } else null
                    }
                    if (connection == null) { enqueue(); continue }
                    usedCloud = true
                    val guard = CloudRequestGuard {
                        gate.withLock {
                            val current = preferences.state.value
                            if (current.revision != snapshot.revision || privacySettings().revision != privacy.revision ||
                                policyGeneration != generation || !sources.accepts(entry.packageName) || !isCurrentPending(entry) ||
                                CloudPrivacyGate.permission(entry, current, privacySettings(), deviceLocked()) != CloudPermission.ALLOWED) {
                                throw AuthorizationChanged()
                            }
                        }
                    }
                    withContext(guard) {
                        guard.check()
                        cloudAnalyze(connection.first.copy(interests = CloudPrivacyGate.redact(connection.first.interests)),
                            connection.second, CloudPrivacyGate.prepare(entry))
                    }
                } catch (_: AuthorizationChanged) {
                    enqueue()
                    continue
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = if (attempt < 2) "retry" else "failed"
                    retry = retry || status == "retry"
                    Decision(DecisionBucket.REVIEW, "unknown", if (status == "retry")
                        "云端暂未完成，消息保留可见并稍后重试" else "云端分析失败，消息保留可见；可在设置重新整理", 0.0, "cloud")
                }
            } else {
                status = if (permission == CloudPermission.LOCKED) "privacy_waiting" else "local_only"
                Decision(DecisionBucket.REVIEW, "unknown", when (permission) {
                    CloudPermission.LOCKED -> "设备已锁定，未发送云端；解锁后继续整理"
                    CloudPermission.LOCAL_ONLY -> "此来源仅在本机处理，暂不能确定，保留可见"
                    else -> "本地规则暂不能确定，消息保留可见"
                }, 0.0)
            }
            gate.withLock applyDecision@ {
                if (preferences.state.value.revision != snapshot.revision || privacySettings().revision != privacy.revision ||
                    policyGeneration != generation || !sources.accepts(entry.packageName)) return@applyDecision
                if (usedCloud && privacy.blockCloudWhenLocked && deviceLocked()) {
                    finish(entry, Decision(DecisionBucket.REVIEW, "unknown", "设备已锁定，消息保留可见", 0.0), "privacy_waiting")
                    return@applyDecision
                }
                val latest = dao.find(entry.notificationKey) ?: return@applyDecision
                decision = CloudDecisionPolicy.protect(entry, decision)
                val preference = dao.rules().firstOrNull { it.packageName == entry.packageName && it.kind == decision.kind && it.hidden }
                if (preference != null && decision.bucket != DecisionBucket.REVIEW) {
                    decision = decision.copy(bucket = DecisionBucket.HIDDEN, reason = "按你的偏好收起此应用的这类消息", source = "preference")
                } else if (decision.bucket == DecisionBucket.HIDDEN) {
                    val terms = snapshot.interests.split(Regex("[,，;；\\n]" )).map { it.trim() }.filter { it.length >= 2 }
                    val content = entry.title + " " + entry.body
                    if (latest.interested || terms.any { content.contains(it, ignoreCase = true) }) {
                        decision = decision.copy(bucket = DecisionBucket.LATER, reason = "命中你的关注内容，保留稍后查看", source = "preference")
                    }
                }
                finish(entry, decision, status)
            }
        }
        if (batch.size == 8 && !retry) enqueue()
        retry
    }

    private suspend fun isCurrentPending(entry: NotificationEntry): Boolean {
        if (entry.postedAt <= privacySettings().deletedBefore) return false
        val current = dao.find(entry.notificationKey) ?: return false
        return current.contentVersion == entry.contentVersion && current.contentHash == entry.contentHash &&
            current.labelSource != "manual" && current.analysisStatus in setOf("pending", "retry")
    }

    private class AuthorizationChanged : CancellationException("Cloud permission changed")

    private suspend fun finish(entry: NotificationEntry, decision: Decision, status: String) = dao.finishAnalysis(
        entry.notificationKey, entry.contentVersion, entry.contentHash, decision.bucket.name, decision.kind,
        decision.reason, decision.confidence, decision.source, status,
    )
}

internal fun containsVerificationCode(text: String): Boolean = CloudPrivacyGate.containsSensitiveSecret(text)
