package com.qingxu.app.feature.assistant

import com.qingxu.app.core.ai.AssistantAction
import com.qingxu.app.core.decision.CloudDecisionPolicy
import com.qingxu.app.core.decision.Decision
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.effectiveBucket
import com.qingxu.app.core.decision.hasCurrentCorrection
import com.qingxu.app.core.decision.isLongTermHideEligible
import com.qingxu.app.core.privacy.CloudPrivacyGate
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel

/** A conversation produces a reviewable proposal, never permissions or executable instructions. */
internal fun assistantEdit(entry: NotificationEntry, action: AssistantAction, now: Long): NotificationEntry? {
    if (entry.handledAt != null) return null
    if (action == AssistantAction.HANDLE) return entry.copy(handledAt = now)
    if (action == AssistantAction.HIDE) {
        if (entry.machineBucket != "HIDDEN" || entry.analysisStatus != "done" ||
            entry.analysisSource !in setOf("local", "ai", "jev")) return null
        // Chat must not provide a less strict hiding route than automatic sorting.
        val evidence = CloudDecisionPolicy.protect(entry, Decision(DecisionBucket.HIDDEN,
            entry.machineKind, entry.machineReason, entry.machineConfidence, entry.analysisSource))
        if (entry.interested || entry.hasCurrentCorrection() ||
            CloudPrivacyGate.containsSensitiveSecret(entry.title + " " + entry.body) ||
            !evidence.isLongTermHideEligible()) return null
    }
    val label = when (action) {
        AssistantAction.PRIORITIZE -> UserLabel.IMPORTANT_URGENT
        AssistantAction.LATER -> UserLabel.IMPORTANT
        AssistantAction.HIDE -> UserLabel.FILTERED
        AssistantAction.HANDLE -> error("Handled above")
    }
    if (entry.hasCurrentCorrection() && entry.userLabel == label) return null
    val previous = if (label == UserLabel.FILTERED && entry.userLabel != UserLabel.FILTERED) {
        if (entry.hasCurrentCorrection()) entry.userLabel else when (entry.effectiveBucket()) {
            DecisionBucket.NOW -> UserLabel.IMPORTANT_URGENT
            DecisionBucket.LATER, DecisionBucket.HIDDEN -> UserLabel.IMPORTANT
            DecisionBucket.REVIEW -> entry.userLabel
        }
    } else entry.previousLabel
    return entry.copy(userLabel = label, previousLabel = previous, labelSource = "manual",
        manualVersion = entry.contentVersion, analysisStatus = "manual")
}

internal fun AssistantAction.label(): String = when (this) {
    AssistantAction.PRIORITIZE -> "优先看"
    AssistantAction.LATER -> "稍后看"
    AssistantAction.HIDE -> "收起促销"
    AssistantAction.HANDLE -> "已处理"
}
