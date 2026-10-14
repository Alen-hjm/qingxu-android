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

fun NotificationEntry.displayReason(): String = if (hasCurrentCorrection()) {
    when (effectiveBucket()) {
        DecisionBucket.NOW -> "你将这条消息设为优先查看"
        DecisionBucket.LATER -> "你将这条消息留在稍后看"
        DecisionBucket.HIDDEN -> "你收起了这条消息，可随时恢复"
        DecisionBucket.REVIEW -> "你选择保留这条消息，暂不分类"
    }
} else machineReason.ifBlank { "暂时无法确定，已保留可见" }
