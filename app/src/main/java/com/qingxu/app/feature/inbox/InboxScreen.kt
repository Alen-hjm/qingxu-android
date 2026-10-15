package com.qingxu.app.feature.inbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.effectiveBucket
import com.qingxu.app.core.decision.displayBucket
import com.qingxu.app.core.decision.isConfidentlyOrganized
import com.qingxu.app.core.ui.BrandFace
import com.qingxu.app.core.ui.NotificationCard
import com.qingxu.app.core.ui.QuietState
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.feature.QingxuUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun InboxScreen(
    state: QingxuUiState,
    onSettings: () -> Unit,
    onClassify: (NotificationEntry, UserLabel) -> Unit,
    onInterest: (NotificationEntry) -> Unit,
    onOpen: (NotificationEntry) -> Unit,
    tutorial: (@Composable () -> Unit)? = null,
    onHideSimilar: (NotificationEntry) -> Unit = {},
    concealContent: Boolean = false,
    onUnlock: () -> Unit = {},
    onReconnect: () -> Unit = {},
    onHandled: (NotificationEntry, Boolean) -> Unit = { _, _ -> },
    onPrioritizeSender: (NotificationEntry) -> Unit = {},
    onTestNotification: () -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf("pending") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showOtherHelp by rememberSaveable { mutableStateOf(false) }
    val inbox = state.entries.filter { it.handledAt == null && it.effectiveBucket() != DecisionBucket.HIDDEN }
    val handled = state.entries.filter { it.handledAt != null }
    val filtered = when (filter) {
        "interested" -> inbox.filter { it.interested }
        "handled" -> handled
        else -> inbox
    }
    val visible = if (concealContent || query.isBlank()) filtered else filtered.filter {
        listOf(it.appLabel, it.title, it.body).any { text -> text.contains(query.trim(), ignoreCase = true) }
    }
    val groups = visible.groupBy { it.displayBucket() }
    val nowCount = inbox.count { it.displayBucket() == DecisionBucket.NOW }
    val organized = state.entries.filter { it.isConfidentlyOrganized() }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        if (state.entries.isNotEmpty()) item(key = "focus-header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AutomaticFocusHeader(nowCount, organized.size, organized.count { it.effectiveBucket() == DecisionBucket.HIDDEN })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = filter == "pending", onClick = { filter = "pending" }, label = { Text("待看 ${inbox.size}") })
                        FilterChip(selected = filter == "interested", onClick = { filter = "interested" }, label = { Text("关注 ${inbox.count { it.interested }}") })
                        FilterChip(selected = filter == "handled", onClick = { filter = "handled" }, label = { Text("已处理 ${handled.size}") })
                    }
                    if (!concealContent) IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) query = "" }) {
                        Icon(if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = if (searchExpanded) "收起搜索" else "搜索消息")
                    }
                }
                AnimatedVisibility(searchExpanded && !concealContent) {
                    OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索消息或应用") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                }
            }
        }
        if (tutorial != null) item(key = "tutorial") { tutorial() }
        if ((!state.permissionGranted || state.selectedPackages.isEmpty()) && inbox.isNotEmpty()) item(key = "setup") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (!state.permissionGranted) "通知访问未开启" else "尚未选择消息来源", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onSettings) { Text(if (state.selectedPackages.isEmpty()) "选择应用" else "去开启") }
            }
        }
        if (state.permissionGranted && state.selectedPackages.isNotEmpty()) item(key = "receiving-health") {
            ReceivingHealth(state, onReconnect, onSettings, onTestNotification)
        }
        if (state.captureFailed) item(key = "capture-failed") {
            Text("最近一条保存失败，请检查存储空间。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (visible.isEmpty() && tutorial == null) item(key = "empty") {
            val needsSetup = !state.permissionGranted || state.selectedPackages.isEmpty()
            QuietState(when {
                state.loading -> "正在读取…"
                query.isNotBlank() && !concealContent -> "没有找到这条消息"
                filter == "handled" -> "还没有已处理的消息"
                filter == "interested" -> "把喜欢的，留在这里"
                else -> "把整理交给轻序"
            }, when {
                query.isNotBlank() && !concealContent -> "试试其他关键词。"
                filter == "handled" -> "处理完点一下，待看列表更清爽。"
                filter == "interested" -> "点亮消息上的星星，就能在这里找到。"
                needsSetup -> "选好消息来源，开始自动整理。"
                !state.listenerConnected -> "连接恢复后，才能接收新消息。"
                else -> "新消息来了，会自动整理到这里。"
            }, action = if (state.selectedPackages.isEmpty()) "选择我的应用" else if (!state.permissionGranted) "开启通知访问" else null,
                onAction = onSettings)
        }
        if (filter == "handled") {
            items(visible.sortedByDescending { it.handledAt }, key = { it.notificationKey }) { entry ->
                Box(Modifier.animateItem()) {
                    InboxCard(entry, onClassify, onInterest, onOpen, onHideSimilar, concealContent, onUnlock, onHandled, onPrioritizeSender)
                }
            }
        } else listOf(DecisionBucket.NOW, DecisionBucket.LATER, DecisionBucket.REVIEW).forEach { bucket ->
            val entries = groups[bucket].orEmpty().sortedByDescending { it.postedAt }
            if (entries.isNotEmpty()) {
                item(key = "group:${bucket.name}") {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${bucketTitle(bucket)} · ${entries.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                color = if (bucket == DecisionBucket.NOW) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (bucket == DecisionBucket.REVIEW) IconButton(onClick = { showOtherHelp = !showOtherHelp }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Info, contentDescription = if (showOtherHelp) "收起其他消息说明" else "其他消息说明",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                        if (bucket == DecisionBucket.REVIEW && showOtherHelp) Text("拿不准的先保留，不必逐条分类。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(entries, key = { it.notificationKey }) { entry ->
                    Box(Modifier.animateItem()) {
                        InboxCard(entry, onClassify, onInterest, onOpen, onHideSimilar, concealContent, onUnlock, onHandled, onPrioritizeSender)
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxCard(entry: NotificationEntry, onClassify: (NotificationEntry, UserLabel) -> Unit,
    onInterest: (NotificationEntry) -> Unit, onOpen: (NotificationEntry) -> Unit,
    onHideSimilar: (NotificationEntry) -> Unit, concealContent: Boolean, onUnlock: () -> Unit,
    onHandled: (NotificationEntry, Boolean) -> Unit, onPrioritizeSender: (NotificationEntry) -> Unit) {
    NotificationCard(entry, { onClassify(entry, it) }, { onInterest(entry) }, onRestore = {}, onOpen = { onOpen(entry) },
        onHideSimilar = { onHideSimilar(entry) }, concealContent = concealContent, onUnlock = onUnlock,
        onHandled = { onHandled(entry, entry.handledAt == null) }, onPrioritizeSender = { onPrioritizeSender(entry) })
}

@Composable
private fun ReceivingHealth(state: QingxuUiState, onReconnect: () -> Unit, onSettings: () -> Unit, onTest: () -> Unit) {
    val testFailed = !state.deliveryTestRunning && state.deliveryTestReceivedAt == null && state.deliveryTestMessage.isNotBlank()
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                state.listenerRecovering -> "正在恢复通知连接…"
                !state.listenerConnected -> "通知连接已断开"
                state.deliveryTestRunning -> "等待测试消息…"
                state.deliveryTestReceivedAt != null -> "测试消息已收到"
                testFailed -> "接收测试未完成"
                state.lastReceivedAt != null -> "已连接 · 最近 ${formatReceivedTime(state.lastReceivedAt)}"
                else -> "已连接 · 等待新消息"
            }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = if (!state.listenerConnected || testFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.listenerConnected) TextButton(onClick = onReconnect, enabled = !state.listenerRecovering) { Text("重新连接") }
            else TextButton(onClick = onTest, enabled = !state.deliveryTestRunning) { Text("测试接收") }
        }
        if (testFailed) Text(state.deliveryTestMessage, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.listenerConnected || testFailed) TextButton(onClick = onSettings, contentPadding = PaddingValues(0.dp)) { Text("连接帮助") }
    }
}

@Composable
private fun AutomaticFocusHeader(nowCount: Int, automaticCount: Int, automaticHiddenCount: Int) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface)))
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (nowCount > 0) "$nowCount 条现在看" else "暂时没有急事", style = MaterialTheme.typography.titleLarge)
            if (automaticCount > 0) Text("自动整理 $automaticCount · 收起 $automaticHiddenCount",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BrandFace(Modifier.size(42.dp))
    }
}

private fun bucketTitle(bucket: DecisionBucket): String = when (bucket) {
    DecisionBucket.NOW -> "现在看"
    DecisionBucket.LATER -> "稍后看"
    DecisionBucket.REVIEW -> "其他消息"
    DecisionBucket.HIDDEN -> "已收起"
}

private fun formatReceivedTime(timestamp: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
