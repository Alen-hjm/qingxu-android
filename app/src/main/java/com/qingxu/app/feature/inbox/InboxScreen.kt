package com.qingxu.app.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.effectiveBucket
import com.qingxu.app.core.ui.BrandFace
import com.qingxu.app.core.ui.NotificationCard
import com.qingxu.app.core.ui.QuietState
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.feature.QingxuUiState

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
) {
    var onlyInterested by rememberSaveable { mutableStateOf(false) }
    val inbox = state.entries.filter { it.effectiveBucket() != DecisionBucket.HIDDEN }
    val visible = if (onlyInterested) inbox.filter { it.interested } else inbox
    val groups = visible.groupBy { it.effectiveBucket() }
    val nowCount = inbox.count { it.effectiveBucket() == DecisionBucket.NOW }
    val hiddenCount = state.entries.size - inbox.size
    val order = listOf(DecisionBucket.NOW, DecisionBucket.LATER, DecisionBucket.REVIEW)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(20.dp),
    ) {
        if (inbox.isNotEmpty()) item(key = "focus-header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AutomaticFocusHeader(nowCount, hiddenCount)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onlyInterested, onClick = { onlyInterested = false },
                        label = { Text("全部 ${inbox.size}") })
                    FilterChip(selected = onlyInterested, onClick = { onlyInterested = true },
                        label = { Text("关注 ${inbox.count { it.interested }}") })
                }
            }
        }
        if (tutorial != null) item(key = "tutorial") { tutorial() }
        if ((!state.permissionGranted || state.selectedPackages.isEmpty()) && inbox.isNotEmpty()) item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (!state.permissionGranted) "通知访问未开启" else "尚未选择消息来源")
                Button(onClick = onSettings) { Text(if (state.selectedPackages.isEmpty()) "选择我的应用" else "开启通知访问") }
            }
        }
        if (state.permissionGranted && !state.listenerConnected && state.selectedPackages.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("等待系统连接", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSettings) { Text("重新连接") }
            }
        }
        if (state.captureFailed) item {
            Text("最近一条通知保存失败，请检查手机存储空间并重新打开轻序。",
                color = MaterialTheme.colorScheme.error)
        }
        if (visible.isEmpty() && tutorial == null) item {
            val needsSetup = !state.permissionGranted || state.selectedPackages.isEmpty()
            QuietState(if (state.loading) "正在读取…" else if (onlyInterested) "把喜欢的，留在这里" else "把整理交给轻序",
                if (onlyInterested) "点亮消息上的星星，就能在这里找到。"
                else if (needsSetup) "选好消息来源，开始自动整理。"
                else "新消息来了，会自动整理到这里。",
                action = if (state.selectedPackages.isEmpty()) "选择我的应用" else if (!state.permissionGranted) "开启通知访问" else null,
                onAction = onSettings)
        }
        order.forEach { bucket ->
            val entries = groups[bucket].orEmpty().sortedByDescending { it.postedAt }
            if (entries.isNotEmpty()) {
                item(key = "group:${bucket.name}") {
                    Text("${bucketTitle(bucket)} · ${entries.size}", Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
                items(entries, key = { it.notificationKey }) { entry ->
                    Box(Modifier.animateItem()) {
                        NotificationCard(entry, { onClassify(entry, it) }, { onInterest(entry) },
                            onRestore = {}, onOpen = { onOpen(entry) }, onHideSimilar = { onHideSimilar(entry) },
                            concealContent = concealContent, onUnlock = onUnlock)
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomaticFocusHeader(nowCount: Int, hiddenCount: Int) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.surface)))
        .padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (nowCount > 0) "$nowCount 条值得先看" else "重要的，先看到", style = MaterialTheme.typography.headlineSmall)
            if (hiddenCount > 0) Text("已收起 $hiddenCount 条", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        BrandFace(Modifier.size(56.dp))
    }
}

private fun bucketTitle(bucket: DecisionBucket): String = when (bucket) {
    DecisionBucket.NOW -> "现在看"
    DecisionBucket.LATER -> "稍后看"
    DecisionBucket.REVIEW -> "待确认"
    DecisionBucket.HIDDEN -> "已收起"
}
