package com.qingxu.app.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.ai.AiProvider
import com.qingxu.app.core.ai.AiEndpointMode
import com.qingxu.app.core.ai.AiAuthMode
import com.qingxu.app.core.ai.AiConnectionDraft
import com.qingxu.app.core.ai.AiConnectionTestStatus
import com.qingxu.app.core.ai.canReuseSavedKey
import com.qingxu.app.core.ai.endpointAuthority
import com.qingxu.app.core.ai.resolveAiEndpoint
import com.qingxu.app.core.ui.AppAvatar
import com.qingxu.app.data.local.SourceKindRule
import com.qingxu.app.feature.SourceApp

@Composable
fun AiSettingsScreen(settings: AiSettings, apps: List<SourceApp>, selected: Set<String>, status: String,
    rules: List<SourceKindRule>, onSave: (String, String, String?, Boolean, Set<String>, String, AiProvider, AiEndpointMode, AiAuthMode) -> Unit,
    onTest: (AiConnectionDraft) -> Unit, onClearKey: () -> Unit, onReanalyze: () -> Unit, onRemoveRule: (SourceKindRule) -> Unit,
    onPrivacySettings: () -> Unit = {}, testStatus: AiConnectionTestStatus = AiConnectionTestStatus(),
    onConnectionChanged: () -> Unit = {}) {
    val latestOnConnectionChanged by rememberUpdatedState(onConnectionChanged)
    DisposableEffect(Unit) {
        onDispose { latestOnConnectionChanged() }
    }
    var endpoint by rememberSaveable(settings.revision) { mutableStateOf(settings.endpoint) }
    var model by rememberSaveable(settings.revision) { mutableStateOf(settings.model) }
    // Never persist draft secrets in saved instance state.
    var key by remember(settings.revision) { mutableStateOf("") }
    var enabled by rememberSaveable(settings.revision) { mutableStateOf(settings.enabled) }
    var allowed by remember(settings.revision) { mutableStateOf(settings.allowedPackages) }
    var interests by rememberSaveable(settings.revision) { mutableStateOf(settings.interests) }
    var provider by rememberSaveable(settings.revision) { mutableStateOf(settings.provider) }
    var endpointMode by rememberSaveable(settings.revision) { mutableStateOf(settings.endpointMode) }
    var authMode by rememberSaveable(settings.revision) { mutableStateOf(settings.authMode) }
    var choosingProvider by rememberSaveable { mutableStateOf(false) }
    val configured = settings.endpoint.isNotBlank() && settings.model.isNotBlank() && settings.hasKey
    var editingService by rememberSaveable(settings.revision) { mutableStateOf(!configured) }
    var serviceDetails by rememberSaveable { mutableStateOf(false) }
    var dataDetails by rememberSaveable { mutableStateOf(false) }
    var preferencesExpanded by rememberSaveable { mutableStateOf(false) }
    val draft = AiConnectionDraft(endpoint, model, key.ifBlank { null }, provider, endpointMode, authMode)
    val resolvedEndpoint = remember(endpoint, provider, endpointMode) { runCatching { resolveAiEndpoint(endpoint, provider, endpointMode) } }
    val reusingKey = canReuseSavedKey(settings, draft)
    val canTest = resolvedEndpoint.isSuccess && model.isNotBlank() && (key.isNotBlank() || reusingKey)
    val connectionChanged = endpoint.trim() != settings.endpoint.trim() || model.trim() != settings.model.trim() ||
        provider != settings.provider || endpointMode != settings.endpointMode || key.isNotBlank() ||
        (provider == AiProvider.ANTHROPIC_MESSAGES && authMode != settings.authMode)

    if (choosingProvider) AlertDialog(
        onDismissRequest = { choosingProvider = false },
        title = { Text("选择接口类型") },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                AiProvider.entries.forEach { choice ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(
                        selected = provider == choice, role = Role.RadioButton,
                        onClick = {
                            if (provider != choice) {
                                provider = choice
                                key = ""
                                if (choice == AiProvider.JEV && endpoint.isBlank()) {
                                    endpoint = "https://api.typesafe.ai/v1/systemone"
                                    endpointMode = AiEndpointMode.FULL_URL
                                }
                                if (choice == AiProvider.JEV && model.isBlank()) model = "jev-latest"
                                onConnectionChanged()
                            }
                            choosingProvider = false
                        }), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = provider == choice, onClick = null)
                        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            Text(providerLabel(choice), style = MaterialTheme.typography.titleSmall)
                            Text(providerDescription(choice), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingProvider = false }) { Text("取消") } },
    )

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
                            Text("${providerLabel(provider)} · ${model.ifBlank { "填写模型名称" }}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (connectionChanged) Text("未保存", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (configured) TextButton(onClick = { editingService = !editingService }) {
                            Text(if (editingService) "收起配置" else "编辑服务")
                        }
                    }
                    if (editingService) {
                        OutlinedButton(onClick = { choosingProvider = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { contentDescription = "选择接口类型" }) {
                            Text(providerLabel(provider), Modifier.weight(1f))
                            Icon(Icons.Outlined.ExpandMore, contentDescription = null)
                        }
                        if (provider != settings.provider) Text("已更换接口类型，请核对地址、模型和密钥。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (provider != AiProvider.JEV) {
                            Column(Modifier.selectableGroup()) {
                                AiRadioOption("基础 URL", endpointMode == AiEndpointMode.BASE_URL) {
                                    if (endpointMode != AiEndpointMode.BASE_URL) { endpointMode = AiEndpointMode.BASE_URL; onConnectionChanged() }
                                }
                                AiRadioOption("完整 URL", endpointMode == AiEndpointMode.FULL_URL) {
                                    if (endpointMode != AiEndpointMode.FULL_URL) { endpointMode = AiEndpointMode.FULL_URL; onConnectionChanged() }
                                }
                            }
                        }
                        OutlinedTextField(endpoint, {
                            if (endpointAuthority(endpoint.trim()) != endpointAuthority(it.trim())) key = ""
                            endpoint = it
                            onConnectionChanged()
                        }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("服务地址") }, placeholder = { Text(when {
                                provider == AiProvider.JEV -> "https://api.typesafe.ai/v1/systemone"
                                endpointMode == AiEndpointMode.BASE_URL -> "https://你的服务"
                                provider == AiProvider.ANTHROPIC_MESSAGES -> "https://你的服务/v1/messages"
                                else -> "https://你的服务/v1/chat/completions"
                            }) }, isError = endpoint.isNotBlank() && resolvedEndpoint.isFailure)
                        if (endpoint.isNotBlank()) {
                            if (resolvedEndpoint.isSuccess) Text("实际请求地址\n${resolvedEndpoint.getOrNull()}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Text(resolvedEndpoint.exceptionOrNull()?.message ?: "请检查服务地址。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedTextField(model, { model = it; onConnectionChanged() }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("模型名称") }, placeholder = { if (provider == AiProvider.JEV) Text("jev-latest") },
                            supportingText = { Text("填写服务提供的模型 ID") })
                        if (provider == AiProvider.ANTHROPIC_MESSAGES) {
                            Text("鉴权方式", style = MaterialTheme.typography.labelLarge)
                            Column(Modifier.selectableGroup()) {
                                AiRadioOption("API Key（x-api-key）", authMode == AiAuthMode.API_KEY) {
                                    if (authMode != AiAuthMode.API_KEY) { authMode = AiAuthMode.API_KEY; key = ""; onConnectionChanged() }
                                }
                                AiRadioOption("Bearer Token", authMode == AiAuthMode.BEARER) {
                                    if (authMode != AiAuthMode.BEARER) { authMode = AiAuthMode.BEARER; key = ""; onConnectionChanged() }
                                }
                            }
                        }
                        OutlinedTextField(key, { key = it; onConnectionChanged() }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(if (reusingKey) "密钥已保存，留空保留" else "API 密钥") },
                            visualTransformation = PasswordVisualTransformation())
                        if (settings.hasKey && !reusingKey && key.isBlank()) Text("地址、类型或鉴权已变更，请填写对应密钥。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!editingService && endpoint.isNotBlank()) {
                        if (resolvedEndpoint.isSuccess) Text("实际请求地址\n${resolvedEndpoint.getOrNull()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text(resolvedEndpoint.exceptionOrNull()?.message ?: "请检查服务地址。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { onTest(draft) }, enabled = canTest && !testStatus.running,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (testStatus.running) "正在测试…" else "测试连接") }
                    if (testStatus.message.isNotBlank()) Text(testStatus.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (testStatus.success == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text("用虚构示例测试当前填写，不会开启云端分析。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AiDisclosure("服务说明", serviceDetails, { serviceDetails = !serviceDetails })
                    if (serviceDetails) {
                        Text(when (provider) {
                            AiProvider.JEV -> "Jev 同样通过云端判断。官方接口：https://api.typesafe.ai/v1/systemone；模型可填 jev-latest。"
                            AiProvider.ANTHROPIC_MESSAGES -> "Claude Messages 使用 /v1/messages。基础 URL 会补全接口路径，完整 URL 原样使用；鉴权方式按服务要求选择。"
                            AiProvider.CHAT_COMPLETIONS -> "Chat Completions 使用 /v1/chat/completions。基础 URL 会补全接口路径，完整 URL 原样使用。"
                        },
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
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                value = app.packageName in allowed, role = Role.Checkbox,
                onValueChange = { checked -> allowed = if (checked) allowed + app.packageName else allowed - app.packageName })
                .semantics { contentDescription = "允许云端分析${app.label}的通知" }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppAvatar(app.packageName, app.label)
                Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Checkbox(app.packageName in allowed, onCheckedChange = null)
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
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = enabled, role = Role.Switch,
                    onValueChange = { enabled = it }).semantics { contentDescription = "启用云端分析" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("启用云端分析", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(enabled, onCheckedChange = null)
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
                Button(onClick = { onSave(endpoint, model, key.ifBlank { null }, enabled, allowed.intersect(selected), interests, provider, endpointMode, authMode) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (enabled) "保存并启用" else "保存设置")
                }
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun providerLabel(provider: AiProvider) = when (provider) {
    AiProvider.CHAT_COMPLETIONS -> "Chat Completions"
    AiProvider.ANTHROPIC_MESSAGES -> "Claude Messages"
    AiProvider.JEV -> "Jev"
}

private fun providerDescription(provider: AiProvider) = when (provider) {
    AiProvider.CHAT_COMPLETIONS -> "OpenAI 兼容接口"
    AiProvider.ANTHROPIC_MESSAGES -> "Anthropic 兼容接口"
    AiProvider.JEV -> "TypeSafe 云端判断服务"
}

@Composable
private fun AiRadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected,
        role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AiDisclosure(title: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(vertical = 8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起$title" else "展开$title")
    }
}
