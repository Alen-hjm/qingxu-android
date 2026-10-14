package com.qingxu.app.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.ai.AiProvider
import com.qingxu.app.core.ui.QingxuTheme
import com.qingxu.app.data.local.SourceKindRule
import com.qingxu.app.feature.settings.AiSettingsScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val apps = listOf(SourceApp("shop.example", "购物应用"), SourceApp("chat.example", "微信"))
    private val selected = apps.map { it.packageName }.toSet()

    @Test fun sourceConsentIsOffByDefaultAndDraftOnlyAppliesOnSave() {
        var saved: SavedSettings? = null
        compose.setContent { QingxuTheme { Surface {
            AiSettingsScreen(AiSettings(), apps, selected, "", emptyList(),
                onSave = { endpoint, model, key, enabled, allowed, interests, provider ->
                    saved = SavedSettings(endpoint, model, key, enabled, allowed, interests, provider)
                }, onTest = {}, onClearKey = {}, onReanalyze = {}, onRemoveRule = {})
        } } }
        scrollToText("购物应用")
        sourceCheck("购物应用").assertIsOff()
        sourceCheck("微信").assertIsOff()
        sourceCheck("购物应用").performClick()
        sourceCheck("购物应用").assertIsOn()
        compose.runOnIdle { assertNull(saved) }
        scrollToText("保存设置")
        cloudToggle().assertIsOff()
        compose.onNodeWithText("保存设置").performClick()
        compose.runOnIdle {
            assertEquals(setOf("shop.example"), saved!!.allowed)
            assertFalse(saved!!.enabled)
            assertNull(saved!!.key)
        }
        scrollToText("测试连接")
        compose.onNodeWithText("测试连接").assertIsNotEnabled()
    }

    @Test fun connectionDraftSavesValuesAndClearsSecretFromField() {
        val settings = mutableStateOf(AiSettings())
        var saved: SavedSettings? = null
        compose.setContent { QingxuTheme { Surface {
            AiSettingsScreen(settings.value, apps, selected, "", emptyList(),
                onSave = { endpoint, model, key, enabled, allowed, interests, provider ->
                    saved = SavedSettings(endpoint, model, key, enabled, allowed, interests, provider)
                    settings.value = AiSettings(endpoint, model, enabled, allowed, interests, hasKey = true, revision = 1, provider = provider)
                }, onTest = {}, onClearKey = {}, onReanalyze = {}, onRemoveRule = {})
        } } }
        scrollToText("完整 HTTPS 接口地址")
        compose.onNodeWithText("完整 HTTPS 接口地址").performTextInput("https://api.example.com/v1/chat/completions")
        compose.onNodeWithText("模型名称").performScrollTo().performTextInput("example-model")
        compose.onNodeWithText("API 密钥").performScrollTo().performTextInput("test-only-fake-secret")
        scrollToText("关注与偏好")
        compose.onNodeWithText("关注与偏好").performClick()
        scrollToText("关注的内容（可选）")
        compose.onNodeWithText("关注的内容（可选）").performTextInput("摄影，退款")
        scrollToText("购物应用")
        sourceCheck("购物应用").performClick()
        scrollToText("保存设置")
        cloudToggle().performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("保存并启用").performClick()
        compose.runOnIdle {
            assertEquals("https://api.example.com/v1/chat/completions", saved!!.endpoint)
            assertEquals("example-model", saved!!.model)
            assertEquals("test-only-fake-secret", saved!!.key)
            assertEquals("摄影，退款", saved!!.interests)
            assertTrue(saved!!.enabled)
            assertEquals(setOf("shop.example"), saved!!.allowed)
        }
        scrollToText("测试连接")
        compose.onNodeWithText("测试连接").assertIsEnabled()
        compose.onNodeWithText("编辑服务").performScrollTo().performClick()
        scrollToText("密钥已保存，留空保留")
        compose.onNode(hasText("密钥已保存，留空保留") and hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun savedConnectionAndRuleControlsInvokeCallbacksAndRenderPreview() {
        var tested = 0
        var cleared = 0
        var reanalyzed = 0
        var removed: SourceKindRule? = null
        val rule = SourceKindRule("shop.example", "marketing", true)
        compose.setContent { QingxuTheme { Surface {
            AiSettingsScreen(AiSettings(endpoint = "https://api.example.com/v1/chat/completions", model = "example-model",
                hasKey = true, interests = "摄影，设计活动", allowedPackages = setOf("shop.example")),
                apps, selected, "配置已保存，尚未启用云端分析", listOf(rule),
                onSave = { _, _, _, _, _, _, _ -> }, onTest = { tested++ }, onClearKey = { cleared++ },
                onReanalyze = { reanalyzed++ }, onRemoveRule = { removed = it })
        } } }
        compose.onNodeWithText("完整 HTTPS 接口地址").assertDoesNotExist()
        compose.onNodeWithText("关注的内容（可选）").assertDoesNotExist()
        capture("smart-settings")
        scrollToText("允许上传的应用")
        compose.onNodeWithText("勾选应用的名称、通知标题、正文、时间及关注内容会发送到云端。")
            .assertIsDisplayed()
        compose.onNodeWithText("「仅本机」设置优先，仍不会上传。").assertIsDisplayed()
        capture("smart-settings-sources")
        scrollToText("测试连接")
        compose.onNodeWithText("测试连接").performClick()
        compose.onNodeWithText("服务说明").performScrollTo().performClick()
        compose.onNodeWithText("移除密钥并关闭云端").performScrollTo().performClick()
        scrollToText("关注与偏好")
        compose.onNodeWithText("关注与偏好").performClick()
        scrollToText("购物应用 · 营销")
        compose.onNodeWithText("撤销").performClick()
        scrollToText("重新整理已收纳消息")
        compose.onNodeWithText("重新整理已收纳消息").performClick()
        compose.runOnIdle {
            assertEquals(1, tested)
            assertEquals(1, cleared)
            assertEquals(1, reanalyzed)
            assertEquals(rule, removed)
        }
    }

    @Test fun largeTextConsentAndSaveRemainReachable() {
        var saved = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                QingxuTheme { Surface {
                    AiSettingsScreen(AiSettings(), apps, selected, "", emptyList(),
                        onSave = { _, _, _, _, _, _, _ -> saved = true }, onTest = {}, onClearKey = {},
                        onReanalyze = {}, onRemoveRule = {})
                } }
            }
        }
        scrollToText("购物应用")
        sourceCheck("购物应用").assertIsDisplayed().performClick()
        scrollToText("保存设置")
        compose.onNodeWithText("保存设置").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun choosingJevKeepsEndpointAndUploadConsentUnchangedUntilSave() {
        var saved: SavedSettings? = null
        var privacyOpened = 0
        compose.setContent { QingxuTheme { Surface {
            AiSettingsScreen(AiSettings(endpoint = "https://api.example.com/v1/chat/completions", model = "old-model",
                hasKey = true), apps, selected, "", emptyList(),
                onSave = { endpoint, model, key, enabled, allowed, interests, provider ->
                    saved = SavedSettings(endpoint, model, key, enabled, allowed, interests, provider)
                }, onTest = {}, onClearKey = {}, onReanalyze = {}, onRemoveRule = {},
                onPrivacySettings = { privacyOpened++ })
        } } }
        compose.onNodeWithText("隐私与本机处理").performClick()
        compose.runOnIdle { assertEquals(1, privacyOpened) }
        compose.onNodeWithText("编辑服务").performClick()
        compose.onNodeWithText("密钥已保存，留空保留").performScrollTo().performTextInput("discarded-provider-draft")
        scrollToText("Jev")
        compose.onNodeWithText("Jev").performClick()
        compose.onNodeWithText("Jev").assertIsSelected()
        scrollToText("完整 HTTPS 接口地址")
        compose.onNode(hasText("完整 HTTPS 接口地址") and hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,
                AnnotatedString("https://api.example.com/v1/chat/completions")))
        compose.runOnIdle { assertNull(saved) }
        compose.onNode(hasText("API 密钥") and hasSetTextAction()).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .performTextInput("fake-jev-only-key")
        compose.onNodeWithText("完整 HTTPS 接口地址").performScrollTo()
            .performTextReplacement("https://api.typesafe.ai/v1/systemone")
        compose.onNodeWithText("模型名称").performScrollTo().performTextReplacement("jev-latest")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        capture("provider-Jev")
        scrollToText("保存设置")
        cloudToggle().assertIsOff()
        compose.onNodeWithText("保存设置").performClick()
        compose.runOnIdle {
            assertEquals(AiProvider.JEV, saved!!.provider)
            assertEquals("https://api.typesafe.ai/v1/systemone", saved!!.endpoint)
            assertEquals("jev-latest", saved!!.model)
            assertEquals("fake-jev-only-key", saved!!.key)
            assertFalse(saved!!.enabled)
            assertTrue(saved!!.allowed.isEmpty())
        }
    }

    private fun sourceCheck(label: String) = compose.onNodeWithContentDescription("允许云端分析${label}的通知")
    private fun cloudToggle() = compose.onNodeWithContentDescription("启用云端分析")
    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/visual-checks/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private data class SavedSettings(val endpoint: String, val model: String, val key: String?, val enabled: Boolean,
        val allowed: Set<String>, val interests: String, val provider: AiProvider)
}
