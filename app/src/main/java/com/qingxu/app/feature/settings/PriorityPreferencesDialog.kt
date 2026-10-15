package com.qingxu.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.SenderPreferencePolicy
import com.qingxu.app.data.local.SenderPriorityRule

@Composable
fun PriorityPreferencesDialog(rules: List<SenderPriorityRule>, entries: List<NotificationEntry>,
    onRemove: (SenderPriorityRule) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("会话优先") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("偏好保存在本机。相同会话标题可能重名，可随时撤销。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(rules, key = { it.packageName + it.scopeHash }) { rule ->
                    val example = entries.firstOrNull { it.packageName == rule.packageName && SenderPreferencePolicy.senderScope(it) == rule.scopeHash }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(example?.title ?: "已保存的会话", style = MaterialTheme.typography.titleSmall)
                            Text(example?.appLabel ?: "历史内容已清理", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onRemove(rule) }) { Text("撤销") }
                    }
                }
            }
            if (rules.isEmpty()) Text("没有已保存的会话偏好")
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
