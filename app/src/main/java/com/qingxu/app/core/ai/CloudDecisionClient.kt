package com.qingxu.app.core.ai

import com.qingxu.app.core.decision.Decision
import com.qingxu.app.data.local.NotificationEntry
import org.json.JSONObject

/**
 * Returns a model decision, not an authorization to hide a notification.
 * The processor must protect the decision using the original row before applying it; transport input
 * may be redacted and therefore cannot supply local package/channel context for that policy.
 * The provider is part of the settings snapshot, so routing cannot mix protocols or credentials.
 */
class CloudDecisionClient internal constructor(private val transport: CloudTransport) {
    constructor() : this(HttpsCloudTransport())

    suspend fun classify(settings: AiSettings, key: String, entry: NotificationEntry): Decision =
        when (settings.provider) {
            AiProvider.CHAT_COMPLETIONS -> AiClient(transport).classify(settings, key, entry)
            AiProvider.ANTHROPIC_MESSAGES -> AnthropicClient(transport).classify(settings, key, entry)
            AiProvider.JEV -> JevClient(transport).classify(settings, key, entry)
        }
}

internal fun interface CloudTransport {
    suspend fun post(settings: AiSettings, key: String, payload: JSONObject): String
}
