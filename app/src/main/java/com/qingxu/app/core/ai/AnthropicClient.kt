package com.qingxu.app.core.ai

import com.qingxu.app.core.decision.Decision
import com.qingxu.app.data.local.NotificationEntry
import org.json.JSONArray
import org.json.JSONObject

/** Non-streaming Anthropic Messages adapter. Authentication and URL resolution belong to transport. */
class AnthropicClient internal constructor(private val transport: CloudTransport) {
    constructor() : this(HttpsCloudTransport())

    suspend fun classify(settings: AiSettings, key: String, entry: NotificationEntry): Decision {
        require(settings.provider == AiProvider.ANTHROPIC_MESSAGES) { "请选择 Anthropic Messages 服务类型。" }
        return parseAnthropicResponse(transport.post(settings, key, createAnthropicRequest(settings, entry)))
    }
}

internal fun createAnthropicRequest(settings: AiSettings, entry: NotificationEntry): JSONObject {
    // Keep the analysis instructions and bounded notification input identical across text providers.
    val sharedMessages = createRequest(settings, entry).getJSONArray("messages")
    return JSONObject()
        .put("model", settings.model)
        .put("max_tokens", 512)
        .put("stream", false)
        .put("system", sharedMessages.getJSONObject(0).getString("content"))
        .put("messages", JSONArray().put(JSONObject()
            .put("role", "user")
            .put("content", sharedMessages.getJSONObject(1).getString("content"))))
}

internal fun parseAnthropicResponse(response: String): Decision {
    try {
        val root = strictJsonObject(response)
        require(root.get("type") == "message" && root.get("role") == "assistant")
        require(root.get("stop_reason") == "end_turn")
        require(!root.has("stop_sequence") || root.isNull("stop_sequence"))
        listOf("error", "refusal", "tool_calls", "function_call").forEach { field ->
            require(!root.has(field) || root.isNull(field))
        }
        val blocks = root.getJSONArray("content")
        require(blocks.length() in 1..64)
        val text = buildString {
            for (index in 0 until blocks.length()) {
                val block = blocks.getJSONObject(index)
                require(block.get("type") == "text")
                require(!block.has("refusal") || block.isNull("refusal"))
                append(block.get("text") as? String ?: error("Invalid text block"))
            }
        }
        require(text.isNotBlank())
        return parseDecisionContent(text)
    } catch (known: AiServiceException) {
        throw known
    } catch (_: Exception) {
        throw AiServiceException("模型返回的格式无法识别，本条通知已保留。")
    }
}
