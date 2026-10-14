package com.qingxu.app.core.ai

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AiConnectionTest {
    private lateinit var context: Context
    private lateinit var stored: SharedPreferences

    @Before fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        stored = context.getSharedPreferences("smart_sorting", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
    }

    @Test fun baseAddressAcceptsDomainVersionPrefixAndNestedGatewayWithoutDuplicatingEndpoint() {
        listOf(AiProvider.CHAT_COMPLETIONS to "/chat/completions",
            AiProvider.ANTHROPIC_MESSAGES to "/messages").forEach { (provider, suffix) ->
            val cases = mapOf(
                "https://api.example.com" to "https://api.example.com/v1$suffix",
                "https://api.example.com/" to "https://api.example.com/v1$suffix",
                "https://api.example.com/v1" to "https://api.example.com/v1$suffix",
                "https://api.example.com/v1/" to "https://api.example.com/v1$suffix",
                "https://api.example.com/gateway/team" to "https://api.example.com/gateway/team/v1$suffix",
                "https://api.example.com/gateway/team/v1/" to "https://api.example.com/gateway/team/v1$suffix",
                "https://api.example.com/v1$suffix" to "https://api.example.com/v1$suffix",
                "https://api.example.com/gateway$suffix/" to "https://api.example.com/gateway$suffix",
                " https://api.example.com:8443/ " to "https://api.example.com:8443/v1$suffix",
            )
            cases.forEach { (input, expected) ->
                assertEquals(input, expected, resolveAiEndpoint(input, provider, AiEndpointMode.BASE_URL).toString())
            }
        }
    }

    @Test fun fullAddressPreservesCustomRouteInsteadOfAppendingProtocolPath() {
        listOf(AiProvider.CHAT_COMPLETIONS, AiProvider.ANTHROPIC_MESSAGES).forEach { provider ->
            listOf("https://api.example.com/proxy/custom/route", "https://api.example.com/proxy/custom/route/",
                "https://api.example.com:8443/provider/model").forEach { endpoint ->
                assertEquals(endpoint, resolveAiEndpoint(" $endpoint ", provider, AiEndpointMode.FULL_URL).toString())
            }
        }
        assertEquals("https://api.example.com/v1/messages", resolveAiEndpoint("https://api.example.com/v1/messages",
            AiProvider.ANTHROPIC_MESSAGES, AiEndpointMode.FULL_URL).toString())
        assertEquals("https://api.example.com/v1/chat/completions", resolveAiEndpoint("https://api.example.com/v1/chat/completions",
            AiProvider.CHAT_COMPLETIONS, AiEndpointMode.FULL_URL).toString())
        assertThrows(IllegalArgumentException::class.java) {
            resolveAiEndpoint("https://api.example.com", AiProvider.CHAT_COMPLETIONS, AiEndpointMode.FULL_URL)
        }
    }

    @Test fun knownWrongProtocolRoutesAreRejectedInsteadOfSilentlyConverted() {
        val mismatches = listOf(
            AiProvider.CHAT_COMPLETIONS to "/v1/messages",
            AiProvider.CHAT_COMPLETIONS to "/v1/responses",
            AiProvider.CHAT_COMPLETIONS to "/v1/systemone",
            AiProvider.ANTHROPIC_MESSAGES to "/v1/chat/completions",
            AiProvider.ANTHROPIC_MESSAGES to "/v1/responses",
            AiProvider.ANTHROPIC_MESSAGES to "/v1/systemone",
        )
        AiEndpointMode.entries.forEach { mode ->
            mismatches.forEach { (provider, path) ->
                assertThrows("$provider $mode $path", IllegalArgumentException::class.java) {
                    resolveAiEndpoint("https://api.example.com$path", provider, mode)
                }
            }
        }
    }

    @Test fun endpointRejectsInsecureCredentialBearingAndAmbiguousAddressesInBothModes() {
        val invalid = listOf(
            "", "http://api.example.com/v1/messages", "file:///tmp/provider", "not an address",
            "https://user:synthetic-password@api.example.com/v1/messages",
            "https://api.example.com/v1/messages?api_key=synthetic-only",
            "https://api.example.com/v1/messages#fragment",
            "https://api.example.com:0/v1/messages", "https://api.example.com:65536/v1/messages",
            "https://api.example.com/a/../v1/messages", "https://api.example.com/%76%31/messages",
            "https://api.example.com/v1/\nmessages",
        )
        AiEndpointMode.entries.forEach { mode ->
            invalid.forEach { endpoint ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    resolveAiEndpoint(endpoint, AiProvider.ANTHROPIC_MESSAGES, mode)
                }
                assertFalse(error.message.orEmpty().contains("synthetic-password"))
                assertFalse(error.message.orEmpty().contains("synthetic-only"))
            }
        }
    }

    @Test fun jevResolvesOnlyToOfficialHttpsEndpoint() {
        listOf("https://api.typesafe.ai", "https://api.typesafe.ai/", "https://api.typesafe.ai/v1",
            "https://api.typesafe.ai/v1/", "https://api.typesafe.ai/v1/systemone").forEach {
            assertEquals("https://api.typesafe.ai/v1/systemone",
                resolveAiEndpoint(it, AiProvider.JEV, AiEndpointMode.BASE_URL).toString())
        }
        assertEquals("https://api.typesafe.ai:443/v1/systemone", resolveAiEndpoint(
            "https://api.typesafe.ai:443/v1/systemone", AiProvider.JEV, AiEndpointMode.FULL_URL).toString())
        AiEndpointMode.entries.forEach { mode ->
            listOf("https://other.example.com/v1/systemone", "https://api.typesafe.ai:444/v1/systemone",
                "https://api.typesafe.ai/proxy/v1/systemone", "https://api.typesafe.ai/v1/messages").forEach {
                assertThrows(IllegalArgumentException::class.java) { resolveAiEndpoint(it, AiProvider.JEV, mode) }
            }
        }
    }

    @Test fun legacyEndpointKeepsFullUrlMeaningAndFreshInstallStartsWithBaseUrl() {
        assertEquals(AiEndpointMode.BASE_URL, AiPreferences(context).state.value.endpointMode)
        val legacy = "https://api.example.com/custom/completions"
        stored.edit().putString("endpoint", legacy).putString("model", "old-model").commit()
        val preferences = AiPreferences(context)
        assertEquals(AiEndpointMode.FULL_URL, preferences.state.value.endpointMode)
        assertEquals(legacy, preferences.state.value.endpoint)
        assertFalse(stored.contains("endpoint_mode"))
        assertEquals(legacy, resolveAiEndpoint(preferences.state.value.endpoint,
            preferences.state.value.provider, preferences.state.value.endpointMode).toString())
        preferences.save(legacy, "old-model", null, false, emptySet(), "")
        assertEquals(AiEndpointMode.FULL_URL, AiPreferences(context).state.value.endpointMode)
    }

    @Test fun explicitAddressModeAndClaudeAuthenticationPersistWithoutChangingAuthorization() {
        val preferences = AiPreferences(context)
        preferences.save("https://api.example.com", "example-model", null, false, setOf("example.shop"), "设计",
            AiProvider.ANTHROPIC_MESSAGES, AiEndpointMode.BASE_URL, AiAuthMode.BEARER)
        val read = AiPreferences(context).state.value
        assertEquals(AiEndpointMode.BASE_URL, read.endpointMode)
        assertEquals(AiAuthMode.BEARER, read.authMode)
        assertEquals(AiProvider.ANTHROPIC_MESSAGES, read.provider)
        assertEquals(setOf("example.shop"), read.allowedPackages)
        assertEquals("设计", read.interests)
        assertFalse(read.enabled)
        assertFalse(read.hasKey)
    }

    @Test fun draftWithNewKeyDoesNotSaveEnableOrCopyNotificationAuthorization() {
        seedEncryptedSettings(AiProvider.CHAT_COMPLETIONS)
        val preferences = AiPreferences(context)
        val before = preferences.state.value
        val rawBefore = stored.all.toMap()
        val draft = AiConnectionDraft(" https://other.example.com/ ", " other-model ", " synthetic-new-key ",
            AiProvider.ANTHROPIC_MESSAGES, AiEndpointMode.BASE_URL, AiAuthMode.BEARER)
        val (testSettings, testKey) = preferences.connectionSnapshot(draft)
        assertEquals("synthetic-new-key", testKey)
        assertEquals("https://other.example.com/", testSettings.endpoint)
        assertEquals("other-model", testSettings.model)
        assertEquals(AiProvider.ANTHROPIC_MESSAGES, testSettings.provider)
        assertEquals(AiAuthMode.BEARER, testSettings.authMode)
        assertEquals(before.revision, testSettings.revision)
        assertFalse(testSettings.enabled)
        assertTrue(testSettings.allowedPackages.isEmpty())
        assertEquals("", testSettings.interests)
        assertFalse(testSettings.hasKey)
        assertEquals(before, preferences.state.value)
        assertEquals(rawBefore, stored.all)
        assertFalse(preferences.state.value.enabled)
        assertEquals(setOf("example.shop"), preferences.state.value.allowedPackages)
        assertEquals("original-interest", preferences.state.value.interests)
    }

    @Test fun changingHostPortProtocolOrClaudeAuthRejectsReuseBeforeDecryptingStoredCiphertext() {
        seedEncryptedSettings(AiProvider.ANTHROPIC_MESSAGES)
        val preferences = AiPreferences(context)
        val original = preferences.state.value
        val base = AiConnectionDraft("https://api.example.com", "model", null,
            AiProvider.ANTHROPIC_MESSAGES, AiEndpointMode.BASE_URL)
        val invalid = listOf(base.copy(endpoint = "https://other.example.com"),
            base.copy(endpoint = "https://api.example.com:8443"),
            base.copy(provider = AiProvider.CHAT_COMPLETIONS), base.copy(authMode = AiAuthMode.BEARER))
        invalid.forEach { draft ->
            assertFalse(canReuseSavedKey(original, draft))
            assertThrows(IllegalArgumentException::class.java) { preferences.connectionSnapshot(draft) }
            assertThrows(IllegalArgumentException::class.java) {
                preferences.save(draft.endpoint, draft.model, null, false, original.allowedPackages, original.interests,
                    draft.provider, draft.endpointMode, draft.authMode)
            }
            assertEquals(original, preferences.state.value)
        }
        // The deliberately invalid ciphertext must never reach Keystore decryption in these checks.
        assertEquals("never-decrypt-this-test-value", stored.getString("key_ciphertext", null))
    }

    @Test fun savedKeyHelperAcceptsSameAuthorityAcrossAddressModesButNotInvalidInput() {
        val saved = AiSettings(endpoint = "https://API.example.com/v1/chat/completions", model = "model",
            hasKey = true, endpointMode = AiEndpointMode.FULL_URL)
        val draft = AiConnectionDraft("https://api.example.com:443", "another-model", null,
            AiProvider.CHAT_COMPLETIONS, AiEndpointMode.BASE_URL)
        assertTrue(canReuseSavedKey(saved, draft))
        assertTrue(canReuseSavedKey(saved, draft.copy(endpoint = "https://api.example.com/custom/route",
            endpointMode = AiEndpointMode.FULL_URL)))
        assertFalse(canReuseSavedKey(saved.copy(hasKey = false), draft))
        assertFalse(canReuseSavedKey(saved, draft.copy(endpoint = "http://api.example.com")))
        assertFalse(canReuseSavedKey(saved, draft.copy(endpoint = "https://api.example.com:444")))
        assertFalse(canReuseSavedKey(saved, draft.copy(provider = AiProvider.ANTHROPIC_MESSAGES)))
    }

    @Test fun protocolHeadersSendExactlyOneCredentialScheme() {
        val key = "synthetic-only-key"
        listOf(AiProvider.CHAT_COMPLETIONS, AiProvider.JEV).forEach { provider ->
            AiAuthMode.entries.forEach { mode ->
                assertEquals(mapOf("Authorization" to "Bearer $key"),
                    connectionHeaders(AiSettings(provider = provider, authMode = mode), key))
            }
        }
        assertEquals(mapOf("anthropic-version" to "2023-06-01", "x-api-key" to key),
            connectionHeaders(AiSettings(provider = AiProvider.ANTHROPIC_MESSAGES), key))
        assertEquals(mapOf("anthropic-version" to "2023-06-01", "Authorization" to "Bearer $key"),
            connectionHeaders(AiSettings(provider = AiProvider.ANTHROPIC_MESSAGES, authMode = AiAuthMode.BEARER), key))
    }

    @Test fun httpFailuresHaveDistinctActionableMessagesWithoutServiceOutput() {
        val categories = mapOf(400 to "接口类型", 401 to "密钥", 403 to "权限", 404 to "地址", 429 to "额度")
        val messages = categories.map { (status, hint) ->
            statusMessage(status).also { message ->
                assertTrue(message.contains("HTTP $status"))
                assertTrue(message.contains(hint))
                assertFalse(message.contains("https://"))
                assertFalse(message.contains("Bearer"))
                assertFalse(message.contains("Exception"))
                assertFalse(message.contains("synthetic-only-key"))
            }
        }
        assertEquals(categories.size, messages.toSet().size)
        assertTrue(statusMessage(302).contains("重定向"))
        assertTrue(statusMessage(500).contains("暂时不可用"))
    }

    private fun seedEncryptedSettings(provider: AiProvider) {
        stored.edit().putString("endpoint", "https://api.example.com")
            .putString("endpoint_mode", AiEndpointMode.BASE_URL.name)
            .putString("provider", provider.name).putString("auth_mode", AiAuthMode.API_KEY.name)
            .putString("model", "saved-model").putBoolean("enabled", false)
            .putStringSet("allowed_packages", setOf("example.shop")).putString("interests", "original-interest")
            .putLong("revision", 7L).putString("key_ciphertext", "never-decrypt-this-test-value")
            .putString("key_iv", "never-decrypt-this-test-iv").commit()
    }
}
