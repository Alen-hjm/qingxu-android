package com.qingxu.app.feature.assistant

import android.app.Application
import com.qingxu.app.AppContainer
import com.qingxu.app.core.ai.*
import com.qingxu.app.core.decision.displayBucket
import com.qingxu.app.core.privacy.*
import com.qingxu.app.core.processing.AnalysisScheduler
import com.qingxu.app.data.local.AssistantEntryEdit
import com.qingxu.app.data.local.NotificationEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Transient chat. Only local, confirmed proposals can mutate notification state. */
class AssistantCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val ai: StateFlow<AiSettings>,
    private val privacy: StateFlow<PrivacySettings>,
    private val sources: StateFlow<Set<String>>,
    private val unlocked: StateFlow<Boolean>,
    private val deviceLocked: () -> Boolean,
    private val sessionUnlocked: () -> Boolean,
    private val connection: suspend () -> Pair<AiSettings, String>,
    private val candidates: suspend (List<String>) -> List<NotificationEntry>,
    private val find: suspend (String) -> NotificationEntry?,
    private val edit: suspend (List<AssistantEntryEdit>, suspend () -> Unit) -> List<AssistantEntryEdit>,
    private val gate: Mutex,
    private val ensureReady: suspend () -> Unit,
    private val refreshSurface: () -> Unit,
    private val reply: suspend (AiSettings, String, String, List<AssistantCandidate>, List<AssistantInputMessage>) -> AssistantReply,
    private val now: () -> Long = System::currentTimeMillis,
    private val resumeAnalysis: () -> Unit = {},
) {
    constructor(application: Application, container: AppContainer, scope: CoroutineScope) : this(
        scope, container.ai.state, container.privacy.state, container.sources.selected, container.contentSession.unlocked,
        { deviceIsPrivate(application) }, { container.contentSession.isUnlocked() },
        { withContext(Dispatchers.IO) { container.ai.connectionSnapshot() } },
        { container.dao.assistantCandidates(it, 80) }, { container.dao.find(it) },
        { edits, check -> container.dao.applyAssistantEditsReturning(edits, check) }, container.processor.gate,
        { container.maintainStorage() }, { container.surface.refresh() },
        { settings, key, prompt, rows, history -> NotificationAssistantClient().reply(settings, key, prompt, rows, history) },
        resumeAnalysis = { AnalysisScheduler.enqueue(application) },
    )

    private val mutable = MutableStateFlow(AssistantUiState())
    val state = mutable.asStateFlow()
    private val generation = AtomicLong()
    private var job: Job? = null
    private var pending: List<AssistantEntryEdit> = emptyList()
    private var undoEdits: List<AssistantEntryEdit> = emptyList()
    private var history = emptyList<AssistantInputMessage>()
    private var historyContext = emptyList<NotificationEntry>()
    private var proposalAuthorization: Authorization? = null
    private var undoAuthorization: Authorization? = null

    private data class Authorization(val ai: AiSettings, val privacy: PrivacySettings,
        val sources: Set<String>, val unlocked: Boolean)
    private fun authorization() = Authorization(ai.value, privacy.value, sources.value,
        !privacy.value.hideContent || unlocked.value)
    private var observed = authorization()

    init {
        updateAvailability()
        scope.launch {
            combine(ai, privacy, sources, unlocked) { _, _, _, _ -> authorization() }.collect { latest ->
                if (latest != observed) {
                    observed = latest
                    reset("范围或隐私状态已变化，请重新开始对话")
                }
                updateAvailability()
            }
        }
    }

    private fun allowedPackages() = sources.value.intersect(ai.value.allowedPackages) - privacy.value.localOnlyPackages
    private fun updateAvailability() {
        val settings = ai.value
        mutable.value = mutable.value.copy(configured = settings.enabled && settings.hasKey &&
            settings.endpoint.isNotBlank() && settings.model.isNotBlank(), chatSupported = settings.provider != AiProvider.JEV,
            hasAuthorizedSources = allowedPackages().isNotEmpty(), concealed = privacy.value.hideContent && !sessionUnlocked(),
            scopeLabel = if (mutable.value.scopeLabel.isBlank()) "已授权来源 · 最近最多 20 条未处理消息" else mutable.value.scopeLabel)
    }

    fun updateDraft(text: String) {
        if (mutable.value.running || mutable.value.applying || mutable.value.proposal != null) return
        updateAvailability()
        if (!mutable.value.concealed) mutable.value = mutable.value.copy(draft = text.take(2000))
    }

    fun clear() = reset("")

    private fun reset(message: String) {
        generation.incrementAndGet()
        job?.cancel(); job = null
        pending = emptyList(); undoEdits = emptyList(); history = emptyList(); historyContext = emptyList()
        proposalAuthorization = null; undoAuthorization = null
        mutable.value = AssistantUiState(status = message)
        updateAvailability()
    }

    private fun checkAuthorization(expected: Authorization, token: Long) {
        if (generation.get() != token || authorization() != expected ||
            (privacy.value.hideContent && !sessionUnlocked()) ||
            (privacy.value.blockCloudWhenLocked && deviceLocked())) throw AssistantRevoked()
        if (!ai.value.enabled || !ai.value.hasKey || allowedPackages().isEmpty() || ai.value.provider == AiProvider.JEV)
            throw AssistantRevoked()
    }

    private fun permitted(entry: NotificationEntry): Boolean = entry.packageName in sources.value &&
        entry.postedAt > privacy.value.deletedBefore &&
        (privacy.value.retentionDays == 0 || entry.updatedAt >= now() - privacy.value.retentionDays * 86_400_000L) &&
        CloudPrivacyGate.permission(entry, ai.value, privacy.value, deviceLocked()) == CloudPermission.ALLOWED &&
        !CloudPrivacyGate.containsSensitiveSecret(entry.appLabel)

    private fun sameContent(first: NotificationEntry, second: NotificationEntry) =
        first.notificationKey == second.notificationKey && first.packageName == second.packageName &&
            first.contentVersion == second.contentVersion && first.contentHash == second.contentHash &&
            first.title == second.title && first.body == second.body && first.appLabel == second.appLabel

    private suspend fun checkContext(rows: List<NotificationEntry>, expected: Authorization, token: Long) {
        checkAuthorization(expected, token)
        for (entry in rows) {
            val latest = find(entry.notificationKey) ?: throw AssistantRevoked()
            if (!sameContent(entry, latest) || !permitted(latest)) throw AssistantRevoked()
        }
        checkAuthorization(expected, token)
    }

    fun send() {
        updateAvailability()
        val ui = mutable.value
        if (ui.running || ui.applying || ui.proposal != null || ui.concealed) return
        if (!ui.configured || !ui.chatSupported || !ui.hasAuthorizedSources) {
            mutable.value = ui.copy(status = "先连接支持对话的服务，并选择允许分析的来源")
            return
        }
        val prompt = ui.draft.trim()
        if (prompt.isBlank()) return
        if (CloudPrivacyGate.containsSensitiveSecret(prompt)) {
            mutable.value = ui.copy(status = "这段话可能含验证码或密钥，请移除后再发送")
            return
        }
        val expected = authorization()
        val token = generation.incrementAndGet()
        mutable.value = ui.copy(running = true, status = "", draft = "",
            messages = (ui.messages + AssistantMessage("u$token", prompt, true)).takeLast(40))
        job = scope.launch {
            try {
                checkAuthorization(expected, token)
                ensureReady()
                val rows = candidates(allowedPackages().toList()).filter { it.handledAt == null && permitted(it) }.take(20)
                if (rows.isEmpty()) {
                    mutable.value = mutable.value.copy(status = "最近的消息中暂无可分析内容。仅本机或敏感消息不会发送；新消息收到后再试。", draft = prompt)
                    return@launch
                }
                // Never resend a summary of content that has changed or been deleted since the last turn.
                if (historyContext.size != rows.size || historyContext.indices.any {
                        !sameContent(historyContext[it], rows[it]) }) history = emptyList()
                val safeHistory = history.takeLast(6).filterNot { CloudPrivacyGate.containsSensitiveSecret(it.text) }
                    .map { it.copy(text = CloudPrivacyGate.redact(it.text)) }
                val safePrompt = CloudPrivacyGate.redact(prompt)
                val connection = connection()
                if (connection.first != expected.ai) throw AssistantRevoked()
                val inputs = rows.mapIndexed { index, row -> CloudPrivacyGate.prepare(row).let { safe ->
                    AssistantCandidate("n${index + 1}", safe.appLabel, safe.title, safe.body, row.displayBucket().name, row.postedAt)
                } }
                mutable.value = mutable.value.copy(scopeLabel = "最近 ${rows.size} 条未处理消息 · 仅已授权来源")
                val guard = CloudRequestGuard { checkContext(rows, expected, token) }
                val result = withContext(guard) {
                    guard.check()
                    reply(connection.first, connection.second, safePrompt, inputs, safeHistory)
                }
                guard.check()
                val indexed = inputs.mapIndexed { index, input -> input.id to rows[index] }.toMap()
                // Validate again at the application boundary, even for alternative client implementations.
                require(result.changes.size <= 20 && result.changes.map { it.id }.distinct().size == result.changes.size)
                pending = result.changes.mapNotNull { change ->
                    val before = indexed[change.id] ?: throw IllegalArgumentException("服务返回了未知消息，请重试")
                    assistantEdit(before, change.action, now())?.let { AssistantEntryEdit(before, it) }
                }
                proposalAuthorization = expected
                val items = result.changes.mapNotNull { change ->
                    val before = indexed[change.id] ?: return@mapNotNull null
                    if (pending.none { it.before.notificationKey == before.notificationKey }) return@mapNotNull null
                    AssistantProposalItem(change.id, before.title, change.action.label(), before.appLabel)
                }
                history = (safeHistory + AssistantInputMessage("user", safePrompt) + AssistantInputMessage("assistant", result.text)).takeLast(6)
                historyContext = rows
                mutable.value = mutable.value.copy(messages = (mutable.value.messages + AssistantMessage("a$token", result.text)).takeLast(40),
                    proposal = if (items.isEmpty()) null else AssistantProposal("p$token", items = items),
                    status = when {
                        result.changes.size > pending.size -> "部分建议不符合消息保护规则，已保留原状。其余改动需确认后生效。"
                        pending.isNotEmpty() -> "这是整理建议，确认后才会改动收件箱。"
                        else -> "已分析 ${rows.size} 条消息，收件箱未改动。"
                    })
            } catch (_: AssistantRevoked) {
                if (generation.get() == token) reset("消息或授权已变化，请重新发送")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: AiServiceException) {
                if (generation.get() == token) mutable.value = mutable.value.copy(status = error.message ?: "服务暂时不可用，请重试", draft = prompt)
            } catch (_: Exception) {
                if (generation.get() == token) mutable.value = mutable.value.copy(status = "整理未完成，请检查连接后重试。收件箱未改动。", draft = prompt)
            } finally {
                if (generation.get() == token) mutable.value = mutable.value.copy(running = false)
            }
        }
    }

    fun dismiss() {
        if (mutable.value.running || mutable.value.applying) return
        pending = emptyList(); proposalAuthorization = null
        history = emptyList(); historyContext = emptyList()
        mutable.value = mutable.value.copy(proposal = null, status = "已取消，收件箱未改动")
    }

    fun apply() = commit(undo = false)
    fun undo() = commit(undo = true)

    private fun commit(undo: Boolean) {
        val ui = mutable.value
        if (ui.running || ui.applying || ui.concealed || (undo && ui.proposal != null)) return
        val edits = if (undo) undoEdits.map { AssistantEntryEdit(it.after, it.before) } else pending
        val expected = (if (undo) undoAuthorization else proposalAuthorization) ?: return
        if (edits.isEmpty()) return
        val token = generation.get()
        mutable.value = ui.copy(applying = true, status = "")
        job = scope.launch {
            try {
                val applied = gate.withLock {
                    checkAuthorization(expected, token)
                    ensureReady()
                    // Full before-state CAS inside Room skips changed/deleted rows rather than overwriting them.
                    edit(edits.filter { permitted(it.before) }) { checkAuthorization(expected, token) }
                }
                checkAuthorization(expected, token)
                history = emptyList(); historyContext = emptyList()
                if (undo) { undoEdits = emptyList(); undoAuthorization = null }
                else {
                    undoEdits = applied; undoAuthorization = expected
                    pending = emptyList(); proposalAuthorization = null
                }
                val skipped = edits.size - applied.size
                mutable.value = mutable.value.copy(proposal = null, canUndo = !undo && applied.isNotEmpty(),
                    undoLabel = "已整理 ${applied.size} 条",
                    status = "已${if (undo) "撤销" else "整理"} ${applied.size} 条" +
                        if (skipped > 0) "，$skipped 条状态已变化，保持原状" else "")
                refreshSurface()
                if (undo && applied.any { it.after.analysisStatus in setOf("pending", "retry", "retry2") }) resumeAnalysis()
            } catch (_: AssistantRevoked) {
                if (generation.get() == token) reset("授权已变化，请解锁后核对收件箱")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (generation.get() == token) mutable.value = mutable.value.copy(status = "保存失败，请重试")
            } finally {
                if (generation.get() == token) mutable.value = mutable.value.copy(applying = false)
            }
        }
    }

    private class AssistantRevoked : CancellationException("Assistant authorization changed")
}
