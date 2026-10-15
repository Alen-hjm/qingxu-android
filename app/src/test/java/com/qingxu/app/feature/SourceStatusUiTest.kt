package com.qingxu.app.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.privacy.PrivacySettings
import com.qingxu.app.core.ui.QingxuTheme
import com.qingxu.app.feature.settings.SourceSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val apps = listOf(SourceApp("com.tencent.mm", "微信"), SourceApp("shop.example", "购物应用"))
    private val selected = apps.map { it.packageName }.toSet()
    private val configuredAi = AiSettings(endpoint = "https://gateway.example.com/v1", model = "example-model",
        hasKey = true, enabled = true, allowedPackages = selected)

    @Test fun selectedSourcesShowEffectivePrivacyAndCloudPolicyRatherThanAllowedCheckboxAlone() {
        compose.setContent { QingxuTheme { Surface {
            SourceSettingsScreen(QingxuUiState(sourceApps = apps, selectedPackages = selected, loading = false),
                {}, { _, _ -> }, privacySettings = PrivacySettings(localOnlyPackages = setOf("com.tencent.mm")),
                aiSettings = configuredAi)
        } } }
        scrollTo("微信")
        compose.onNodeWithText("仅本机 · 隐私保护").assertIsDisplayed()
        compose.onNodeWithContentDescription("整理微信的通知").assertIsOn()
        scrollTo("购物应用")
        compose.onNodeWithText("可云端复核 · 锁屏暂停").assertIsDisplayed()
        compose.onNodeWithContentDescription("整理购物应用的通知").assertIsOn()
        compose.onNodeWithText("后台管理", substring = true).assertDoesNotExist()
    }

    @Test fun savedCloudChangesRecomposeTheStatusWithoutChangingSourceSelection() {
        var ai by mutableStateOf(configuredAi.copy(enabled = false))
        val oneApp = listOf(apps.last())
        compose.setContent { QingxuTheme { Surface {
            SourceSettingsScreen(QingxuUiState(sourceApps = oneApp, selectedPackages = setOf("shop.example"), loading = false),
                {}, { _, _ -> }, privacySettings = PrivacySettings(localOnlyPackages = emptySet()), aiSettings = ai)
        } } }
        scrollTo("购物应用")
        compose.onNodeWithText("本机处理 · 云端未开启").assertIsDisplayed()
        compose.runOnIdle { ai = configuredAi.copy(hasKey = false) }
        compose.onNodeWithText("本机处理 · 云端未配置").assertIsDisplayed()
        compose.runOnIdle { ai = configuredAi.copy(allowedPackages = emptySet()) }
        compose.onNodeWithText("本机处理 · 云端未授权").assertIsDisplayed()
        compose.runOnIdle { ai = configuredAi }
        compose.onNodeWithText("可云端复核 · 锁屏暂停").assertIsDisplayed()
        compose.onNodeWithContentDescription("整理购物应用的通知").assertIsOn()
    }

    @Test fun deliveryTestReportsActualStateAndCannotBeStartedTwice() {
        var state by mutableStateOf(QingxuUiState(permissionGranted = true, listenerConnected = true,
            listenerRecovering = true, lastReceivedAt = 1_800_000_000_000L, loading = false))
        var testRequests = 0
        compose.setContent { QingxuTheme { Surface {
            SourceSettingsScreen(state, {}, { _, _ -> }, onTestNotification = {
                testRequests++
                state = state.copy(deliveryTestRunning = true, deliveryTestMessage = "等待接收测试通知")
            })
        } } }
        compose.onNodeWithText("已连接").assertExists()
        compose.onNodeWithText("正在恢复通知连接…").assertDoesNotExist()
        compose.onNodeWithText("最近接收", substring = true).assertExists()
        compose.onNodeWithText("发送测试通知").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, testRequests) }
        compose.onNodeWithText("正在等待测试通知…").assertIsNotEnabled()
        compose.onNodeWithText("等待接收测试通知").assertExists()
        compose.runOnIdle { state = state.copy(deliveryTestRunning = false,
            deliveryTestReceivedAt = 1_800_000_001_000L, deliveryTestMessage = "测试通知已收到") }
        compose.onNodeWithText("测试通知已收到").assertExists()
        compose.onNodeWithText("发送测试通知").assertIsEnabled()
        compose.runOnIdle { state = state.copy(listenerConnected = false, listenerRecovering = false) }
        compose.onNodeWithText("通知连接已断开").assertExists()
        compose.onNodeWithText("发送测试通知").assertIsNotEnabled()
    }

    @Test fun revokedPermissionWinsOverStaleConnectedAndRecoveringState() {
        compose.setContent { QingxuTheme { Surface {
            SourceSettingsScreen(QingxuUiState(permissionGranted = false, listenerConnected = true,
                listenerRecovering = true, deliveryTestMessage = "测试通知已收到", loading = false), {}, { _, _ -> })
        } } }
        compose.onNodeWithText("尚未开启").assertExists()
        compose.onNodeWithText("去开启").assertIsEnabled()
        compose.onNodeWithText("已连接").assertDoesNotExist()
        compose.onNodeWithText("正在恢复通知连接…").assertDoesNotExist()
        compose.onNodeWithText("发送测试通知").assertDoesNotExist()
        compose.onNodeWithText("测试通知已收到").assertDoesNotExist()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }
}
