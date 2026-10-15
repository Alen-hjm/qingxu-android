package com.qingxu.app.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.qingxu.app.core.ui.QingxuTheme
import com.qingxu.app.feature.assistant.*
import com.qingxu.app.feature.inbox.InboxScreen
import java.io.File
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
class AssistantScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ready = AssistantUiState(configured = true, hasAuthorizedSources = true,
        scopeLabel = "本次提问与已授权应用的最近 20 条待看消息")
    private val proposal = AssistantProposal("suggestion-1", items = listOf(
        AssistantProposalItem("message-1", "周末折扣专场", "移到已收起", "购物应用"),
        AssistantProposalItem("message-2", "今晚 8 点前确认方案", "放到现在看", "工作应用"),
    ))

    @Test fun emptyInboxKeepsAssistantAndServiceSettingsReachable() {
        var assistantOpened = 0
        var settingsOpened = 0
        compose.setContent { QingxuTheme { Surface {
            InboxScreen(QingxuUiState(loading = false), {}, { _, _ -> }, {}, {},
                onAssistant = { assistantOpened++ }, onAiSettings = { settingsOpened++ })
        } } }
        compose.onNodeWithText("本机规则运行中 · AI 未启用").assertIsDisplayed()
        compose.onNodeWithText("和小耳聊聊").performClick()
        compose.onNodeWithText("服务设置").performClick()
        compose.runOnIdle { assertEquals(1, assistantOpened); assertEquals(1, settingsOpened) }
    }

    @Test fun suggestionFillsDraftAndSendingShowsImmediateFeedback() {
        var state by mutableStateOf(ready)
        var sends = 0
        compose.setContent { QingxuTheme { Surface {
            AssistantScreen(state, onDraftChange = { state = state.copy(draft = it) }, onSend = {
                sends++
                state = state.copy(messages = listOf(AssistantMessage("question-1", state.draft, true)),
                    draft = "", running = true)
            }, onApply = {}, onDismiss = {}, onUndo = {}, onSettings = {}, onUnlock = {})
        } } }
        compose.onNodeWithContentDescription("发送给小耳").assertIsNotEnabled()
        compose.onNodeWithText("现在先看什么").performClick()
        compose.runOnIdle { assertEquals(0, sends); assertEquals("现在先看什么", state.draft) }
        compose.onNodeWithContentDescription("发送给小耳").assertIsEnabled().performClick()
        compose.onNodeWithText("正在整理，请稍等…").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送给小耳").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, sends) }
        capture("assistant-thinking")
    }

    @Test fun lockingRemovesAllRetainedSensitiveContentAndOnlyOffersUnlock() {
        var state by mutableStateOf(ready.copy(draft = "敏感草稿", messages = listOf(
            AssistantMessage("answer-1", "敏感回复")), proposal = proposal,
            status = "敏感状态", canUndo = true, undoLabel = "敏感撤销信息"))
        var unlocks = 0
        compose.setContent { QingxuTheme { Surface {
            AssistantScreen(state, {}, {}, {}, {}, {}, {}, { unlocks++ })
        } } }
        compose.onNodeWithText("敏感草稿").assertExists()
        compose.runOnIdle { state = state.copy(concealed = true) }
        listOf("敏感草稿", "敏感回复", "敏感状态", "敏感撤销信息", "周末折扣专场", "今晚 8 点前确认方案")
            .forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("发送给小耳").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空对话").assertDoesNotExist()
        compose.onNodeWithText("解锁查看").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, unlocks) }
    }

    @Test fun changesRequireConfirmationAndAppliedChangesOfferUndo() {
        var state by mutableStateOf(ready.copy(messages = listOf(
            AssistantMessage("answer-1", "找到了两条可以整理的消息。")), proposal = proposal))
        var applies = 0
        var undos = 0
        compose.setContent { QingxuTheme { Surface {
            AssistantScreen(state, {}, {}, onApply = {
                applies++
                state = state.copy(proposal = null, canUndo = true, undoLabel = "已整理 2 条消息")
            }, onDismiss = { state = state.copy(proposal = null) }, onUndo = {
                undos++
                state = state.copy(canUndo = false)
            }, onSettings = {}, onUnlock = {})
        } } }
        compose.onNodeWithText("周末折扣专场").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("移到已收起").assertExists()
        compose.onNodeWithText("今晚 8 点前确认方案").assertExists()
        compose.runOnIdle { assertEquals(0, applies) }
        compose.onNodeWithContentDescription("发送给小耳").assertIsNotEnabled()
        capture("assistant-proposal")
        compose.onNodeWithText("确认应用").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, applies) }
        compose.onNodeWithText("已整理 2 条消息").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("撤销上次整理").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, undos) }
        compose.onNodeWithText("撤销上次整理").assertDoesNotExist()
    }

    @Test fun busyMutationDisablesActionsAndCancellingDoesNotApplyChanges() {
        var state by mutableStateOf(ready.copy(messages = listOf(AssistantMessage("answer-1", "整理建议")),
            proposal = proposal, applying = true, canUndo = true))
        var applies = 0
        var dismisses = 0
        compose.setContent { QingxuTheme { Surface {
            AssistantScreen(state, {}, {}, { applies++ }, {
                dismisses++
                state = state.copy(proposal = null)
            }, {}, {}, {})
        } } }
        compose.onNodeWithText("确认应用").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.onNodeWithContentDescription("清空对话").assertIsNotEnabled()
        compose.onNodeWithText("撤销上次整理").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(applying = false) }
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, dismisses); assertEquals(0, applies) }
        compose.onNodeWithText("确认应用").assertDoesNotExist()
    }

    @Test fun unavailableConfigurationAndUnauthorizedSourcesCannotSend() {
        var state by mutableStateOf(ready.copy(draft = "帮我整理", configured = false))
        var settings = 0
        compose.setContent { QingxuTheme { Surface {
            AssistantScreen(state, {}, {}, {}, {}, {}, { settings++ }, {})
        } } }
        compose.onNodeWithText("配置服务").performScrollTo().performClick()
        compose.onNodeWithContentDescription("发送给小耳").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(configured = true, chatSupported = false) }
        compose.onNodeWithText("当前服务暂不支持对话").assertExists()
        compose.onNodeWithText("更换判断服务").performScrollTo().performClick()
        compose.onNodeWithContentDescription("发送给小耳").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(chatSupported = true, hasAuthorizedSources = false) }
        compose.onNodeWithText("还没有允许分析的消息来源").assertExists()
        compose.onNodeWithText("选择分析范围").performScrollTo().performClick()
        compose.onNodeWithContentDescription("发送给小耳").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(3, settings) }
    }

    @Test fun failureKeepsDraftAndClearIsExplicit() {
        var state by mutableStateOf(ready.copy(draft = "再次整理", status = "连接暂时失败，请重试。",
            messages = listOf(AssistantMessage("question-1", "现在先看什么", true))))
        var clears = 0
        compose.setContent { QingxuTheme { Surface {
            AssistantScreen(state, {}, {}, {}, {}, {}, {}, {}, onClear = {
                clears++
                state = state.copy(messages = emptyList(), status = "", draft = "")
            })
        } } }
        compose.onNodeWithText("连接暂时失败，请重试。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("再次整理").assertExists()
        compose.onNodeWithContentDescription("发送给小耳").assertIsEnabled()
        compose.onNodeWithContentDescription("清空对话").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
        compose.onNodeWithContentDescription("清空对话").assertDoesNotExist()
    }

    @Test fun largeTextKeepsProposalActionsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                QingxuTheme { Surface {
                    AssistantScreen(ready.copy(messages = listOf(AssistantMessage("answer-1", "看看这两条消息。")),
                        proposal = proposal), {}, {}, {}, {}, {}, {}, {})
                } }
            }
        }
        compose.onNodeWithText("确认应用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送给小耳").assertIsDisplayed().assertIsNotEnabled()
        capture("assistant-large-text")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val destination = File("build/visual-checks/$name.png")
            destination.parentFile?.mkdirs()
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
