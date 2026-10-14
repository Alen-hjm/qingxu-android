package com.qingxu.app.core.ai

import java.net.URI

data class AiConnectionDraft(
    val endpoint: String,
    val model: String,
    val key: String?,
    val provider: AiProvider,
    val endpointMode: AiEndpointMode,
    val authMode: AiAuthMode = AiAuthMode.API_KEY,
)

data class AiConnectionTestStatus(
    val running: Boolean = false,
    val message: String = "",
    val success: Boolean? = null,
)

/** Pure URL handling; no trial requests, redirects or guessing a different provider. */
internal fun resolveAiEndpoint(value: String, provider: AiProvider, mode: AiEndpointMode): URI {
    val clean = value.trim()
    require(clean.isNotBlank() && clean.length <= 2048 && clean.none { it.isISOControl() }) {
        "请填写 HTTPS 服务地址。"
    }
    val uri = try { URI(clean) } catch (_: Exception) {
        throw IllegalArgumentException("服务地址格式不正确，请检查 HTTPS 地址。")
    }
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) {
        "服务地址必须使用 HTTPS，例如 https://api.example.com。"
    }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "地址不能包含账号、密钥、查询参数或锚点，请把密钥填入专用输入框。"
    }
    require(uri.port == -1 || uri.port in 1..65535) { "服务地址的端口不正确。" }
    require(uri.normalize().rawPath == uri.rawPath && !uri.rawPath.orEmpty().contains('%')) {
        "接口路径不能包含转义字符或相对路径。"
    }
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val suffix = when (provider) {
        AiProvider.CHAT_COMPLETIONS -> "/chat/completions"
        AiProvider.ANTHROPIC_MESSAGES -> "/messages"
        AiProvider.JEV -> "/systemone"
    }
    val knownEndpoints = listOf("/chat/completions", "/messages", "/responses", "/systemone")
    require(knownEndpoints.none { path.endsWith(it) && it != suffix }) {
        "地址与所选接口类型不匹配，请切换类型或填写对应的服务地址。"
    }
    if (mode == AiEndpointMode.FULL_URL) return validateAiEndpoint(clean, provider)
    val resolvedPath = when {
        path.endsWith(suffix) -> path
        path.endsWith("/v1") -> path + suffix
        else -> path + "/v1" + suffix
    }
    return validateAiEndpoint("${uri.scheme}://${uri.rawAuthority}$resolvedPath", provider)
}

internal fun canReuseSavedKey(settings: AiSettings, draft: AiConnectionDraft): Boolean {
    if (!settings.hasKey || settings.provider != draft.provider ||
        (draft.provider == AiProvider.ANTHROPIC_MESSAGES && settings.authMode != draft.authMode)) return false
    return try {
        val saved = resolveAiEndpoint(settings.endpoint, settings.provider, settings.endpointMode)
        val current = resolveAiEndpoint(draft.endpoint, draft.provider, draft.endpointMode)
        endpointAuthority(saved.toString()) == endpointAuthority(current.toString())
    } catch (_: IllegalArgumentException) { false }
}

internal fun draftSettings(draft: AiConnectionDraft): AiSettings {
    resolveAiEndpoint(draft.endpoint, draft.provider, draft.endpointMode)
    val model = draft.model.trim()
    require(model.isNotEmpty() && model.length <= 200 && model.none { it.isISOControl() }) {
        "请填写服务实际支持的模型名称。"
    }
    return AiSettings(endpoint = draft.endpoint.trim(), model = model, provider = draft.provider,
        endpointMode = draft.endpointMode, authMode = draft.authMode)
}

internal fun connectionHeaders(settings: AiSettings, key: String): Map<String, String> =
    if (settings.provider == AiProvider.ANTHROPIC_MESSAGES) {
        mapOf("anthropic-version" to "2023-06-01") + when (settings.authMode) {
            AiAuthMode.API_KEY -> mapOf("x-api-key" to key)
            AiAuthMode.BEARER -> mapOf("Authorization" to "Bearer $key")
        }
    } else mapOf("Authorization" to "Bearer $key")
