package com.qingxu.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.privacy.PrivacySettings
import com.qingxu.app.core.ui.AppAvatar
import com.qingxu.app.feature.SourceApp

@Composable
fun PrivacySettingsScreen(settings: PrivacySettings, apps: List<SourceApp>, selected: Set<String>, status: String,
    onSave: (Boolean, Boolean, Set<String>, Int, Boolean) -> Unit, onDeleteAll: () -> Unit, cloudEnabled: Boolean = false) {
    var hideContent by rememberSaveable(settings.revision) { mutableStateOf(settings.hideContent) }
    var preventScreenshots by rememberSaveable(settings.revision) { mutableStateOf(settings.preventScreenshots) }
    var blockWhenLocked by rememberSaveable(settings.revision) { mutableStateOf(settings.blockCloudWhenLocked) }
    var localOnly by remember(settings.revision) { mutableStateOf(settings.localOnlyPackages) }
    var retentionDays by rememberSaveable(settings.revision) { mutableIntStateOf(settings.retentionDays) }
    var confirmDeletion by rememberSaveable { mutableStateOf(false) }
    var showRetention by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showCloudPrivacy by rememberSaveable { mutableStateOf(false) }
    val dirty = hideContent != settings.hideContent || blockWhenLocked != settings.blockCloudWhenLocked ||
        localOnly != settings.localOnlyPackages || retentionDays != settings.retentionDays ||
        preventScreenshots != settings.preventScreenshots
    val retentionOptions = listOf(7 to "7 天", 30 to "30 天", 0 to "不自动清理")

    if (confirmDeletion) AlertDialog(
        onDismissRequest = { confirmDeletion = false },
        title = { Text("删除轻序内的全部历史？") },
        text = { Text("通知记录和整理结果将从本机删除，无法恢复。其他应用的消息与系统通知不受影响。") },
        confirmButton = { TextButton(onClick = { confirmDeletion = false; onDeleteAll() }) {
            Text("确认删除历史", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDeletion = false }) { Text("保留历史") } },
    )

    if (showRetention) AlertDialog(
        onDismissRequest = { showRetention = false },
        title = { Text("历史保留") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("保存设置后，会清理已过期的本机记录。", style = MaterialTheme.typography.bodyMedium)
                retentionOptions.forEach { (days, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(retentionDays == days, role = Role.RadioButton,
                            onClick = { retentionDays = days; showRetention = false }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = retentionDays == days, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showRetention = false }) { Text("取消") } },
    )

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Outlined.Lock, contentDescription = null,
                            modifier = Modifier.padding(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("隐私保护", style = MaterialTheme.typography.headlineSmall)
                        Text(when {
                            dirty -> "有修改待保存"
                            settings.hideContent -> "应用锁已开启"
                            settings.preventScreenshots -> "截图保护已开启"
                            else -> "按需开启，日常直接查看"
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    SettingSwitch("应用锁", "应用锁", "可选。优先用指纹等生物识别，无可用方式时使用系统锁。",
                        hideContent, { hideContent = it })
                }
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    SettingSwitch("防止截图与录屏", "防止截图与录屏", "独立控制截图、录屏和最近任务预览，不影响应用锁或云端上传。",
                        preventScreenshots, { preventScreenshots = it })
                }
            }
            item {
                Surface(onClick = { showRetention = true }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("历史保留", style = MaterialTheme.typography.titleMedium)
                            Text(retentionOptions.first { it.first == retentionDays }.second,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                    }
                }
            }
            item {
                Surface(onClick = { showCloudPrivacy = !showCloudPrivacy }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("云端隐私", style = MaterialTheme.typography.titleMedium)
                            Text(if (cloudEnabled) "云端已启用 · 查看上传限制" else "云端未启用 · 查看上传限制",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (showCloudPrivacy) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (showCloudPrivacy) "收起云端隐私" else "展开云端隐私")
                    }
                }
            }
            if (showCloudPrivacy) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        SettingSwitch("锁屏时不上传", "锁屏时阻止云端分析", "锁屏后暂停新的云端分析。",
                            blockWhenLocked, { blockWhenLocked = it })
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("禁止上传的应用", style = MaterialTheme.typography.titleMedium)
                        Text("开启后仅本机处理，优先于云端授权。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (selected.isEmpty()) Text("选择消息来源后，可在这里调整。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(apps.filter { it.packageName in selected }, key = { it.packageName }) { app ->
                    val checked = app.packageName in localOnly
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                            .toggleable(checked, role = Role.Switch, onValueChange = { enabled ->
                                localOnly = if (enabled) localOnly + app.packageName else localOnly - app.packageName
                            }).semantics { contentDescription = "${app.label}仅本机处理" }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppAvatar(app.packageName, app.label)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium)
                                Text(if (checked) "不上传" else "上传需另行授权",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
            item {
                Column {
                    TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("了解隐私保护", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Icon(if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (showDetails) "收起说明" else "展开说明")
                    }
                    AnimatedVisibility(showDetails) {
                        Column(Modifier.testTag("privacy-details").padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PrivacyDetail("应用锁", "新安装默认关闭。开启时需验证身份；持续在前台使用不会超时，短暂切换应用可在 60 秒内返回，锁屏立即锁定。没有可用的生物识别时用系统锁；未设置系统锁需先设置。")
                            PrivacyDetail("截图与录屏", "新安装默认关闭，与应用锁分别保存。开启后请求系统限制截图、录屏和最近任务预览，实际效果取决于设备。")
                            PrivacyDetail("锁屏与云端", "锁屏限制新的分析，已发出的请求无法撤回。Jev 等服务在云端运行。")
                            PrivacyDetail("本机整理", "仅本机来源使用规则，尚未接入端侧 AI。关闭仅本机限制，不会自动允许上传。")
                            PrivacyDetail("本机保存", "通知正文加密保存；应用包名、时间和分类等元数据仍以明文保存。")
                        }
                    }
                    TextButton(onClick = { confirmDeletion = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("删除全部本机历史", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { onSave(hideContent, blockWhenLocked, localOnly, retentionDays, preventScreenshots) },
                    enabled = dirty, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("保存设置") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, supportingText: String,
    checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp)
        .toggleable(checked, role = Role.Switch, onValueChange = onChecked)
        .semantics { contentDescription = description }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(supportingText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun PrivacyDetail(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
