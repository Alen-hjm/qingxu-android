package com.qingxu.app.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.ui.QingxuTheme
import com.qingxu.app.feature.settings.AiSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiSourcePrivacyUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val apps = listOf(SourceApp("chat.example", "微信"), SourceApp("shop.example", "购物应用"))
    private val selected = apps.map { it.packageName }.toSet()
    private val configured = AiSettings(endpoint = "https://api.example.com", model = "example-model", hasKey = true)

    @Test fun localOnlyOverridesSavedCloudConsentAndIsExcludedOnSave() {
        var savedAllowed: Set<String>? = null
        compose.setContent { QingxuTheme { Surface {
            AiSettingsScreen(configured.copy(enabled = true, allowedPackages = selected), apps, selected, "", emptyList(),
                onSave = { _, _, _, _, allowed, _, _, _, _ -> savedAllowed = allowed },
                onTest = {}, onClearKey = {}, onReanalyze = {}, onRemoveRule = {},
                localOnlyPackages = setOf("chat.example"))
        } } }
        scrollToText("微信")
        sourceCheck("微信").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("仅本机 · 隐私保护").assertIsDisplayed()
        sourceCheck("微信").performTouchInput { click() }
        sourceCheck("微信").assertIsOff()
        sourceCheck("购物应用").assertIsOn().assertIsEnabled()
        scrollToText("保存并启用")
        compose.onNodeWithText("保存并启用").performClick()
        compose.runOnIdle { assertEquals(setOf("shop.example"), savedAllowed) }
    }

    @Test fun otherSourceConsentStillRequiresExplicitSave() {
        var savedAllowed: Set<String>? = null
        compose.setContent { QingxuTheme { Surface {
            AiSettingsScreen(configured, apps, selected, "", emptyList(),
                onSave = { _, _, _, _, allowed, _, _, _, _ -> savedAllowed = allowed },
                onTest = {}, onClearKey = {}, onReanalyze = {}, onRemoveRule = {},
                localOnlyPackages = setOf("chat.example"))
        } } }
        scrollToText("购物应用")
        sourceCheck("购物应用").assertIsOff().assertIsEnabled().performClick().assertIsOn()
        sourceCheck("购物应用").performClick().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertNull(savedAllowed) }
        scrollToText("保存设置")
        compose.onNodeWithText("保存设置").performClick()
        compose.runOnIdle { assertEquals(setOf("shop.example"), savedAllowed) }
    }

    @Test fun localOnlyManagementRemainsReachableWithLargeText() {
        var privacyOpened = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                QingxuTheme { Surface {
                    AiSettingsScreen(configured, apps, selected, "", emptyList(),
                        onSave = { _, _, _, _, _, _, _, _, _ -> },
                        onTest = {}, onClearKey = {}, onReanalyze = {}, onRemoveRule = {},
                        onPrivacySettings = { privacyOpened++ }, localOnlyPackages = setOf("chat.example"))
                } }
            }
        }
        scrollToText("管理本机限制")
        compose.onNodeWithText("管理本机限制").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, privacyOpened) }
        scrollToText("微信")
        sourceCheck("微信").assertIsOff().assertIsNotEnabled()
    }

    private fun sourceCheck(label: String) = compose.onNodeWithContentDescription("允许云端分析${label}的通知")
    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }
}
