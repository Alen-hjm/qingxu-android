package com.qingxu.app.core.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AiPreferencesTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("smart_sorting", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun endpointAllowsOnlyCompleteHttpsAddressWithoutSecretsOrRedirectParameters() {
        assertEquals("api.example.com", validateAiEndpoint("https://api.example.com/v1/chat/completions").host)
        listOf(
            "http://api.example.com/v1/chat/completions",
            "https://api.example.com",
            "https://api.example.com/",
            "https://user:pass@api.example.com/v1/chat/completions",
            "https://api.example.com/v1/chat/completions?api_key=secret",
            "https://api.example.com/v1/chat/completions#fragment",
            "https://api.example.com:99999/v1/chat/completions",
            "file:///tmp/provider",
            "this is not a URL",
        ).forEach { assertThrows(IllegalArgumentException::class.java) { validateAiEndpoint(it) } }
    }

    @Test fun freshInstallDoesNotOptInOrAuthorizeAnySources() {
        val preferences = AiPreferences(context)
        assertFalse(preferences.state.value.enabled)
        assertFalse(preferences.state.value.hasKey)
        assertTrue(preferences.state.value.allowedPackages.isEmpty())
        assertEquals("", preferences.apiKey())
        assertEquals(AiProvider.CHAT_COMPLETIONS, preferences.state.value.provider)
    }

    @Test fun jevRequiresOfficialEndpointAndProviderPersistsWithRevision() {
        assertEquals("api.typesafe.ai", validateAiEndpoint("https://api.typesafe.ai/v1/systemone", AiProvider.JEV).host)
        listOf("https://api.typesafe.ai/v1/chat/completions", "https://other.example/v1/systemone",
            "https://api.typesafe.ai:444/v1/systemone").forEach {
            assertThrows(IllegalArgumentException::class.java) { validateAiEndpoint(it, AiProvider.JEV) }
        }
        val preferences = AiPreferences(context)
        preferences.save("https://api.typesafe.ai/v1/systemone", "jev-latest", null, false, emptySet(), "", AiProvider.JEV)
        assertEquals(1L, preferences.state.value.revision)
        assertEquals(AiProvider.JEV, AiPreferences(context).state.value.provider)
        preferences.save("https://api.typesafe.ai/v1/systemone", "jev-latest", null, false, emptySet(), "兴趣")
        assertEquals(AiProvider.JEV, preferences.state.value.provider)
        assertEquals(2L, preferences.state.value.revision)
    }

    @Test fun changingProtocolOnSameHostCannotReuseExistingKey() {
        context.getSharedPreferences("smart_sorting", Context.MODE_PRIVATE).edit()
            .putString("endpoint", "https://api.typesafe.ai/v1/systemone")
            .putString("key_ciphertext", "dummy-ciphertext").putString("key_iv", "dummy-iv").commit()
        val preferences = AiPreferences(context)
        assertThrows(IllegalArgumentException::class.java) {
            preferences.save("https://api.typesafe.ai/v1/systemone", "jev-latest", null, false, emptySet(), "", AiProvider.JEV)
        }
        assertEquals(AiProvider.CHAT_COMPLETIONS, preferences.state.value.provider)
        preferences.clearKey()
        preferences.save("https://api.typesafe.ai/v1/systemone", "jev-latest", null, false, emptySet(), "", AiProvider.JEV)
        assertEquals(AiProvider.JEV, preferences.state.value.provider)
    }

    @Test fun disabledDraftPersistsSelectedSourcesAndRevisionWithoutNetworkOrKey() {
        val preferences = AiPreferences(context)
        preferences.save("https://api.example.com/v1/chat/completions", "sample", null, false, setOf("example.app"), "包裹")
        assertEquals(1L, preferences.state.value.revision)
        preferences.save("https://api.example.com/v1/chat/completions", "sample", " ", false, setOf("example.app"), "包裹")
        assertEquals(1L, preferences.state.value.revision)
        val reloaded = AiPreferences(context)
        assertEquals(setOf("example.app"), reloaded.state.value.allowedPackages)
        assertEquals("包裹", reloaded.state.value.interests)
        reloaded.save("https://api.example.com/v1/chat/completions", "sample", null, false, emptySet(), "包裹")
        assertEquals(2L, reloaded.state.value.revision)
    }

    @Test fun incompleteConfigurationCannotEnableAndDoesNotMutateSavedState() {
        val preferences = AiPreferences(context)
        assertThrows(IllegalArgumentException::class.java) {
            preferences.save("https://api.example.com/v1/chat/completions", "sample", null, true, emptySet(), "")
        }
        assertEquals(AiSettings(), preferences.state.value)
        assertThrows(IllegalArgumentException::class.java) {
            preferences.save("https://api.example.com/v1/chat/completions", "", null, true, emptySet(), "")
        }
        assertEquals(AiSettings(), preferences.state.value)
    }

    @Test fun changingHostCannotReusePreviouslySavedKey() {
        // The dummy ciphertext is never decrypted; this checks authorization before any crypto/network.
        context.getSharedPreferences("smart_sorting", Context.MODE_PRIVATE).edit()
            .putString("endpoint", "https://original.example.com/v1/chat/completions")
            .putString("key_ciphertext", "dummy-ciphertext")
            .putString("key_iv", "dummy-iv").commit()
        val preferences = AiPreferences(context)
        assertThrows(IllegalArgumentException::class.java) {
            preferences.save("https://different.example.com/v1/chat/completions", "sample", null, false, emptySet(), "")
        }
        assertEquals("https://original.example.com/v1/chat/completions", preferences.state.value.endpoint)
        preferences.clearKey()
        assertFalse(preferences.state.value.enabled)
        assertFalse(preferences.state.value.hasKey)
        assertEquals("", preferences.apiKey())
        preferences.save("https://different.example.com/v1/chat/completions", "sample", null, false, emptySet(), "")
        assertEquals("https://different.example.com/v1/chat/completions", preferences.state.value.endpoint)
    }
}
