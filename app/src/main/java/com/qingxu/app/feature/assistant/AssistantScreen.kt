package com.qingxu.app.feature.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.ui.BrandFace

data class AssistantMessage(val id: String, val text: String, val fromUser: Boolean = false)

data class AssistantProposalItem(
    val id: String,
    val title: String,
    val actionLabel: String,
    val appLabel: String = "",
)

data class AssistantProposal(
    val id: String,
    val title: String = "建议这样整理",
    val items: List<AssistantProposalItem>,
)

data class AssistantUiState(
    val messages: List<AssistantMessage> = emptyList(),
    val draft: String = "",
    val running: Boolean = false,
    val applying: Boolean = false,
    val status: String = "",
    val scopeLabel: String = "",
    val configured: Boolean = false,
    val chatSupported: Boolean = true,
    val hasAuthorizedSources: Boolean = false,
    val concealed: Boolean = false,
    val proposal: AssistantProposal? = null,
    val canUndo: Boolean = false,
    val undoLabel: String = "已应用整理",
)

/** This screen never renders retained message content while the content session is locked. */
@Composable
fun AssistantScreen(
    state: AssistantUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    onUndo: () -> Unit,
    onSettings: () -> Unit,
    onUnlock: () -> Unit,
    onClear: () -> Unit = {},
) {
    if (state.concealed) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            BrandFace(Modifier.size(72.dp))
            Spacer(Modifier.height(20.dp))
            Text("解锁后，继续整理", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onUnlock, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("解锁查看")
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val ready = state.configured && state.chatSupported && state.hasAuthorizedSources
    val busy = state.running || state.applying
    val canSend = ready && !busy && state.proposal == null && state.draft.isNotBlank()
    LaunchedEffect(state.messages.lastOrNull(), state.running, state.proposal?.id, state.canUndo) {
        if (state.messages.isNotEmpty()) {
            withFrameNanos { }
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandFace(Modifier.size(42.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("小耳助手", style = MaterialTheme.typography.titleLarge)
                Text("一起理清收到的消息", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.messages.isNotEmpty()) IconButton(onClick = onClear, enabled = !state.applying) {
                Icon(if (state.running) Icons.Outlined.StopCircle else Icons.Outlined.DeleteOutline,
                    contentDescription = if (state.running) "停止并清空本次对话" else "清空对话")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "助手设置")
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!ready) item(key = "setup") {
                AssistantSetupCard(state, onSettings)
            }
            if (state.messages.isEmpty()) item(key = "welcome") {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("想先整理什么？", style = MaterialTheme.typography.headlineSmall)
                    Text("告诉我你的想法，改动会先给你确认。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("现在先看什么", "把促销收起来", "找出需要回复的消息").forEach { prompt ->
                        OutlinedButton(onClick = { onDraftChange(prompt) },
                            enabled = !busy && state.proposal == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(16.dp)) {
                            Text(prompt, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            items(state.messages, key = { "message:${it.id}" }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement =
                    if (message.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(Modifier.widthIn(max = 340.dp), shape = RoundedCornerShape(
                        topStart = 20.dp, topEnd = 20.dp,
                        bottomStart = if (message.fromUser) 20.dp else 6.dp,
                        bottomEnd = if (message.fromUser) 6.dp else 20.dp),
                        color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text(message.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (state.running) item(key = "thinking") {
                Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在整理，请稍等…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (state.applying) item(key = "applying") {
                Text("正在处理…", Modifier.padding(horizontal = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (state.status.isNotBlank()) item(key = "status") {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(state.status, Modifier.fillMaxWidth().padding(14.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.proposal?.let { proposal ->
                item(key = "proposal:${proposal.id}") {
                    ProposalCard(proposal, !busy, onApply, onDismiss)
                }
            }
            if (state.canUndo) item(key = "undo") {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.undoLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onUndo, enabled = !busy && state.proposal == null) { Text("撤销上次整理") }
                }
            }
        }
        Surface(tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready && state.scopeLabel.isNotBlank()) {
                    Text("发送范围 · ${state.scopeLabel}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = state.draft, onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f), enabled = !busy && state.proposal == null,
                        placeholder = { Text(if (state.proposal == null) "说说你想怎么整理" else "先确认或取消这次建议") },
                        minLines = 1, maxLines = 4, shape = RoundedCornerShape(22.dp))
                    FilledIconButton(onClick = { if (canSend) onSend() }, enabled = canSend,
                        modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送给小耳")
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantSetupCard(state: AssistantUiState, onSettings: () -> Unit) {
    val (title, action) = when {
        !state.chatSupported -> "当前服务暂不支持对话" to "更换判断服务"
        !state.configured -> "先连接你的 AI 服务" to "配置服务"
        else -> "还没有允许分析的消息来源" to "选择分析范围"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(when {
                !state.chatSupported -> "Jev 用于消息判断，对话需要支持聊天的服务。"
                !state.configured -> "连接后就能用对话查找、归纳和整理消息。"
                else -> "只分析你明确允许的来源，仅本机消息不会发送。"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(action) }
        }
    }
}

@Composable
private fun ProposalCard(proposal: AssistantProposal, enabled: Boolean, onApply: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(proposal.title, style = MaterialTheme.typography.titleMedium)
            proposal.items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.appLabel.isNotBlank()) Text(item.appLabel, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.title.ifBlank { "未命名通知" }, style = MaterialTheme.typography.bodyLarge)
                    Text(item.actionLabel, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
                Button(onClick = onApply, enabled = enabled && proposal.items.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("确认应用") }
            }
        }
    }
}
