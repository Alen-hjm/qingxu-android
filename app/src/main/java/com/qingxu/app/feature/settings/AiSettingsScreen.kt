package com.qingxu.app.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.ai.AiProvider
import com.qingxu.app.core.ui.AppAvatar
import com.qingxu.app.data.local.SourceKindRule
import com.qingxu.app.feature.SourceApp

@Composable
fun AiSettingsScreen(settings: AiSettings, apps: List<SourceApp>, selected: Set<String>, status: String,
    rules: List<SourceKindRule>, onSave: (String, String, String?, Boolean, Set<String>, String, AiProvider) -> Unit,
    onTest: () -> Unit, onClearKey: () -> Unit, onReanalyze: () -> Unit, onRemoveRule: (SourceKindRule) -> Unit,
    onPrivacySettings: () -> Unit = {}) {
    var endpoint by rememberSaveable(settings.revision) { mutableStateOf(settings.endpoint) }
    var model by rememberSaveable(settings.revision) { mutableStateOf(settings.model) }
    // Never persist draft secrets in saved instance state.
    var key by remember(settings.revision) { mutableStateOf("") }
    var enabled by rememberSaveable(settings.revision) { mutableStateOf(settings.enabled) }
    var allowed by remember(settings.revision) { mutableStateOf(settings.allowedPackages) }
    var interests by rememberSaveable(settings.revision) { mutableStateOf(settings.interests) }
    var provider by rememberSaveable(settings.revision) { mutableStateOf(settings.provider) }
    val configured = settings.endpoint.isNotBlank() && settings.model.isNotBlank() && settings.hasKey
    var editingService by rememberSaveable(settings.revision) { mutableStateOf(!configured) }
    var serviceDetails by rememberSaveable { mutableStateOf(false) }
    var dataDetails by rememberSaveable { mutableStateOf(false) }
    var preferencesExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("云端分析（可选）", style = MaterialTheme.typography.headlineSmall)
                Text("无需配置，也会按本机规则整理。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(if (settings.enabled) "本机规则 · 云端已开启" else "本机规则已启用 · 云端未开启",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                TextButton(onClick = onPrivacySettings, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("隐私与本机处理")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("判断服务", style = MaterialTheme.typography.titleMedium)
                            Text(if (configured) "${providerLabel(settings.provider)} · ${settings.model}" else "连接你的 AI 服务",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (configured) TextButton(onClick = { editingService = !editingService }) {
                            Text(if (editingService) "收起配置" else "编辑服务")
                        }
                    }
                    if (editingService) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = provider == AiProvider.CHAT_COMPLETIONS,
                                onClick = { if (provider != AiProvider.CHAT_COMPLETIONS) { provider = AiProvider.CHAT_COMPLETIONS; key = "" } },
                                modifier = Modifier.heightIn(min = 48.dp), label = { Text("兼容接口") })
                            FilterChip(selected = provider == AiProvider.JEV,
                                onClick = { if (provider != AiProvider.JEV) { provider = AiProvider.JEV; key = "" } },
                                modifier = Modifier.heightIn(min = 48.dp), label = { Text("Jev") })
                        }
                        if (provider != settings.provider) Text("更换服务后，请核对地址并填写新密钥。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("完整 HTTPS 接口地址") }, placeholder = { Text(if (provider == AiProvider.JEV)
                                "https://api.typesafe.ai/v1/systemone" else "https://你的服务/v1/chat/completions") })
                        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("模型名称") }, placeholder = { if (provider == AiProvider.JEV) Text("jev-latest") })
                        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(if (settings.hasKey && provider == settings.provider) "密钥已保存，留空保留" else "API 密钥") },
                            visualTransformation = PasswordVisualTransformation())
                    }
                    OutlinedButton(onClick = onTest, enabled = configured,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("测试连接") }
                    Text("用虚构示例测试已保存的配置。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AiDisclosure("服务说明", serviceDetails, { serviceDetails = !serviceDetails })
                    if (serviceDetails) {
                        Text(if (provider == AiProvider.JEV)
                            "Jev 同样通过云端判断。官方接口：https://api.typesafe.ai/v1/systemone；模型可填 jev-latest。"
                            else "填写兼容 Chat Completions 的完整 HTTPS 接口与模型名称。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("密钥在本机加密保存。更换服务不会自动授权上传，修改需要保存后生效。",
                            style = MaterialTheme.typography.bodySmall)
                        if (settings.hasKey) TextButton(onClick = onClearKey) { Text("移除密钥并关闭云端") }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("允许上传的应用", style = MaterialTheme.typography.titleMedium)
                Text("勾选应用的名称、通知标题、正文、时间及关注内容会发送到云端。",
                    style = MaterialTheme.typography.bodyMedium)
                Text("「仅本机」设置优先，仍不会上传。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                if (selected.isEmpty()) Text("先到「设置 → 整理哪些应用」选择消息来源。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(apps.filter { it.packageName in selected }, key = { it.packageName }) { app ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppAvatar(app.packageName, app.label)
                Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Checkbox(app.packageName in allowed,
                    { checked -> allowed = if (checked) allowed + app.packageName else allowed - app.packageName },
                    modifier = Modifier.semantics { contentDescription = "允许云端分析${app.label}的通知" })
            }
        }
        item {
            HorizontalDivider()
            AiDisclosure("关注与偏好", preferencesExpanded, { preferencesExpanded = !preferencesExpanded })
        }
        if (preferencesExpanded) {
            item {
                OutlinedTextField(interests, { interests = it.take(300) }, Modifier.fillMaxWidth(),
                    label = { Text("关注的内容（可选）") }, placeholder = { Text("摄影，退款，设计活动") },
                    supportingText = { Text("用逗号分隔，保存后生效。") })
            }
            item {
                Text("少显示这些内容", style = MaterialTheme.typography.titleMedium)
                Text(if (rules.isEmpty()) "在消息卡片选择“这类以后少显示”即可添加。"
                    else "只对所选应用与类型生效，单条纠正优先。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rules, key = { it.packageName + ":" + it.kind }) { rule ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val name = apps.firstOrNull { it.packageName == rule.packageName }?.label ?: rule.packageName
                    val kind = mapOf("marketing" to "营销", "transaction" to "交易", "delivery" to "物流", "event" to "日程", "message" to "消息")[rule.kind] ?: rule.kind
                    Text("$name · $kind", Modifier.weight(1f))
                    TextButton(onClick = { onRemoveRule(rule) }) { Text("撤销") }
                }
            }
            item {
                TextButton(onClick = onReanalyze, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("重新整理已收纳消息")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用云端分析", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(enabled, { enabled = it }, modifier = Modifier.semantics { contentDescription = "启用云端分析" })
                }
                Text("开启并保存后，允许分析勾选应用的历史与后续通知。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AiDisclosure("数据说明", dataDetails, { dataDetails = !dataDetails })
                if (dataDetails) {
                    Text("新来源默认不上传。常见验证码留在本机，但文字识别无法覆盖所有敏感内容；云端服务方按其政策处理数据。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("本机目前使用规则，尚未接入端侧 AI。无法判断的通知保留可见。只整理轻序内的列表，系统原通知保留。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { onSave(endpoint, model, key.ifBlank { null }, enabled, allowed.intersect(selected), interests, provider); key = "" },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (enabled) "保存并启用" else "保存设置")
                }
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun providerLabel(provider: AiProvider) = if (provider == AiProvider.JEV) "Jev" else "兼容接口"

@Composable
private fun AiDisclosure(title: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(vertical = 8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起$title" else "展开$title")
    }
}
