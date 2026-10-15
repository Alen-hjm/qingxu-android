package com.qingxu.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qingxu.app.feature.QingxuUiState
import com.qingxu.app.notification.SurfaceSettings

/** Everyday choices only. Collection and optional service configuration have their own pages. */
@Composable
fun SettingsScreen(
    state: QingxuUiState,
    onSources: () -> Unit,
    onPrivacySettings: () -> Unit,
    onAdvancedSettings: () -> Unit,
    onRestartGuide: () -> Unit,
    hideContent: Boolean = true,
    guideStep: Int? = null,
    surfaceSettings: SurfaceSettings = SurfaceSettings(),
    onSurfaceChange: (Boolean, Boolean) -> Unit = { _, _ -> },
    priorityCount: Int = 0,
    onManagePriority: () -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    SettingsDestination("整理哪些应用", when {
                        state.selectedPackages.isEmpty() -> "选择想整理的应用"
                        !state.permissionGranted -> "${state.selectedPackages.size} 个应用 · 需开启通知访问"
                        !state.listenerConnected && state.listenerRecovering -> "${state.selectedPackages.size} 个应用 · 正在恢复连接"
                        !state.listenerConnected -> "${state.selectedPackages.size} 个应用 · 连接已断开"
                        else -> "${state.selectedPackages.size} 个应用 · 已连接"
                    }, Icons.Outlined.Apps, onSources, highlighted = guideStep == 0 || guideStep == 1)
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    SettingsDestination("隐私保护", if (hideContent) "解锁后查看消息" else "消息内容直接显示",
                        Icons.Outlined.Lock, onPrivacySettings)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("通知栏摘要", style = MaterialTheme.typography.titleSmall)
                            Text("静默显示优先消息数量，点开即看", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(surfaceSettings.summary, { onSurfaceChange(it, surfaceSettings.removePromotions) })
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("促销移出通知栏", style = MaterialTheme.typography.titleSmall)
                            Text("仅确认的纯促销；内容仍在已收起中", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(surfaceSettings.removePromotions, { onSurfaceChange(surfaceSettings.summary, it) })
                    }
                    if (surfaceSettings.removePromotions) Text("原通知先到达系统，移除无法消除已响过的提示音。恢复列表内容不会重新发出原通知。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (priorityCount > 0) item {
            SettingsDestination("会话优先", "已记住 $priorityCount 个会话 · 管理与撤销", Icons.Outlined.Apps, onManagePriority)
        }
        item {
            Column {
                TextButton(onClick = onRestartGuide, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("重新体验操作引导", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onAdvancedSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .semantics { contentDescription = "高级设置" }) {
                    Text("高级设置", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsDestination(title: String, subtitle: String, icon: ImageVector,
    onClick: () -> Unit, highlighted: Boolean = false) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 84.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(12.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
