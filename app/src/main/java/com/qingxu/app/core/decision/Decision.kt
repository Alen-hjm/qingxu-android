package com.qingxu.app.core.decision

import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel

enum class DecisionBucket { NOW, LATER, HIDDEN, REVIEW }

data class Decision(
    val bucket: DecisionBucket,
    val kind: String,
    val reason: String,
    val confidence: Double,
    val source: String = "local",
)

/** A persistent hide preference must never turn an uncertain or useful message into an ad. */
fun Decision.isLongTermHideEligible(): Boolean = kind == "marketing" && bucket == DecisionBucket.HIDDEN &&
    confidence.isFinite() && confidence >= 0.95

/** Counts evidence-based decisions, not the visible default location of an unknown message. */
fun NotificationEntry.isConfidentlyOrganized(): Boolean = handledAt == null && !hasCurrentCorrection() &&
    analysisSource in setOf("local", "ai", "jev", "preference") &&
    machineKind in setOf("marketing", "transaction", "delivery", "event", "message", "security", "medical", "verification") &&
    machineConfidence.isFinite() && machineConfidence >= 0.8 && effectiveBucket() != DecisionBucket.REVIEW

fun NotificationEntry.hasCurrentCorrection(): Boolean =
    labelSource == "manual" && (manualVersion == 0 || manualVersion == contentVersion)

fun NotificationEntry.effectiveBucket(): DecisionBucket {
    if (hasCurrentCorrection()) return when (userLabel) {
        UserLabel.IMPORTANT_URGENT, UserLabel.URGENT -> DecisionBucket.NOW
        UserLabel.IMPORTANT, UserLabel.LOW_PRIORITY -> DecisionBucket.LATER
        UserLabel.FILTERED -> DecisionBucket.HIDDEN
        UserLabel.UNSORTED -> DecisionBucket.REVIEW
    }
    return DecisionBucket.entries.firstOrNull { it.name == machineBucket } ?: DecisionBucket.REVIEW
}

/** Time-bound machine signals expire for display; user corrections and unresolved transactions do not. */
fun NotificationEntry.displayBucket(now: Long = System.currentTimeMillis()): DecisionBucket {
    val bucket = effectiveBucket()
    if (bucket != DecisionBucket.NOW || hasCurrentCorrection() || analysisSource == "preference") return bucket
    val timeBound = machineKind in setOf("verification", "event", "message", "security", "medical")
    if (timeBound && (now - postedAt > 30 * 60_000L || postedAt - now > 5 * 60_000L)) return DecisionBucket.LATER
    return bucket
}

fun NotificationEntry.displayReason(now: Long = System.currentTimeMillis()): String = if (hasCurrentCorrection()) {
    when (effectiveBucket()) {
        DecisionBucket.NOW -> "你将这条消息设为优先查看"
        DecisionBucket.LATER -> "你将这条消息留在稍后看"
        DecisionBucket.HIDDEN -> "你收起了这条消息，可随时恢复"
        DecisionBucket.REVIEW -> "你选择保留这条消息，暂不分类"
    }
} else if (effectiveBucket() == DecisionBucket.NOW && displayBucket(now) == DecisionBucket.LATER) {
    when (machineKind) {
        "verification" -> "较早的验证码已移到稍后看，请以原通知有效期为准"
        "event" -> "较早的日程提醒已移到稍后看，请核实当前时间"
        "medical", "security" -> "较早的风险提醒已保留，请核实当前情况"
        else -> "较早的急事消息已移到稍后看，请核实是否仍需处理"
    }
} else machineReason.ifBlank { "暂时无法确定，已保留可见" }
