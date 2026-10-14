package com.qingxu.app.core.ai

import android.app.Application
import com.qingxu.app.core.decision.CloudDecisionPolicy
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.data.local.NotificationEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AnthropicClientTest {
    private val settings = AiSettings(endpoint = "https://api.example.com/v1/messages", model = "example-claude",
        interests = "设计", provider = AiProvider.ANTHROPIC_MESSAGES)
    private val entry = NotificationEntry("private-key", "example.neutral", "示例品牌", "秋季促销",
        "限时优惠，满100减20", "promotion", 1_000L, 1_000L, "private-hash")

    private fun decisionContent(kind: String = "marketing", important: Boolean = false, urgent: Boolean = false,
        action: Boolean = false, confidence: Double = .99) = JSONObject()
        .put("kind", kind).put("important", important).put("urgent", urgent).put("actionRequired", action)
        .put("confidence", confidence).put("reason", "服务自由文本不应直接展示").toString()

    private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)
    private fun response(text: String = decisionContent()) = JSONObject()
        .put("id", "synthetic-message").put("type", "message").put("role", "assistant")
        .put("model", "example-claude").put("stop_reason", "end_turn").put("stop_sequence", JSONObject.NULL)
        .put("content", JSONArray().put(textBlock(text)))

    @Test fun requestUsesTopLevelSystemAndOnlyOneUserMessageWithoutTools() {
        val request = createAnthropicRequest(settings, entry)
        assertEquals(setOf("model", "max_tokens", "stream", "system", "messages"),
            request.keys().asSequence().toSet())
        assertEquals("example-claude", request.getString("model"))
        assertEquals(512, request.getInt("max_tokens"))
        assertFalse(request.getBoolean("stream"))
        val shared = createRequest(settings, entry).getJSONArray("messages")
        assertEquals(shared.getJSONObject(0).getString("content"), request.getString("system"))
        val messages = request.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val input = JSONObject(messages.getJSONObject(0).getString("content"))
        assertEquals("设计", input.getString("interests"))
        assertEquals(entry.body, input.getJSONObject("notification").getString("body"))
        listOf("private-key", "private-hash", "example.neutral", "channelId", "temperature", "tools").forEach {
            // Instructions mention tools conceptually, so inspect actual request fields for capability keys.
            if (it == "tools" || it == "temperature") assertFalse(request.has(it))
            else assertFalse(request.toString().contains(it))
        }
    }

    @Test fun completeTextResponseUsesSharedDecisionPolicyAndDoesNotExposeModelProse() {
        val decision = parseAnthropicResponse(response().toString())
        assertEquals(DecisionBucket.HIDDEN, decision.bucket)
        assertEquals("ai", decision.source)
        assertFalse(decision.reason.contains("服务自由文本"))
        assertEquals(DecisionBucket.HIDDEN, CloudDecisionPolicy.protect(entry, decision).bucket)
        assertEquals(DecisionBucket.REVIEW,
            CloudDecisionPolicy.protect(entry.copy(body = "限时优惠，退款失败需要处理"), decision).bucket)
        assertEquals(DecisionBucket.NOW, parseAnthropicResponse(response(
            decisionContent("transaction", important = true, urgent = true, action = true)).toString()).bucket)
        assertEquals(DecisionBucket.REVIEW,
            parseAnthropicResponse(response(decisionContent(confidence = .5)).toString()).bucket)
    }

    @Test fun consecutiveTextBlocksJoinIntoOneStrictDecisionObject() {
        val text = decisionContent()
        val split = text.length / 2
        val envelope = response().put("content", JSONArray()
            .put(textBlock(text.substring(0, split))).put(textBlock(text.substring(split))))
        assertEquals(DecisionBucket.HIDDEN, parseAnthropicResponse(envelope.toString()).bucket)
        val twoObjects = response().put("content", JSONArray().put(textBlock(text)).put(textBlock(text)))
        assertThrows(AiServiceException::class.java) { parseAnthropicResponse(twoObjects.toString()) }
    }

    @Test fun incompleteRefusedToolOrNonTextResponsesCannotProduceDecision() {
        val invalid = listOf(
            response().put("stop_reason", "max_tokens"),
            response().put("stop_reason", "tool_use"),
            response().put("stop_reason", "refusal"),
            response().put("stop_reason", "stop_sequence"),
            response().put("stop_reason", JSONObject.NULL),
            response().put("role", "user"),
            response().put("type", "error"),
            response().put("error", JSONObject().put("type", "overloaded_error")),
            response().put("refusal", "test refusal"),
            response().put("tool_calls", JSONArray()),
            response().put("content", JSONArray()),
            response().put("content", JSONArray().put(textBlock(""))),
            response().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", 123))),
            response().put("content", JSONArray().put(JSONObject().put("type", "tool_use").put("name", "unused"))),
            response().put("content", JSONArray().put(textBlock(decisionContent()))
                .put(JSONObject().put("type", "thinking").put("thinking", "test-only"))),
            response().put("content", JSONArray().put(textBlock(decisionContent()).put("refusal", "test refusal"))),
        )
        invalid.forEach { assertThrows(AiServiceException::class.java) { parseAnthropicResponse(it.toString()) } }
    }

    @Test fun malformedEnvelopeOrDecisionIsRejectedWithoutEchoingResponse() {
        val invalid = listOf(
            "not-json-private-response",
            "x".repeat(65_537),
            response().toString().replace("\"stop_reason\":\"end_turn\"", "\"stop_reason\":\"end_turn\",\"stop_reason\":\"tool_use\""),
            response(decisionContent().replace("\"important\":false", "\"important\":\"false\"")).toString(),
            response(decisionContent().replace("\"important\":false", "\"important\":false,\"important\":true")).toString(),
            response("```json\n${decisionContent()}\n```").toString(),
        )
        invalid.forEach {
            val error = assertThrows(AiServiceException::class.java) { parseAnthropicResponse(it) }
            assertFalse(error.message.orEmpty().contains("private-response"))
        }
    }

    @Test fun adapterUsesSelectedProtocolAndCredentialWithFakeTransport() = runBlocking {
        var calls = 0
        val client = AnthropicClient(CloudTransport { actual, key, payload ->
            calls++
            assertEquals(settings, actual)
            assertEquals("synthetic-only-key", key)
            assertTrue(payload.has("system"))
            assertFalse(payload.has("questions"))
            response().toString()
        })
        assertEquals(DecisionBucket.HIDDEN, client.classify(settings, "synthetic-only-key", entry).bucket)
        assertEquals(1, calls)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.classify(settings.copy(provider = AiProvider.CHAT_COMPLETIONS), "synthetic-only-key", entry) }
        }
        assertEquals(1, calls)
    }
}
