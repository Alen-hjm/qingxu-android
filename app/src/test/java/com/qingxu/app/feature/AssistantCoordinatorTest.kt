package com.qingxu.app.feature

import com.qingxu.app.core.ai.*
import com.qingxu.app.core.privacy.PrivacySettings
import com.qingxu.app.data.local.AssistantEntryEdit
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.feature.assistant.AssistantCoordinator
import com.qingxu.app.feature.assistant.AssistantUiState
import com.qingxu.app.feature.assistant.assistantEdit
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

/** Deterministic coordinator tests; transport and complete-state CAS are explicit injected boundaries. */
class AssistantCoordinatorTest {
    private val packageName = "example.mail"
    private val now = 10_000_000L

    private fun entry(key: String, app: String = packageName) = NotificationEntry(
        key, app, "邮件", "项目进展", "会议材料已更新，请查看附件", "mail-channel", now, now, "hash-$key",
        machineBucket = "LATER", machineKind = "message", machineConfidence = .9, analysisSource = "local",
        analysisStatus = "done", storageEncryptionVersion = 1,
    )

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runCurrent() {
            var steps = 0
            while (tasks.isNotEmpty()) {
                check(++steps < 10_000) { "Unexpected recurring coroutine work" }
                tasks.removeFirst().run()
            }
        }
    }

    private data class Request(
        val prompt: String, val candidates: List<AssistantCandidate>, val history: List<AssistantInputMessage>,
    )

    private inner class Harness(
        initial: List<NotificationEntry> = listOf(entry("one")),
        allowed: Set<String> = setOf(packageName),
        selected: Set<String> = setOf(packageName),
        privacySettings: PrivacySettings = PrivacySettings(hideContent = true),
        provider: AiProvider = AiProvider.CHAT_COMPLETIONS,
    ) {
        val dispatcher = QueuedDispatcher()
        private val supervisor = SupervisorJob()
        private val failures = mutableListOf<Throwable>()
        private val scope = CoroutineScope(supervisor + dispatcher + CoroutineExceptionHandler { _, failure -> failures += failure })
        val rows = initial.associateBy { it.notificationKey }.toMutableMap()
        val ai = MutableStateFlow(AiSettings(endpoint = "https://service.invalid/v1/chat/completions",
            model = "test-model", enabled = true, hasKey = true, allowedPackages = allowed, provider = provider))
        val privacy = MutableStateFlow(privacySettings)
        val sources = MutableStateFlow(selected)
        val unlocked = MutableStateFlow(true)
        var sessionValid = true
        var deviceLocked = false
        var beforeReady: suspend () -> Unit = {}
        var beforeReply: suspend () -> Unit = {}
        var beforeEdit: suspend () -> Unit = {}
        var onConnection: suspend () -> Pair<AiSettings, String> = { ai.value to "test-key-never-in-prompt" }
        var candidateRows: (() -> List<NotificationEntry>)? = null
        var answer: (Request) -> AssistantReply = { AssistantReply("这是目前的消息摘要。", emptyList()) }
        val requests = mutableListOf<Request>()
        val editsRequested = mutableListOf<List<AssistantEntryEdit>>()
        var connectionReads = 0
        var surfaceRefreshes = 0
        var analysisResumes = 0
        val gate = Mutex()
        val coordinator = AssistantCoordinator(
            scope, ai, privacy, sources, unlocked, { deviceLocked }, { sessionValid },
            connection = { connectionReads++; onConnection() },
            // Deliberately return even unselected rows: coordinator must enforce its own privacy boundary.
            candidates = { candidateRows?.invoke() ?: rows.values.toList() },
            find = { rows[it] },
            edit = { edits, check ->
                beforeEdit()
                val staged = rows.toMutableMap()
                val applied = mutableListOf<AssistantEntryEdit>()
                for ((before, after) in edits) {
                    check()
                    val current = staged[before.notificationKey] ?: continue
                    if (before.notificationKey != after.notificationKey || current != before) continue
                    val updated = current.copy(userLabel = after.userLabel, previousLabel = after.previousLabel,
                        labelSource = after.labelSource, manualVersion = after.manualVersion,
                        analysisStatus = after.analysisStatus, handledAt = after.handledAt)
                    if (updated != current) {
                        staged[current.notificationKey] = updated
                        applied += AssistantEntryEdit(current, updated)
                    }
                }
                check()
                rows.clear(); rows.putAll(staged)
                editsRequested += edits
                applied
            },
            gate = gate, ensureReady = { beforeReady() }, refreshSurface = { surfaceRefreshes++ },
            reply = { _, _, prompt, candidates, history ->
                val request = Request(prompt, candidates.toList(), history.toList())
                requests += request
                beforeReply()
                answer(request)
            },
            now = { now },
            resumeAnalysis = { analysisResumes++ },
        )
        val state: AssistantUiState get() = coordinator.state.value
        init { drain() }
        fun drain() { dispatcher.runCurrent(); assertTrue("Unexpected coroutine failure: $failures", failures.isEmpty()) }
        fun send(prompt: String = "帮我整理这些消息") { coordinator.updateDraft(prompt); coordinator.send(); drain() }
        fun finish() { supervisor.cancel(); drain() }
    }

    private fun use(harness: Harness, check: (Harness) -> Unit) {
        try { check(harness) } finally { harness.finish() }
    }

    @Test fun missingSourceAuthorizationAndLocalOnlyAppsNeverReachConnectionOrTransport() {
        listOf(
            Harness(allowed = emptySet()),
            Harness(selected = emptySet()),
            Harness(privacySettings = PrivacySettings(localOnlyPackages = setOf(packageName))),
            Harness(provider = AiProvider.JEV),
        ).forEach { harness -> use(harness) {
            it.send()
            assertEquals(0, it.connectionReads)
            assertTrue(it.requests.isEmpty())
            assertFalse(it.state.running)
            assertNotNull(it.state.status.takeIf(String::isNotBlank))
        } }
    }

    @Test fun sensitivePromptAndSensitiveNotificationFieldsNeverLeaveDevice() {
        use(Harness()) {
            it.send("请帮我保存验证码123456")
            assertEquals(0, it.connectionReads)
            assertTrue(it.requests.isEmpty())
            assertTrue(it.state.status.contains("验证码"))
        }
        use(Harness(listOf(entry("body-secret").copy(body = "验证码123456"),
            entry("app-secret").copy(appLabel = "sk-abcdefghijklmnop")))) {
            it.send()
            assertEquals(0, it.connectionReads)
            assertTrue(it.requests.isEmpty())
            assertTrue(it.state.status.contains("暂无可分析"))
        }
    }

    @Test fun optionalLockDisabledAllowsAssistantWithoutAnUnlockedContentSession() {
        use(Harness(privacySettings = PrivacySettings(hideContent = false))) { h ->
            h.sessionValid = false
            h.unlocked.value = false
            h.drain()
            assertFalse(h.state.concealed)
            h.answer = { AssistantReply("建议优先查看", listOf(AssistantChange("n1", AssistantAction.PRIORITIZE))) }
            h.send()
            assertEquals(1, h.requests.size)
            assertNotNull(h.state.proposal)
            h.coordinator.apply(); h.drain()
            assertEquals(UserLabel.IMPORTANT_URGENT, h.rows.getValue("one").userLabel)
            assertTrue(h.state.canUndo)
            assertFalse(h.state.concealed)
            assertFalse(h.sessionValid)
        }
    }

    @Test fun optionalLockDisabledStillRespectsTheSeparateScreenLockCloudPolicy() {
        listOf(true, false).forEach { blockCloud ->
            use(Harness(privacySettings = PrivacySettings(hideContent = false, blockCloudWhenLocked = blockCloud))) { h ->
                h.sessionValid = false
                h.unlocked.value = false
                h.deviceLocked = true
                h.drain()
                assertFalse(h.state.concealed)
                h.send()
                assertEquals(if (blockCloud) 0 else 1, h.requests.size)
                assertEquals(if (blockCloud) 0 else 1, h.connectionReads)
                assertFalse(h.state.running)
            }
        }
        use(Harness(privacySettings = PrivacySettings(hideContent = false, blockCloudWhenLocked = true))) { h ->
            h.sessionValid = false
            h.unlocked.value = false
            h.drain()
            val release = CompletableDeferred<Unit>()
            h.beforeReply = { release.await() }
            h.send()
            assertTrue(h.state.running)
            h.deviceLocked = true
            release.complete(Unit); h.drain()
            assertTrue(h.state.messages.isEmpty())
            assertNull(h.state.proposal)
            assertFalse(h.state.running)
            assertFalse(h.state.concealed)
        }
    }

    @Test fun sendImmediatelyShowsRunningThenSendsOnlyRedactedTemporaryIdsWithinBoundedScope() {
        val permitted = (1..25).map { entry("actual-system-key-$it").copy(
            appLabel = "邮箱 owner@example.com", title = "联系13812345678", body = "资料 https://private.example/doc 联系owner@example.com",
        ) }
        val excluded = listOf(entry("unselected", "other.app"), entry("handled").copy(handledAt = now),
            entry("secret").copy(body = "验证码123456"), entry("wechat", "com.tencent.mm"))
        use(Harness(excluded + permitted, allowed = setOf(packageName, "com.tencent.mm"),
            selected = setOf(packageName, "com.tencent.mm"))) { h ->
            val release = CompletableDeferred<Unit>()
            h.beforeReady = { release.await() }
            h.coordinator.updateDraft("优先看看 https://private.example/plan 发来的消息")
            h.coordinator.send()
            assertTrue(h.state.running)
            assertEquals("", h.state.draft)
            assertEquals(1, h.state.messages.size)
            assertTrue(h.requests.isEmpty())
            h.drain()
            assertTrue(h.state.running)
            release.complete(Unit)
            h.drain()
            val request = h.requests.single()
            assertEquals((1..20).map { "n$it" }, request.candidates.map { it.id })
            assertTrue(request.prompt.contains("[链接]"))
            assertTrue(request.candidates.all { it.title.contains("[号码]") && it.body.contains("[链接]") && it.appLabel.contains("[邮箱]") })
            assertFalse(request.toString().contains("actual-system-key"))
            assertFalse(request.toString().contains("owner@example.com"))
            assertFalse(request.toString().contains("13812345678"))
            assertFalse(request.toString().contains("mail-channel"))
            assertFalse(request.toString().contains("test-key-never-in-prompt"))
            assertFalse(h.state.running)
            assertEquals("最近 20 条未处理消息 · 仅已授权来源", h.state.scopeLabel)
        }
    }

    @Test fun sourceRevocationDuringRequestClearsTransientDataAndDiscardsLateReply() {
        use(Harness()) { h ->
            val release = CompletableDeferred<Unit>()
            h.beforeReply = { withContext(NonCancellable) { release.await() } }
            h.answer = { AssistantReply("不能再显示的旧内容", listOf(AssistantChange("n1", AssistantAction.PRIORITIZE))) }
            h.send()
            assertTrue(h.state.running)
            h.sources.value = emptySet()
            h.drain()
            assertFalse(h.state.running)
            assertTrue(h.state.messages.isEmpty())
            assertNull(h.state.proposal)
            assertEquals("", h.state.draft)
            release.complete(Unit)
            h.drain()
            assertTrue(h.state.messages.isEmpty())
            assertNull(h.state.proposal)
            assertFalse(h.state.canUndo)
            assertTrue(h.editsRequested.isEmpty())
        }
    }

    @Test fun lockingWhileRequestRunsConcealsAndClearsTheConversation() {
        use(Harness()) { h ->
            val release = CompletableDeferred<Unit>()
            h.beforeReply = { release.await() }
            h.send()
            assertTrue(h.state.running)
            h.sessionValid = false
            h.unlocked.value = false
            h.drain()
            assertTrue(h.state.concealed)
            assertTrue(h.state.messages.isEmpty())
            assertFalse(h.state.running)
            assertNull(h.state.proposal)
            assertFalse(h.state.canUndo)
            h.coordinator.updateDraft("锁定后不得发送")
            h.coordinator.send()
            h.drain()
            assertEquals("", h.state.draft)
            assertEquals(1, h.requests.size)
        }
    }

    @Test fun deviceLockAndSessionExpiryAreRecheckedWithoutWaitingForStateFlowEmission() {
        listOf<(Harness) -> Unit>({ it.deviceLocked = true }, { it.sessionValid = false }).forEach { lock ->
            use(Harness()) { h ->
                val release = CompletableDeferred<Unit>()
                h.beforeReply = { release.await() }
                h.send()
                lock(h)
                release.complete(Unit)
                h.drain()
                assertTrue(h.state.messages.isEmpty())
                assertNull(h.state.proposal)
                assertFalse(h.state.running)
                assertTrue(h.editsRequested.isEmpty())
            }
        }
    }

    @Test fun preSendContextCheckRejectsDeletedOrChangedNotificationAndChangedConnection() {
        listOf<(Harness) -> Unit>(
            { it.rows.remove("one") },
            { it.rows["one"] = it.rows.getValue("one").copy(body = "新的正文", contentHash = "new-hash", contentVersion = 2) },
            { it.deviceLocked = true },
        ).forEach { alter ->
            use(Harness()) { h ->
                h.onConnection = { alter(h); h.ai.value to "test-key" }
                h.send()
                assertTrue(h.requests.isEmpty())
                assertTrue(h.state.messages.isEmpty())
                assertFalse(h.state.running)
            }
        }
        use(Harness()) { h ->
            h.onConnection = { h.ai.value.copy(model = "different-model") to "different-key" }
            h.send()
            assertTrue(h.requests.isEmpty())
            assertTrue(h.state.messages.isEmpty())
        }
    }

    @Test fun proposalsRequireConfirmationAndUndoIncludesOnlyRowsActuallyChanged() {
        val first = entry("one")
        val second = entry("two")
        use(Harness(listOf(first, second))) { h ->
            h.answer = { request -> AssistantReply("建议优先查看这两条，请确认。",
                request.candidates.map { AssistantChange(it.id, AssistantAction.PRIORITIZE) }) }
            h.send()
            assertNotNull(h.state.proposal)
            assertEquals(first, h.rows["one"])
            assertEquals(second, h.rows["two"])
            assertTrue(h.editsRequested.isEmpty())
            // Another action independently arrived at exactly the proposed state for row two.
            // It must not be included in this operation's undo just because its state matches after.
            val independent = assistantEdit(second, AssistantAction.PRIORITIZE, now)!!
            h.rows["two"] = independent
            h.coordinator.apply()
            assertTrue(h.state.applying)
            h.drain()
            assertEquals(UserLabel.IMPORTANT_URGENT, h.rows.getValue("one").userLabel)
            assertEquals(independent, h.rows["two"])
            assertTrue(h.state.canUndo)
            assertTrue(h.state.status.startsWith("已整理 1 条，1 条"))
            h.coordinator.undo()
            h.drain()
            assertEquals(first, h.rows["one"])
            assertEquals(independent, h.rows["two"])
            assertEquals(listOf("one"), h.editsRequested.last().map { it.before.notificationKey })
            assertFalse(h.state.canUndo)
            assertEquals(2, h.surfaceRefreshes)
        }
    }

    @Test fun changedNotificationCannotBeOverwrittenByConfirmationOrUndo() {
        use(Harness()) { h ->
            h.answer = { AssistantReply("建议优先看", listOf(AssistantChange("n1", AssistantAction.PRIORITIZE))) }
            h.send()
            val changed = h.rows.getValue("one").copy(body = "已更新消息", contentVersion = 2, contentHash = "hash-v2")
            h.rows["one"] = changed
            h.coordinator.apply(); h.drain()
            assertEquals(changed, h.rows["one"])
            assertFalse(h.state.canUndo)
            assertTrue(h.state.status.startsWith("已整理 0 条，1 条"))
            h.send()
            h.coordinator.apply(); h.drain()
            assertTrue(h.state.canUndo)
            val newer = h.rows.getValue("one").copy(body = "又有新的消息", contentVersion = 3, contentHash = "hash-v3")
            h.rows["one"] = newer
            h.coordinator.undo(); h.drain()
            assertEquals(newer, h.rows["one"])
            assertTrue(h.state.status.startsWith("已撤销 0 条，1 条"))
        }
    }

    @Test fun undoResumesAnalysisOnlyForSuccessfullyRestoredPendingOrRetryRows() {
        listOf("pending", "retry", "retry2", "done").forEach { status ->
            val original = entry("one").copy(analysisStatus = status)
            use(Harness(listOf(original))) { h ->
                h.answer = { AssistantReply("建议优先看", listOf(AssistantChange("n1", AssistantAction.PRIORITIZE))) }
                h.send()
                h.coordinator.apply(); h.drain()
                assertEquals("manual", h.rows.getValue("one").analysisStatus)
                assertEquals(0, h.analysisResumes)
                h.coordinator.undo(); h.drain()
                assertEquals(original, h.rows["one"])
                assertEquals(if (status == "done") 0 else 1, h.analysisResumes)
            }
        }
        use(Harness(listOf(entry("one").copy(analysisStatus = "retry")))) { h ->
            h.answer = { AssistantReply("建议优先看", listOf(AssistantChange("n1", AssistantAction.PRIORITIZE))) }
            h.send()
            h.coordinator.apply(); h.drain()
            h.rows["one"] = h.rows.getValue("one").copy(contentHash = "new-hash", contentVersion = 2)
            h.coordinator.undo(); h.drain()
            assertEquals(0, h.analysisResumes)
            assertEquals("manual", h.rows.getValue("one").analysisStatus)
        }
    }

    @Test fun commitChecksAuthorizationAfterWaitingForGateAndAtWriteBoundary() {
        use(Harness()) { h ->
            h.answer = { AssistantReply("建议优先看", listOf(AssistantChange("n1", AssistantAction.PRIORITIZE))) }
            h.send()
            assertTrue(h.gate.tryLock())
            h.coordinator.apply(); h.drain()
            assertTrue(h.state.applying)
            h.sessionValid = false // Content session can expire before its flow is observed.
            h.gate.unlock(); h.drain()
            assertEquals(entry("one"), h.rows["one"])
            assertTrue(h.editsRequested.isEmpty())
            assertTrue(h.state.messages.isEmpty())
            assertFalse(h.state.applying)
        }
        use(Harness()) { h ->
            h.answer = { AssistantReply("建议优先看", listOf(AssistantChange("n1", AssistantAction.PRIORITIZE))) }
            h.send()
            h.beforeEdit = { h.deviceLocked = true }
            h.coordinator.apply(); h.drain()
            assertEquals(entry("one"), h.rows["one"])
            assertTrue(h.editsRequested.isEmpty())
            assertNull(h.state.proposal)
            assertFalse(h.state.canUndo)
        }
    }

    @Test fun renewedAuthorizationClearsConversationAndNoLongerResendsOldHistory() {
        use(Harness()) { h ->
            h.send("总结消息")
            assertEquals(2, h.state.messages.size)
            h.send("还有什么")
            assertEquals(2, h.requests.last().history.size)
            h.ai.value = h.ai.value.copy(revision = 1)
            h.drain()
            assertTrue(h.state.messages.isEmpty())
            h.send("重新总结")
            assertTrue(h.requests.last().history.isEmpty())
            h.coordinator.clear()
            assertTrue(h.state.messages.isEmpty())
            assertNull(h.state.proposal)
        }
    }

    @Test fun staleHistoryIsNotIncludedAfterTheUnderlyingMessageChanges() {
        use(Harness()) { h ->
            h.send("总结消息")
            h.rows["one"] = h.rows.getValue("one").copy(body = "新内容", contentVersion = 2, contentHash = "new-hash")
            h.send("现在呢")
            assertTrue(h.requests.last().history.isEmpty())
            assertEquals("新内容", h.requests.last().candidates.single().body)
        }
    }

    @Test fun insertingOrReorderingNotificationsResetsHistoryBeforeTemporaryIdsAreReassigned() {
        use(Harness()) { h ->
            h.send("总结消息")
            val inserted = entry("inserted").copy(postedAt = now + 1)
            h.rows["inserted"] = inserted
            h.candidateRows = { listOf(inserted, h.rows.getValue("one")) }
            h.send("接着整理")
            assertTrue(h.requests.last().history.isEmpty())
            assertEquals(listOf("n1", "n2"), h.requests.last().candidates.map { it.id })
            h.candidateRows = { listOf(h.rows.getValue("one"), inserted) }
            h.send("再看看")
            assertTrue(h.requests.last().history.isEmpty())
        }
    }

    @Test fun clearingAnInFlightRequestPreventsLateReplyFromRestoringMessagesOrProposal() {
        use(Harness()) { h ->
            val release = CompletableDeferred<Unit>()
            h.beforeReply = { withContext(NonCancellable) { release.await() } }
            h.answer = { AssistantReply("旧请求的建议", listOf(AssistantChange("n1", AssistantAction.PRIORITIZE))) }
            h.send()
            assertTrue(h.state.running)
            h.coordinator.clear()
            assertTrue(h.state.messages.isEmpty())
            assertFalse(h.state.running)
            release.complete(Unit)
            h.drain()
            assertTrue(h.state.messages.isEmpty())
            assertNull(h.state.proposal)
            assertFalse(h.state.canUndo)
            assertEquals("", h.state.status)
            assertEquals(entry("one"), h.rows["one"])
        }
    }

    @Test fun unknownIdsAndUnsafeHideSuggestionsNeverMutateTheInbox() {
        use(Harness()) { h ->
            h.answer = { AssistantReply("错误的外部标识", listOf(AssistantChange("invented", AssistantAction.HANDLE))) }
            h.send()
            assertNull(h.state.proposal)
            assertTrue(h.editsRequested.isEmpty())
            assertEquals(entry("one"), h.rows["one"])
            assertFalse(h.state.running)
            h.answer = { AssistantReply("隐藏这条普通消息", listOf(AssistantChange("n1", AssistantAction.HIDE))) }
            h.send()
            assertNull(h.state.proposal)
            assertTrue(h.state.status.contains("保护规则"))
            h.coordinator.apply(); h.drain()
            assertTrue(h.editsRequested.isEmpty())
            assertEquals(entry("one"), h.rows["one"])
        }
    }
}
