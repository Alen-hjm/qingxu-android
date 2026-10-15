package com.qingxu.app.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.ui.QingxuTheme
import com.qingxu.app.core.ui.BrandLaunch
import com.qingxu.app.QingxuApplication
import com.qingxu.app.navigation.QingxuApp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.core.ui.NotificationCard
import com.qingxu.app.data.repository.OnboardingState
import com.qingxu.app.feature.onboarding.OnboardingExample
import com.qingxu.app.feature.inbox.InboxScreen
import com.qingxu.app.feature.filtered.FilteredScreen
import com.qingxu.app.feature.settings.SettingsScreen
import com.qingxu.app.feature.settings.SourceSettingsScreen
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
class GuidedUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exampleShowsAutomaticResultsThenTeachesCorrectionAndRestore() {
        var practice by mutableStateOf(OnboardingState())
        compose.setContent {
            QingxuTheme {
                Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        OnboardingExample(practice,
                            onClassify = { if (it == UserLabel.FILTERED) practice = practice.copy(practice = 1, label = UserLabel.IMPORTANT_URGENT)
                                else practice = practice.copy(label = it) },
                            onStar = { practice = practice.copy(interested = !practice.interested) },
                            onRestore = { practice = practice.copy(practice = 2) })
                    }
                }
            }
        }
        capture("guide-classify")
        compose.onNodeWithText("已收起 · 营销促销").assertExists()
        compose.onNodeWithText("本机整理").assertExists()
        compose.onNodeWithContentDescription("为什么这样整理？").performScrollTo().performClick()
        compose.onNodeWithText("这条收起来", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("恢复到收件箱").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, practice.practice)
            assertEquals(UserLabel.IMPORTANT_URGENT, practice.label)
        }
    }

    @Test fun inboxRendersBrandEmptyState() {
        compose.setContent {
            QingxuTheme {
                Surface { InboxScreen(QingxuUiState(loading = false), {}, { _, _ -> }, {}, {}) }
            }
        }
        compose.onNodeWithText("选择我的应用").assertExists()
        capture("inbox-light")
    }

    @Test fun inboxReconnectRunsRecoveryWithoutNavigatingAndReflectsTheActualConnection() {
        var state by mutableStateOf(QingxuUiState(loading = false, permissionGranted = true,
            selectedPackages = setOf("chat.example")))
        var reconnects = 0
        var settingsRequests = 0
        compose.setContent { QingxuTheme { Surface {
            InboxScreen(state, onSettings = { settingsRequests++ }, onClassify = { _, _ -> },
                onInterest = {}, onOpen = {}, onReconnect = {
                    reconnects++
                    state = state.copy(listenerRecovering = true)
                })
        } } }
        compose.onNodeWithText("通知连接已断开").assertExists()
        capture("inbox-disconnected")
        compose.onNodeWithText("重新连接").performClick()
        compose.runOnIdle { assertEquals(1, reconnects); assertEquals(0, settingsRequests) }
        compose.onNodeWithText("正在恢复通知连接…").assertExists()
        compose.onNodeWithText("重新连接").assertIsNotEnabled()
        capture("inbox-reconnecting")
        compose.onNodeWithText("连接帮助").performClick()
        compose.runOnIdle { assertEquals(1, settingsRequests) }
        compose.runOnIdle { state = state.copy(listenerConnected = true, listenerRecovering = false) }
        compose.onNodeWithText("正在恢复通知连接…").assertDoesNotExist()
        compose.onNodeWithText("通知连接已断开").assertDoesNotExist()
        compose.onNodeWithText("重新连接").assertDoesNotExist()
    }

    @Test fun sourceReconnectUsesRecoveryAndKeepsSystemAndBatteryHelpCollapsed() {
        var state by mutableStateOf(QingxuUiState(loading = false, permissionGranted = true,
            selectedPackages = setOf("chat.example")))
        var reconnects = 0
        var permissionRequests = 0
        compose.setContent { QingxuTheme { Surface {
            SourceSettingsScreen(state, onPermissionSettings = { permissionRequests++ },
                onSelectSource = { _, _ -> }, onReconnect = {
                    reconnects++
                    state = state.copy(listenerRecovering = true)
                })
        } } }
        compose.onNodeWithText("通知连接已断开").assertExists()
        compose.onNodeWithText("后台管理", substring = true).assertDoesNotExist()
        compose.onNodeWithText("重新连接").performClick()
        compose.runOnIdle { assertEquals(1, reconnects); assertEquals(0, permissionRequests) }
        compose.onNodeWithText("正在恢复通知连接…").assertExists()
        compose.onNodeWithText("重新连接").assertIsNotEnabled()
        compose.onNodeWithText("连接帮助").performClick()
        compose.onNodeWithText("后台管理", substring = true).assertExists()
        compose.onNodeWithText("打开系统通知设置").performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests) }
        capture("source-connection-help")
    }

    @Test @Config(qualifiers = "w393dp-h851dp-night-xxhdpi")
    fun darkInboxRenders() {
        compose.setContent {
            QingxuTheme {
                Surface { InboxScreen(QingxuUiState(loading = false), {}, { _, _ -> }, {}, {}) }
            }
        }
        capture("inbox-dark")
    }

    @Test fun settingsSourceSwitchSelectsTheApp() {
        var selected by mutableStateOf(emptySet<String>())
        compose.setContent {
            QingxuTheme {
                Surface { SourceSettingsScreen(QingxuUiState(loading = false, selectedPackages = selected,
                    sourceApps = listOf(SourceApp("chat.example", "微信"), SourceApp("calendar.example", "日历"))),
                    {}, { pkg, enabled -> selected = if (enabled) selected + pkg else selected - pkg }, guideStep = 0) }
            }
        }
        capture("settings-source")
        compose.onNodeWithContentDescription("整理微信的通知").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("chat.example"), selected) }
    }

    @Test @Config(application = QingxuApplication::class)
    fun fullAppStartsWithUsageHintAndCanSkipThenReplay() {
        lateinit var appViewModel: QingxuViewModel
        var unlockRequests = 0
        compose.setContent { QingxuTheme {
            appViewModel = viewModel<QingxuViewModel>()
            QingxuApp(appViewModel, onUnlock = { unlockRequests++ }, onPermissionSettings = {})
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("稍后再学").fetchSemanticsNodes().isNotEmpty() }
        capture("app-first-open")
        compose.onNodeWithText("设置", substring = false).performClick()
        compose.onNodeWithText("整理哪些应用").performClick()
        compose.onNodeWithText("↓ 先解锁，再选择应用").assertExists()
        compose.onNodeWithText("解锁后选择应用").assertExists()
        compose.onNodeWithText("搜索应用").assertExists()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("搜索应用").assertDoesNotExist()
        compose.onNodeWithText("稍后再学").performClick()
        compose.onNodeWithText("设置", substring = false).performClick()
        compose.mainClock.advanceTimeBy(400)
        capture("settings-main")
        compose.onNodeWithText("API 密钥").assertDoesNotExist()
        compose.onNodeWithText("搜索应用").assertDoesNotExist()
        compose.onNodeWithContentDescription("高级设置").performScrollTo().performClick()
        compose.onNodeWithText("先验证，再查看与修改").assertExists()
        compose.onNodeWithText("云端分析（可选）").assertDoesNotExist()
        compose.onNodeWithText("验证身份").performClick()
        compose.runOnIdle { assertEquals(1, unlockRequests) }
        compose.onNodeWithText("云端分析（可选）").assertDoesNotExist()
        compose.runOnIdle { appViewModel.unlockContent() }
        compose.onNodeWithText("云端分析（可选）").assertExists()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("重新体验操作引导"))
        compose.onNodeWithText("重新体验操作引导").performClick()
        compose.onNodeWithText("稍后再学").assertExists()
        compose.onNodeWithText("带我过去").performClick()
        compose.onNodeWithText("↓ 选一个常用应用").assertExists()
        compose.onNodeWithText("稍后再学").assertExists()
    }

    @Test fun sourceGuideMovesFromChoiceToRealPermissionAction() {
        var selected by mutableStateOf(emptySet<String>())
        var permissionRequests = 0
        compose.setContent { QingxuTheme { Surface {
            SourceSettingsScreen(QingxuUiState(loading = false, selectedPackages = selected,
                sourceApps = listOf(SourceApp("chat.example", "微信"))),
                onPermissionSettings = { permissionRequests++ },
                onSelectSource = { pkg, enabled -> selected = if (enabled) selected + pkg else selected - pkg },
                guideStep = if (selected.isEmpty()) 0 else 1)
        } } }
        compose.onNodeWithText("通知访问").assertDoesNotExist()
        compose.onNodeWithContentDescription("整理微信的通知").performScrollTo().performClick()
        compose.onNodeWithText("↑ 开启通知访问").assertExists()
        compose.onNodeWithText("去开启").performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests) }
    }

    @Test fun simpleSettingsKeepsEveryDestinationReachableWithLargeText() {
        var destination = ""
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                QingxuTheme { Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    SettingsScreen(QingxuUiState(loading = false, selectedPackages = setOf("chat.example")),
                        onSources = { destination = "sources" }, onPrivacySettings = { destination = "privacy" },
                        onAdvancedSettings = { destination = "advanced" }, onRestartGuide = { destination = "guide" })
                } }
            }
        }
        capture("settings-content")
        compose.runOnIdle { fontScale = 1.8f }
        capture("settings-large-text")
        compose.onNodeWithText("整理哪些应用").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("sources", destination) }
        compose.onNodeWithText("隐私保护").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("privacy", destination) }
        compose.onNodeWithContentDescription("高级设置").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("advanced", destination) }
        compose.onNodeWithText("重新体验操作引导").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("guide", destination) }
        compose.onNodeWithText("搜索应用").assertDoesNotExist()
    }

    @Test fun sourcePageExplainsUnlockAndStillAllowsStoppingCollection() {
        var locked by mutableStateOf(true)
        var selected by mutableStateOf(setOf("calendar.example"))
        var unlockRequests = 0
        compose.setContent { QingxuTheme { Surface {
            SourceSettingsScreen(QingxuUiState(loading = false, selectedPackages = selected,
                sourceApps = listOf(SourceApp("chat.example", "微信"), SourceApp("calendar.example", "日历"))),
                onPermissionSettings = {},
                onSelectSource = { pkg, enabled -> selected = if (enabled) selected + pkg else selected - pkg },
                requiresUnlock = locked, onUnlock = { unlockRequests++ })
        } } }
        compose.onNodeWithContentDescription("整理微信的通知").assertIsNotEnabled()
        compose.onNodeWithContentDescription("整理日历的通知").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptySet<String>(), selected) }
        compose.onNodeWithText("解锁后选择应用").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, unlockRequests) }
        compose.onNodeWithContentDescription("整理微信的通知").assertIsNotEnabled()
        compose.runOnIdle { locked = false }
        compose.onNodeWithContentDescription("整理微信的通知").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("chat.example"), selected) }
    }

    @Test fun protectedNotificationHidesAllSensitiveSemanticsAndOnlyRequestsUnlock() {
        var unlockRequests = 0
        var contentActions = 0
        val secret = exampleEntry("secret", "NOW", "message").copy(
            appLabel = "保密通信应用", title = "私密联系人发来的标题", body = "这是不可暴露的私人消息正文",
            machineReason = "判断理由也含有私人信息",
        )
        compose.setContent { QingxuTheme { Surface {
            InboxScreen(QingxuUiState(entries = listOf(secret), selectedPackages = setOf(secret.packageName),
                permissionGranted = true, listenerConnected = true, loading = false), onSettings = {},
                onClassify = { _, _ -> contentActions++ }, onInterest = { contentActions++ },
                onOpen = { contentActions++ }, onHideSimilar = { contentActions++ },
                concealContent = true, onUnlock = { unlockRequests++ })
        } } }
        fun assertSensitiveContentAbsent() {
            listOf(secret.appLabel, secret.title, secret.body, secret.machineReason).forEach { value ->
                compose.onNodeWithText(value, useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithContentDescription(value, useUnmergedTree = true).assertDoesNotExist()
            }
        }
        assertSensitiveContentAbsent()
        compose.onNodeWithContentDescription("为什么这样整理？").assertDoesNotExist()
        compose.onNodeWithText("这条收起来").assertDoesNotExist()
        compose.onNodeWithText("内容已隐藏").assertExists()
        capture("privacy-inbox")
        compose.onNodeWithText("解锁查看").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, unlockRequests); assertEquals(0, contentActions) }
        assertSensitiveContentAbsent()
    }

    @Test fun largeTextGuideRemainsScrollableAndActionable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                QingxuTheme { Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        OnboardingExample(OnboardingState(), {}, {}, {})
                    }
                } }
            }
        }
        compose.onNodeWithText("这条收起来").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("为什么这样整理？").performScrollTo().performClick()
        compose.onNodeWithContentDescription("收起说明").assertExists()
    }

    @Test fun automaticInboxShowsUncertainMessagesAndKeepsHiddenMessagesOut() {
        val now = exampleEntry("now", "NOW", "transaction").copy(title = "退款失败，请补充资料", body = "请更新收款信息，以继续退款。")
        val later = exampleEntry("later", "LATER", "delivery").copy(title = "包裹运输中", body = "已离开分拨中心，正在送往下一站。")
        val review = exampleEntry("review", "REVIEW", "unknown").copy(title = "活动有新进展", body = "你关注的活动有新进展。")
        val hidden = exampleEntry("hidden", "HIDDEN", "marketing").copy(title = "促销已收起")
        val manuallyHidden = exampleEntry("manual-hidden", "HIDDEN", "marketing").copy(
            userLabel = UserLabel.FILTERED, labelSource = "manual", manualVersion = 1)
        compose.setContent {
            QingxuTheme { Surface {
                InboxScreen(QingxuUiState(entries = listOf(now, later, review, hidden, manuallyHidden),
                    selectedPackages = setOf("shop.example"), permissionGranted = true,
                    listenerConnected = true, loading = false), {}, { _, _ -> }, {}, {})
            } }
        }
        capture("automatic-inbox")
        compose.onNodeWithText("已自动整理 3 条 · 收起 1 条").assertExists()
        listOf("现在看 · 1", "稍后看 · 1", "其他消息 · 1").forEach { title ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        compose.onNodeWithText("促销已收起").assertDoesNotExist()
        compose.onNodeWithText("待确认", substring = true).assertDoesNotExist()
        compose.onNodeWithText("不必逐条分类", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("其他消息说明").performScrollTo().performClick()
        compose.onNodeWithText("不必逐条分类", substring = true).assertExists()
        compose.onNodeWithText("这条很重要").assertDoesNotExist()
        capture("automatic-other-messages")
    }

    @Test fun localPlacementRemainsClearWhileCloudIsPendingUnavailableOrPaused() {
        var entry by mutableStateOf(exampleEntry("local-first", "LATER", "message").copy(analysisStatus = "pending"))
        compose.setContent { QingxuTheme { Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                NotificationCard(entry, {}, {}, {}, {})
            }
        } } }
        compose.onNodeWithText("本机已整理", substring = false).assertExists()
        val statuses = listOf("retry" to "本机已整理 · 云端待重试", "retry2" to "本机已整理 · 云端待重试",
            "failed" to "本机已整理 · 云端暂不可用", "privacy_waiting" to "本机已整理 · 锁屏暂停云端")
        statuses.forEach { (status, label) ->
            compose.runOnIdle { entry = entry.copy(analysisStatus = status) }
            compose.onNodeWithText(label, substring = false).assertExists()
            compose.onNodeWithText("云端分析", substring = false).assertDoesNotExist()
        }
        capture("local-first-cloud-paused")
        compose.runOnIdle {
            entry = entry.copy(machineBucket = "REVIEW", machineKind = "unknown", machineConfidence = 0.0,
                body = "你收到一条新消息", analysisStatus = "retry")
        }
        compose.onNodeWithText("本机已整理", substring = true).assertDoesNotExist()
        compose.onNodeWithText("云端待重试", substring = false).assertExists()
        compose.runOnIdle { entry = entry.copy(analysisStatus = "local_only") }
        compose.onNodeWithText("保留可见").assertExists()
        compose.onNodeWithText("本机整理", substring = false).assertDoesNotExist()
    }

    @Test fun similarRuleHasExplicitScopeAndRequiresSeparateConfirmation() {
        var ruleCount = 0
        var correctionCount = 0
        compose.setContent {
            QingxuTheme { Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    NotificationCard(exampleEntry("rule", "LATER", "marketing"),
                        onClassify = { correctionCount++ }, onInterest = {}, onRestore = {}, onOpen = {},
                        onHideSimilar = { ruleCount++ })
                }
            } }
        }
        compose.onNodeWithText("这条收起来").assertDoesNotExist()
        compose.onNodeWithText("明确的通知内容，由本机规则整理。").assertDoesNotExist()
        compose.onNodeWithContentDescription("为什么这样整理？").performScrollTo().performClick()
        compose.onNodeWithText("明确的通知内容，由本机规则整理。").assertExists()
        compose.onNodeWithContentDescription("调整整理结果").performScrollTo().performClick()
        compose.onNodeWithText("这条收起来").performClick()
        compose.runOnIdle { assertEquals(1, correctionCount); assertEquals(0, ruleCount) }
        compose.onNodeWithContentDescription("调整整理结果").performScrollTo().performClick()
        compose.onNodeWithText("这类以后少显示").performClick()
        compose.onNodeWithText("为「购物应用」添加一条规则", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, ruleCount) }
        compose.onNodeWithText("添加规则", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, ruleCount) }
    }

    @Test fun automaticallyHiddenMessageCanBeReviewedAndRestored() {
        var restored: String? = null
        compose.setContent {
            QingxuTheme { Surface {
                FilteredScreen(listOf(exampleEntry("hidden-promotion", "HIDDEN", "marketing")),
                    onRestore = { restored = it.notificationKey }, onInterest = {})
            } }
        }
        capture("automatic-hidden")
        compose.onNodeWithText("本机整理").assertExists()
        compose.onNodeWithText("这条收起来").assertDoesNotExist()
        compose.onNodeWithText("恢复到收件箱").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("hidden-promotion", restored) }
    }

    private fun exampleEntry(key: String, bucket: String, kind: String) = NotificationEntry(
        notificationKey = key, packageName = "shop.example", appLabel = "购物应用",
        title = "示例通知", body = "这是用于验证自动整理界面的通知。", channelId = null,
        postedAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, contentHash = key,
        machineBucket = bucket, machineKind = kind, machineReason = "明确的通知内容，由本机规则整理。",
        machineConfidence = .95, analysisStatus = "done", analysisSource = "local",
    )

    @Test fun launchCanBeSkippedAndCompletesWithoutWaitingForData() {
        compose.mainClock.autoAdvance = false
        compose.setContent { QingxuTheme { BrandLaunch { androidx.compose.material3.Text("收件箱已就绪") } } }
        compose.mainClock.advanceTimeBy(240)
        capture("launch-mid-animation")
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("让你听到真正想听的").assertDoesNotExist()
        compose.onNodeWithText("收件箱已就绪").assertExists()
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
}
