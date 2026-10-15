package com.qingxu.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.displayReason
import com.qingxu.app.core.decision.effectiveBucket
import com.qingxu.app.core.decision.hasCurrentCorrection
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.feature.onboarding.CoachHint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotificationCard(entry: NotificationEntry, onClassify: (UserLabel) -> Unit, onInterest: () -> Unit,
    onRestore: () -> Unit, onOpen: () -> Unit, showOriginalAction: Boolean = true,
    guideTarget: String? = null, onHideSimilar: () -> Unit = {},
    concealContent: Boolean = false, onUnlock: () -> Unit = {}) {
    if (concealContent) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("内容已隐藏", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onUnlock) { Text("解锁查看") }
            }
        }
        return
    }
    var menuOpen by rememberSaveable(entry.notificationKey) { mutableStateOf(false) }
    var bodyExpanded by rememberSaveable(entry.notificationKey) { mutableStateOf(false) }
    var reasonExpanded by rememberSaveable(entry.notificationKey) { mutableStateOf(false) }
    var confirmSimilar by rememberSaveable(entry.notificationKey) { mutableStateOf(false) }
    val hidden = entry.effectiveBucket() == DecisionBucket.HIDDEN
    val kindName = notificationKindName(entry.machineKind)
    val source = decisionSource(entry)
    val starScale = remember { Animatable(1f) }
    val starColor by animateColorAsState(if (entry.interested) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant, label = "star-color")
    LaunchedEffect(entry.interested) {
        if (entry.interested) {
            starScale.animateTo(1.22f, tween(110))
            starScale.animateTo(1f, spring())
        }
    }
    val highlightColor = MaterialTheme.colorScheme.primary
    fun emphasis(target: String): Modifier = if (guideTarget == target)
        Modifier.border(2.dp, highlightColor, RoundedCornerShape(16.dp)) else Modifier

    if (confirmSimilar && kindName != null) AlertDialog(
        onDismissRequest = { confirmSimilar = false },
        title = { Text("以后收起这类消息？") },
        text = { Text("为「${entry.appLabel}」添加一条规则：以后识别为「$kindName」的通知会进入「已收起」。其他应用不受影响，可在设置中删除规则。") },
        confirmButton = { TextButton(onClick = { confirmSimilar = false; onHideSimilar() }) { Text("添加规则") } },
        dismissButton = { TextButton(onClick = { confirmSimilar = false }) { Text("取消") } },
    )

    Card(modifier = Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppAvatar(entry.packageName, entry.appLabel)
                Column(Modifier.weight(1f)) {
                    Text(entry.appLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatTime(entry.postedAt), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onInterest, modifier = emphasis("star")) {
                    Icon(if (entry.interested) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (entry.interested) "取消关注" else "关注这条通知",
                        tint = starColor, modifier = Modifier.graphicsLayer { scaleX = starScale.value; scaleY = scaleX })
                }
                if (showOriginalAction) Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = "调整整理结果")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!hidden) {
                            DropdownMenuItem(text = { Text("这条很重要") },
                                onClick = { menuOpen = false; onClassify(UserLabel.IMPORTANT_URGENT) })
                            DropdownMenuItem(text = { Text("留到稍后看") },
                                onClick = { menuOpen = false; onClassify(UserLabel.IMPORTANT) })
                            DropdownMenuItem(text = { Text("这条收起来") },
                                onClick = { menuOpen = false; onClassify(UserLabel.FILTERED) })
                            DropdownMenuItem(text = { Text("暂时保留可见") },
                                onClick = { menuOpen = false; onClassify(UserLabel.UNSORTED) })
                        } else {
                            DropdownMenuItem(text = { Text("恢复到收件箱") },
                                onClick = { menuOpen = false; onRestore() })
                        }
                        if (kindName != null) DropdownMenuItem(text = { Text("这类以后少显示") },
                            onClick = { menuOpen = false; confirmSimilar = true })
                        if (entry.body.isNotBlank()) DropdownMenuItem(text = { Text(if (bodyExpanded) "收起正文" else "展开正文") },
                            onClick = { menuOpen = false; bodyExpanded = !bodyExpanded })
                    }
                }
            }
            if (guideTarget == "star") CoachHint("↑ 点一下星星", "值得留着再看的消息，可以在顶部「关注」里找到。")
            if (entry.title.isNotBlank()) Text(entry.title, style = MaterialTheme.typography.titleMedium,
                maxLines = if (bodyExpanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            if (entry.body.isNotBlank()) Text(entry.body, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (bodyExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis)
            // Teaching controls are visible only during the offline practice. Everyday corrections live in the menu.
            if (!hidden && !showOriginalAction) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onClassify(UserLabel.IMPORTANT_URGENT) },
                        modifier = Modifier.weight(1f).then(emphasis("classify")),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                        Text("这条很重要", style = MaterialTheme.typography.labelLarge)
                    }
                    FilledTonalButton(onClick = { onClassify(UserLabel.FILTERED) },
                        modifier = Modifier.weight(1f).then(emphasis("filter")),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                        Text("这条收起来", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (guideTarget == "classify") CoachHint("↑ 优先查看", "点「这条很重要」，只调整这一条。")
                if (guideTarget == "filter") CoachHint("↑ 试着收起它", "只改这条示例，不影响以后的退款通知。")
            } else if (hidden && !showOriginalAction) {
                FilledTonalButton(onClick = onRestore, modifier = emphasis("restore")) {
                    Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("恢复到收件箱")
                }
                if (guideTarget == "restore") CoachHint("↑ 点一下恢复", "回到收件箱，保持可见。")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { reasonExpanded = !reasonExpanded }, contentPadding = PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.weight(1f, fill = false)) {
                    Icon(Icons.Outlined.Info, contentDescription = if (reasonExpanded) "收起说明" else "为什么这样整理？",
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text(source, style = MaterialTheme.typography.labelMedium)
                }
                if (showOriginalAction) {
                    if (hidden) TextButton(onClick = onRestore) { Text("恢复到收件箱") }
                    else TextButton(onClick = onOpen) {
                        Text("查看原消息"); Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.ArrowOutward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (reasonExpanded) Surface(shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.displayReason(), style = MaterialTheme.typography.bodyMedium)
                    Text(if (entry.hasCurrentCorrection()) "仅调整这一条，可在更多操作中添加长期规则。"
                        else "可在更多操作中纠正；原系统通知不受影响。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.hasCurrentCorrection() && entry.machineReason.isNotBlank()) {
                        Text("此前判断：${entry.machineReason}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
        entry.analysisSource in setOf("rule", "preference") -> "按你的规则"
        entry.effectiveBucket() == DecisionBucket.REVIEW -> "保留可见"
        else -> "本机整理"
    }
}

private fun notificationKindName(kind: String): String? = when (kind) {
    "marketing" -> "营销促销"
    "transaction" -> "交易通知"
    "delivery" -> "物流配送"
    "event" -> "日程提醒"
    "message" -> "聊天消息"
    else -> null
}

private fun formatTime(timestamp: Long): String = DateTimeFormatter.ofPattern("MM月dd日 HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
