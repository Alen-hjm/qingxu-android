package com.qingxu.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.displayReason
import com.qingxu.app.core.decision.effectiveBucket
import com.qingxu.app.core.decision.hasCurrentCorrection
import com.qingxu.app.core.decision.Decision
import com.qingxu.app.core.decision.isLongTermHideEligible
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.SenderPreferencePolicy
import com.qingxu.app.data.local.UserLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotificationCard(entry: NotificationEntry, onClassify: (UserLabel) -> Unit, onInterest: () -> Unit,
    onRestore: () -> Unit, onOpen: () -> Unit, showOriginalAction: Boolean = true,
    guideTarget: String? = null, onHideSimilar: () -> Unit = {},
    concealContent: Boolean = false, onUnlock: () -> Unit = {},
    onHandled: () -> Unit = {}, onPrioritizeSender: () -> Unit = {}) {
    if (concealContent) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("内容已隐藏", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onUnlock) { Text("解锁查看") }
            }
        }
        return
    }
    var menuOpen by rememberSaveable(entry.notificationKey) { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable(entry.notificationKey) { mutableStateOf(false) }
    var confirmSimilar by rememberSaveable(entry.notificationKey) { mutableStateOf(false) }
    var confirmSender by rememberSaveable(entry.notificationKey) { mutableStateOf(false) }
    val hidden = entry.effectiveBucket() == DecisionBucket.HIDDEN
    val handled = entry.handledAt != null
    val canHidePromotions = !handled && !entry.hasCurrentCorrection() && Decision(entry.effectiveBucket(), entry.machineKind,
        entry.machineReason, entry.machineConfidence, entry.analysisSource).isLongTermHideEligible()
    val canPrioritizeSender = SenderPreferencePolicy.senderScope(entry) != null
    val starColor by animateColorAsState(if (entry.interested) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant, label = "star-color")

    if (confirmSimilar && canHidePromotions) AlertDialog(
        onDismissRequest = { confirmSimilar = false },
        title = { Text("收起此应用的促销？") },
        text = { Text("仅收起「${entry.appLabel}」中明确识别为促销的通知。聊天、交易和紧急消息继续保留。偏好保存在本机，可在设置中撤销。") },
        confirmButton = { TextButton(onClick = { confirmSimilar = false; onHideSimilar() }) { Text("记住偏好") } },
        dismissButton = { TextButton(onClick = { confirmSimilar = false }) { Text("取消") } },
    )
    if (confirmSender && canPrioritizeSender) AlertDialog(
        onDismissRequest = { confirmSender = false },
        title = { Text("这个会话优先") },
        text = { Text("以后同一应用、同一通知标题的会话优先查看。轻序会在本机记住这个偏好；不代表识别了联系人的真实身份。") },
        confirmButton = { TextButton(onClick = { confirmSender = false; onPrioritizeSender() }) { Text("记住偏好") } },
        dismissButton = { TextButton(onClick = { confirmSender = false }) { Text("取消") } },
    )

    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppAvatar(entry.packageName, entry.appLabel, modifier = Modifier.padding(top = 4.dp).size(36.dp))
                Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(entry.title.ifBlank { entry.appLabel }, style = MaterialTheme.typography.titleMedium,
                        maxLines = if (detailsExpanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                    if (entry.body.isNotBlank()) Text(entry.body, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (detailsExpanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = "调整整理结果")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!handled && !hidden) {
                            DropdownMenuItem(text = { Text("这条很重要") },
                                onClick = { menuOpen = false; onClassify(UserLabel.IMPORTANT_URGENT) })
                            DropdownMenuItem(text = { Text("留到稍后看") },
                                onClick = { menuOpen = false; onClassify(UserLabel.IMPORTANT) })
                            DropdownMenuItem(text = { Text("这条收起来") },
                                onClick = { menuOpen = false; onClassify(UserLabel.FILTERED) })
                        }
                        if (canPrioritizeSender) DropdownMenuItem(text = { Text("这个会话优先") },
                            onClick = { menuOpen = false; confirmSender = true })
                        if (canHidePromotions) DropdownMenuItem(text = { Text("以后收起此应用的促销") },
                            onClick = { menuOpen = false; confirmSimilar = true })
                        DropdownMenuItem(text = { Text(if (detailsExpanded) "收起整理详情" else "整理详情") },
                            onClick = { menuOpen = false; detailsExpanded = !detailsExpanded })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${entry.appLabel} · ${formatTime(entry.postedAt)}", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onInterest, modifier = Modifier.size(40.dp)) {
                    Icon(if (entry.interested) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (entry.interested) "取消关注" else "关注这条通知",
                        tint = starColor, modifier = Modifier.size(20.dp))
                }
                TextButton(onClick = if (handled) onHandled else if (hidden) onRestore else onHandled,
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    if (!hidden && !handled) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(if (handled) "恢复待看" else if (hidden) "恢复到收件箱" else "已处理",
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            if (detailsExpanded) Surface(Modifier.padding(bottom = 8.dp), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(decisionSource(entry), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(entry.displayReason(), style = MaterialTheme.typography.bodyMedium)
                    Text("收起的内容仍可恢复，通知栏处理由设置控制。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.hasCurrentCorrection() && entry.machineReason.isNotBlank()) Text(
                        "此前判断：${entry.machineReason}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun decisionSource(entry: NotificationEntry): String {
    if (entry.hasCurrentCorrection()) return "你已调整"
    val locallyOrganized = entry.analysisSource in setOf("local", "rule", "preference") &&
        entry.effectiveBucket() != DecisionBucket.REVIEW
    val localPrefix = if (locallyOrganized) "本机已整理 · " else ""
    return when {
        entry.analysisStatus == "pending" -> if (locallyOrganized) "本机已整理" else "正在整理"
        entry.analysisStatus in setOf("retry", "retry2") -> localPrefix + "云端待重试"
        entry.analysisStatus in setOf("error", "failed") -> localPrefix + "云端暂不可用"
        entry.analysisStatus == "privacy_waiting" -> localPrefix + "锁屏暂停云端"
        entry.analysisSource == "jev" -> "Jev 云端判断"
        entry.analysisSource == "ai" -> "云端分析"
        entry.analysisSource in setOf("rule", "preference") -> "按你的偏好"
        entry.effectiveBucket() == DecisionBucket.REVIEW -> "保留可见"
        else -> "本机整理"
    }
}

private fun formatTime(timestamp: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
