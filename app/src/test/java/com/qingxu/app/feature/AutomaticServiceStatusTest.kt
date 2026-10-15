package com.qingxu.app.feature

import com.qingxu.app.core.ai.AiProvider
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.privacy.PrivacySettings
import com.qingxu.app.core.processing.ProcessingActivity
import com.qingxu.app.core.processing.ProcessingOutcome
import com.qingxu.app.feature.inbox.automaticServiceStatus
import org.junit.Assert.*
import org.junit.Test

class AutomaticServiceStatusTest {
    private val settings = AiSettings(endpoint = "https://test.invalid/v1/chat/completions", model = "test-model",
        enabled = true, hasKey = true, allowedPackages = setOf("shop.example"), revision = 7)
    private val privacy = PrivacySettings()
    private val source = setOf("shop.example")
    private val request = ProcessingActivity(settingsRevision = settings.revision, provider = settings.provider)

    @Test fun enablingCloudDoesNotClaimMessagesHaveBeenAnalyzed() {
        val message = status()
        assertEquals("AI 已开启 · 等待需要进一步判断的消息", message)
        assertFalse(message.contains("正在"))
        assertFalse(message.contains("已整理"))
        assertTrue(status(settings.copy(enabled = false)).contains("AI 未启用"))
        assertTrue(status(settings.copy(hasKey = false)).contains("尚未配置完整"))
    }

    @Test fun selectedSourcesAndLocalPrivacyOverrideSavedCloudConsent() {
        assertTrue(status(settings.copy(allowedPackages = emptySet())).contains("尚无可分析来源"))
        assertTrue(automaticServiceStatus(settings, privacy, emptySet(), request, 0, false, false).contains("尚无可分析来源"))
        assertTrue(automaticServiceStatus(settings, privacy.copy(localOnlyPackages = source), source,
            request, 0, false, false).contains("尚无可分析来源"))
    }

    @Test fun actualRunningAndFailureStatesBelongToCurrentConfiguration() {
        assertEquals("AI 正在分析消息…", status(activity = request.copy(running = true)))
        assertTrue(status(activity = request.copy(lastOutcome = ProcessingOutcome.FAILED)).contains("连接失败"))
        assertFalse(status(activity = request.copy(running = true, settingsRevision = 6)).contains("正在"))
        assertFalse(status(activity = request.copy(lastOutcome = ProcessingOutcome.FAILED, settingsRevision = 6)).contains("失败"))
        assertFalse(status(activity = request.copy(running = true, provider = AiProvider.JEV)).contains("正在"))
    }

    @Test fun pausedWorkAndStoredAiCountsAreSeparateFromRunning() {
        assertTrue(status(waiting = true).contains("隐私暂停"))
        assertEquals("AI 已整理 3 条 · 其余先按本机规则", status(count = 3))
        assertEquals("Jev 决策服务 已开启 · 等待需要进一步判断的消息", status(settings.copy(provider = AiProvider.JEV)))
    }

    @Test fun concealedStatusDoesNotExposeAnalysisCountOrActivity() {
        val concealed = automaticServiceStatus(settings, privacy, source, request.copy(running = true), 13, false, true)
        assertEquals("内容已遮住 · 解锁后查看整理状态", concealed)
        assertFalse(concealed.contains("13"))
        assertFalse(concealed.contains("正在"))
    }

    private fun status(ai: AiSettings = settings, activity: ProcessingActivity = request, count: Int = 0,
        waiting: Boolean = false) = automaticServiceStatus(ai, privacy, source, activity, count, waiting, false)
}
