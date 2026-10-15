package com.qingxu.app.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.ui.AppAvatar
import com.qingxu.app.feature.QingxuUiState
import com.qingxu.app.feature.onboarding.CoachHint

@Composable
fun SourceSettingsScreen(state: QingxuUiState, onPermissionSettings: () -> Unit,
    onSelectSource: (String, Boolean) -> Unit, guideStep: Int? = null,
    requiresUnlock: Boolean = false, onUnlock: () -> Unit = {}, onReconnect: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showConnectionHelp by rememberSaveable { mutableStateOf(false) }
    val apps = state.sourceApps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(guideStep) {
        if (guideStep == 1) { keyboard?.hide(); listState.animateScrollToItem(0) }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (guideStep != 0) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("通知访问", style = MaterialTheme.typography.titleMedium)
                            Text(when {
                                !state.permissionGranted -> "尚未开启"
                                !state.listenerConnected && state.listenerRecovering -> "正在恢复通知连接…"
                                !state.listenerConnected -> "通知连接已断开"
                                else -> "已连接"
                            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = if (state.permissionGranted && !state.listenerConnected) onReconnect else onPermissionSettings,
                            enabled = !state.permissionGranted || state.listenerConnected || !state.listenerRecovering,
                            modifier = if (guideStep == 1)
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)).padding(3.dp)
                            else Modifier) {
                            Text(if (!state.permissionGranted) "去开启" else if (!state.listenerConnected) "重新连接" else "管理")
                        }
                    }
                    if (!state.permissionGranted) Text("授权后读取通知，只保存你选中的应用。", style = MaterialTheme.typography.bodySmall)
                    if (guideStep == 1) CoachHint("↑ 开启通知访问", "在系统设置中允许「轻序」，然后返回这里。")
                    if (state.permissionGranted) {
                        TextButton(onClick = { showConnectionHelp = !showConnectionHelp }, contentPadding = PaddingValues(0.dp)) {
                            Text("连接帮助")
                            Icon(if (showConnectionHelp) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                        }
                        if (showConnectionHelp) {
                            Text("重连后仍收不到消息，可在系统通知使用权中将轻序关闭再开启，然后返回这里。",
                                style = MaterialTheme.typography.bodySmall)
                            Text("在手机的电池或后台管理中，允许轻序后台运行，避免被省电策略暂停。不同手机的设置名称可能不同。",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onPermissionSettings) { Text("打开系统通知设置") }
                        }
                    }
                }
            }
        }
        item {
            Text("已选择 ${state.selectedPackages.size} 个应用", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (guideStep == 0) item {
            CoachHint(if (requiresUnlock) "↓ 先解锁，再选择应用" else "↓ 选一个常用应用",
                if (requiresUnlock) "验证手机身份后，就能选择要整理的应用。" else "打开右侧开关，只收纳你选中的来源。")
        }
        if (requiresUnlock) item {
            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("解锁后选择应用")
            }
        }
        item {
            OutlinedTextField(query, { query = it }, label = { Text("搜索应用") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (apps.isEmpty()) item { Text(if (state.loading) "正在读取应用…" else "没有匹配的应用") }
        items(apps, key = { it.packageName }) { app ->
            val selected = app.packageName in state.selectedPackages
            val rowColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface, label = "source-selection")
            Surface(Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(20.dp), color = rowColor) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppAvatar(app.packageName, app.label)
                    Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(selected, onCheckedChange = { onSelectSource(app.packageName, it) },
                        enabled = !requiresUnlock || selected,
                        modifier = Modifier.semantics { contentDescription = "整理${app.label}的通知" })
                }
            }
        }
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showHelp = !showHelp }) {
                    Text("收纳与通知说明")
                    Icon(if (showHelp) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (showHelp) {
                    Text("关闭某个来源后停止采集，已保存的通知仍保留。关闭系统通知访问可停止全部监听。")
                    Text("轻序只整理内部列表，保留原系统通知，不追加提醒。只能读取系统提供的正文。")
                    Text("默认在本机处理。云端分析需另行开启，并逐个允许来源。")
                }
            }
        }
    }
}
