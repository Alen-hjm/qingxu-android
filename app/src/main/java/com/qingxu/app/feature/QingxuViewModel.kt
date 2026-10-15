package com.qingxu.app.feature

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qingxu.app.QingxuApplication
import com.qingxu.app.AppContainer
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.notification.NotificationAccess
import com.qingxu.app.notification.NotificationActions
import com.qingxu.app.notification.QingxuNotificationListener
import com.qingxu.app.notification.NotificationConnectionRecovery
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import com.qingxu.app.core.ai.CloudDecisionClient
import com.qingxu.app.core.ai.CloudRequestGuard
import com.qingxu.app.core.ai.AiProvider
import com.qingxu.app.core.ai.AiEndpointMode
import com.qingxu.app.core.ai.AiAuthMode
import com.qingxu.app.core.ai.AiConnectionDraft
import com.qingxu.app.core.ai.AiConnectionTestStatus
import com.qingxu.app.core.ai.AiServiceException
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import com.qingxu.app.core.privacy.deviceIsPrivate
import com.qingxu.app.core.processing.AnalysisScheduler
import com.qingxu.app.data.local.SourceKindRule
import com.qingxu.app.data.local.SenderPriorityRule
import com.qingxu.app.data.local.SenderPreferencePolicy
import com.qingxu.app.core.decision.Decision
import com.qingxu.app.core.decision.effectiveBucket
import com.qingxu.app.core.decision.isLongTermHideEligible
import com.qingxu.app.notification.SurfaceSettings
import kotlinx.coroutines.flow.asStateFlow
import com.qingxu.app.feature.assistant.AssistantCoordinator

data class SourceApp(val packageName: String, val label: String)

data class QingxuUiState(
    val entries: List<NotificationEntry> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val sourceApps: List<SourceApp> = emptyList(),
    val permissionGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val captureFailed: Boolean = false,
    val loading: Boolean = true,
    val listenerRecovering: Boolean = false,
    val lastReceivedAt: Long? = null,
    val deliveryTestRunning: Boolean = false,
    val deliveryTestReceivedAt: Long? = null,
    val deliveryTestMessage: String = "",
)

class QingxuViewModel internal constructor(application: Application, private val container: AppContainer) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as QingxuApplication).container)
    private val apps = MutableStateFlow<List<SourceApp>>(emptyList())
    private val access = MutableStateFlow(NotificationAccess.granted(application))
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private val listenerStatus = combine(NotificationActions.connected, container.repository.captureFailed,
        NotificationConnectionRecovery.recovering) { connected, failed, recovering ->
        Triple(connected, failed, recovering)
    }

    val onboarding = container.onboarding.state
    val aiSettings = container.ai.state
    val privacySettings = container.privacy.state
    val contentUnlocked = container.contentSession.unlocked
    val assistant = AssistantCoordinator(application, container, viewModelScope)
    val processingActivity = container.processor.activity
    private val mutablePrivacyStatus = MutableStateFlow("")
    val privacyStatus = mutablePrivacyStatus.asStateFlow()
    private val mutableAiStatus = MutableStateFlow("")
    val aiStatus = mutableAiStatus.asStateFlow()
    val rules = container.dao.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val prioritySenders = container.dao.observePrioritySenders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val surfaceSettings = container.surfacePreferences.state
    private val mutableConnectionTest = MutableStateFlow(AiConnectionTestStatus())
    val connectionTest = mutableConnectionTest.asStateFlow()
    private var connectionTestJob: Job? = null
    private val connectionTestGeneration = AtomicLong()

    private val inboxState = combine(
        container.repository.entries.catch {
            messageChannel.send("读取本地通知失败，请重新打开轻序")
            emit(emptyList())
        },
        container.sources.selected,
        apps,
        access,
        listenerStatus,
    ) { entries, selected, sourceApps, granted, status ->
        QingxuUiState(entries, selected, sourceApps, granted, status.first, status.second, loading = false,
            listenerRecovering = status.third)
    }
    val state = combine(inboxState, container.delivery.state) { inbox, delivery ->
        inbox.copy(lastReceivedAt = delivery.lastReceivedAt, deliveryTestRunning = delivery.testRunning,
            deliveryTestReceivedAt = delivery.testReceivedAt, deliveryTestMessage = delivery.testMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QingxuUiState())

    fun testNotification() = container.delivery.startTest()

    fun saveSurface(summary: Boolean, removePromotions: Boolean) {
        if (!requirePrivateAccess()) return
        write {
            container.processor.gate.withLock {
                if (!requirePrivateAccess()) return@write
                container.surfacePreferences.save(summary, removePromotions)
            }
            container.surface.refresh()
        }
    }

    private var refreshing = false
    private var appsLoadedAt = 0L
    private var snapshotRequestedAt = 0L

    fun refresh() {
        access.value = NotificationAccess.granted(getApplication())
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (elapsed - snapshotRequestedAt >= 5_000 || snapshotRequestedAt == 0L) {
            snapshotRequestedAt = elapsed
            AnalysisScheduler.enqueue(getApplication())
            if (access.value) QingxuNotificationListener.refreshActive()
        }
        NotificationConnectionRecovery.request(getApplication())
        if (refreshing || (apps.value.isNotEmpty() && elapsed - appsLoadedAt < 60_000)) return
        refreshing = true
        viewModelScope.launch {
            try {
                apps.value = withContext(Dispatchers.IO) { loadApps() }
                appsLoadedAt = android.os.SystemClock.elapsedRealtime()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                messageChannel.send("暂时无法读取应用列表，请稍后重试")
            } finally {
                refreshing = false
            }
        }
    }

    fun reconnectNotifications() {
        access.value = NotificationAccess.granted(getApplication())
        NotificationConnectionRecovery.request(getApplication(), force = true)
    }

    private fun loadApps(): List<SourceApp> {
        val context = getApplication<Application>()
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = manager.queryIntentActivities(intent, 0).map { info ->
            SourceApp(info.activityInfo.packageName, info.activityInfo.applicationInfo.loadLabel(manager).toString())
        }
        val extras = container.sources.selected.value.mapNotNull { packageName ->
            try {
                val info = manager.getApplicationInfo(packageName, 0)
                SourceApp(packageName, manager.getApplicationLabel(info).toString())
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        val collator = Collator.getInstance()
        return (installed + extras).distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun selectSource(packageName: String, selected: Boolean) {
        if (selected && !requirePrivateAccess()) return
        write {
            container.processor.gate.withLock {
                if (selected && !requirePrivateAccess()) return@write
                container.processor.invalidatePolicy()
                container.sources.setSelected(packageName, selected)
                container.dao.requeueForPackages(listOf(packageName))
            }
            if (selected) QingxuNotificationListener.refreshActive()
            AnalysisScheduler.enqueue(getApplication())
            container.surface.refresh()
        }
    }

    fun saveAi(endpoint: String, model: String, key: String?, enabled: Boolean, allowed: Set<String>, interests: String,
        provider: AiProvider, endpointMode: AiEndpointMode, authMode: AiAuthMode) {
        if (!requirePrivateAccess()) return
        connectionDraftChanged()
        viewModelScope.launch {
            try {
                container.ensureStorageReady()
                container.processor.gate.withLock {
                    if (!requirePrivateAccess()) return@launch
                    withContext(Dispatchers.IO) { container.ai.save(endpoint, model, key, enabled, allowed, interests, provider, endpointMode, authMode) }
                    container.processor.invalidatePolicy()
                    container.dao.requeueForPackages(container.sources.selected.value.toList())
                }
                mutableAiStatus.value = "已保存。${if (enabled) "仅允许的来源会交给所选服务分析" else "当前仅在本机整理"}"
                AnalysisScheduler.enqueue(getApplication())
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: IllegalArgumentException) { mutableAiStatus.value = error.message ?: "配置不完整，请检查"
            } catch (_: Exception) { mutableAiStatus.value = "配置保存失败，请重试" }
        }
    }

    fun clearAiKey() {
        if (!requirePrivateAccess()) return
        connectionDraftChanged()
        write {
            container.processor.gate.withLock {
                container.processor.invalidatePolicy()
                withContext(Dispatchers.IO) { container.ai.clearKey() }
                container.dao.requeueForPackages(container.sources.selected.value.toList())
            }
            mutableAiStatus.value = "已移除密钥并关闭云端分析"
            AnalysisScheduler.enqueue(getApplication())
        }
    }

    fun connectionDraftChanged() {
        connectionTestGeneration.incrementAndGet()
        connectionTestJob?.cancel()
        connectionTestJob = null
        mutableConnectionTest.value = AiConnectionTestStatus()
    }

    fun testAi(draft: AiConnectionDraft) {
        if (!requirePrivateAccess()) return
        if (container.privacy.state.value.blockCloudWhenLocked && deviceIsPrivate(getApplication())) {
            mutableConnectionTest.value = AiConnectionTestStatus(message = "设备锁定时不发起连接测试", success = false)
            return
        }
        connectionDraftChanged()
        val generation = connectionTestGeneration.get()
        mutableConnectionTest.value = AiConnectionTestStatus(running = true, message = "正在测试当前填写的配置…")
        connectionTestJob = viewModelScope.launch {
            try {
                val sample = NotificationEntry("connection-test", "demo", "轻序虚构示例", "包裹已到达配送站",
                    "示例订单正在配送中，无需操作。", null, System.currentTimeMillis(), System.currentTimeMillis(), "demo")
                val connection = withContext(Dispatchers.IO) { container.ai.connectionSnapshot(draft) }
                val privacyRevision = container.privacy.state.value.revision
                val guard = CloudRequestGuard {
                    if (connectionTestGeneration.get() != generation ||
                        container.ai.state.value.revision != connection.first.revision ||
                        container.privacy.state.value.revision != privacyRevision ||
                        (container.privacy.state.value.hideContent && !container.contentSession.isUnlocked()) ||
                        (container.privacy.state.value.blockCloudWhenLocked && deviceIsPrivate(getApplication()))) {
                        throw ConnectionTestRevoked()
                    }
                }
                withContext(guard) {
                    guard.check()
                    CloudDecisionClient().classify(connection.first.copy(interests = ""), connection.second, sample)
                }
                guard.check()
                mutableConnectionTest.value = AiConnectionTestStatus(message = "连接成功，示例整理通过；未保存配置或开启上传。", success = true)
            } catch (_: ConnectionTestRevoked) {
                if (connectionTestGeneration.get() == generation) mutableConnectionTest.value =
                    AiConnectionTestStatus(message = "设备状态或配置已变化，已停止连接测试", success = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: AiServiceException) {
                if (connectionTestGeneration.get() == generation) mutableConnectionTest.value =
                    AiConnectionTestStatus(message = error.message ?: "服务测试失败", success = false)
            } catch (error: IllegalArgumentException) {
                if (connectionTestGeneration.get() == generation) mutableConnectionTest.value =
                    AiConnectionTestStatus(message = error.message ?: "请检查当前配置", success = false)
            } catch (_: Exception) {
                if (connectionTestGeneration.get() == generation) mutableConnectionTest.value =
                    AiConnectionTestStatus(message = "无法读取配置或连接服务，请重新填写密钥后重试。", success = false)
            } finally {
                if (connectionTestGeneration.get() == generation && mutableConnectionTest.value.running)
                    mutableConnectionTest.value = AiConnectionTestStatus(message = "连接测试已停止", success = false)
            }
        }
    }

    private class ConnectionTestRevoked : CancellationException("Connection test authorization changed")

    fun reanalyze() = write {
        container.processor.gate.withLock {
            if (!requirePrivateAccess()) return@write
            container.processor.invalidatePolicy()
            container.dao.requeueForPackages(container.sources.selected.value.toList())
        }
        AnalysisScheduler.enqueue(getApplication())
        mutableAiStatus.value = "已安排重新整理，手动纠正的消息保持不变"
    }

    fun hideSimilar(entry: NotificationEntry) = write {
        if (!requirePrivateAccess() || !Decision(entry.effectiveBucket(), entry.machineKind, entry.machineReason,
            entry.machineConfidence, entry.analysisSource).isLongTermHideEligible()) return@write
        container.processor.gate.withLock {
            if (!requirePrivateAccess()) return@write
            container.processor.invalidatePolicy()
            container.dao.saveRule(SourceKindRule(entry.packageName, entry.machineKind, true))
            container.dao.requeueForPackages(listOf(entry.packageName))
        }
        AnalysisScheduler.enqueue(getApplication())
        messageChannel.send("已记住此应用的纯促销偏好，可在高级设置撤销")
    }

    fun removeRule(rule: SourceKindRule) = write {
        container.processor.gate.withLock {
            if (!requirePrivateAccess()) return@write
            container.processor.invalidatePolicy()
            container.dao.deleteRule(rule.packageName, rule.kind)
            container.dao.requeueForPackages(listOf(rule.packageName))
        }
        AnalysisScheduler.enqueue(getApplication())
    }

    fun completeOnboarding() = container.onboarding.complete()
    fun restartOnboarding() = container.onboarding.restart()
    fun classifyExample(label: UserLabel) = container.onboarding.classify(label)
    fun starExample() = container.onboarding.star()
    fun restoreExample() = container.onboarding.restore()
    fun demoExample() = container.onboarding.demo()

    fun setHandled(entry: NotificationEntry, handled: Boolean) = write {
        if (!requirePrivateAccess()) return@write
        container.processor.gate.withLock {
            if (!requirePrivateAccess()) return@write
            if (container.repository.setHandled(entry.notificationKey, handled, entry.contentVersion, entry.contentHash) == 0)
                messageChannel.send("消息内容已更新，请查看后再操作")
        }
        container.surface.refresh()
        if (!handled) AnalysisScheduler.enqueue(getApplication())
    }

    fun prioritizeSender(entry: NotificationEntry) = write {
        if (!requirePrivateAccess()) return@write
        val scope = SenderPreferencePolicy.senderScope(entry) ?: return@write
        container.processor.gate.withLock {
            if (!requirePrivateAccess()) return@write
            container.processor.invalidatePolicy()
            container.dao.savePrioritySender(SenderPriorityRule(entry.packageName, scope))
            container.dao.requeueForPackages(listOf(entry.packageName))
        }
        AnalysisScheduler.enqueue(getApplication())
        messageChannel.send("已记住这个会话优先，可在设置中撤销")
    }

    fun removePriority(rule: SenderPriorityRule) = write {
        if (!requirePrivateAccess()) return@write
        container.processor.gate.withLock {
            if (!requirePrivateAccess()) return@write
            container.processor.invalidatePolicy()
            container.dao.deletePrioritySender(rule.packageName, rule.scopeHash)
            container.dao.requeueForPackages(listOf(rule.packageName))
        }
        AnalysisScheduler.enqueue(getApplication())
        messageChannel.send("已撤销会话优先")
    }

    fun classify(entry: NotificationEntry, label: UserLabel) = write {
        if (!requirePrivateAccess()) return@write
        container.processor.gate.withLock {
            if (!requirePrivateAccess()) return@write
            container.processor.invalidatePolicy()
            if (container.repository.classify(entry.notificationKey, label, entry.contentVersion, entry.contentHash) == 0)
                messageChannel.send("消息内容已更新，请查看后再操作")
        }
        container.surface.refresh()
    }
    fun restore(entry: NotificationEntry) = write {
        if (!requirePrivateAccess()) return@write
        container.processor.gate.withLock {
            if (!requirePrivateAccess()) return@write
            container.processor.invalidatePolicy()
            if (container.repository.restore(entry.notificationKey, entry.contentVersion, entry.contentHash) == 0)
                messageChannel.send("消息状态已更新，请查看当前内容")
        }
        container.surface.refresh()
    }
    fun toggleInterest(entry: NotificationEntry) = write {
        container.processor.gate.withLock {
            if (!requirePrivateAccess()) return@write
            container.processor.invalidatePolicy()
            container.repository.setInterested(entry.notificationKey, !entry.interested)
            container.dao.requeueOne(entry.notificationKey)
        }
        AnalysisScheduler.enqueue(getApplication())
    }

    fun openOriginal(entry: NotificationEntry) {
        if (!requirePrivateAccess()) return
        if (!NotificationActions.open(entry.notificationKey)) {
            val context = getApplication<Application>()
            val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            try {
                if (intent == null) messageChannel.trySend("原通知已失效，请到来源应用查看")
                else {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    messageChannel.trySend("原通知已失效，已打开来源应用")
                }
            } catch (_: Exception) { messageChannel.trySend("无法打开来源应用，请手动查看") }
        }
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                container.ensureStorageReady()
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                messageChannel.send("保存失败，请重试")
            }
        }
    }

    fun lockContent() { container.contentSession.lock(); assistant.clear() }
    fun unlockContent() { container.contentSession.unlock() }
    fun expireContentSession() { container.contentSession.isUnlocked() }
    private fun requirePrivateAccess(): Boolean {
        if (!container.privacy.state.value.hideContent || container.contentSession.isUnlocked()) return true
        messageChannel.trySend("请先验证身份，再修改隐私或查看内容")
        return false
    }

    fun savePrivacy(hideContent: Boolean, blockCloudWhenLocked: Boolean, localOnlyPackages: Set<String>, retentionDays: Int) {
        if (!requirePrivateAccess()) return
        write {
            container.processor.gate.withLock {
                if (!requirePrivateAccess()) return@write
                withContext(Dispatchers.IO) { container.privacy.save(hideContent, blockCloudWhenLocked, localOnlyPackages, retentionDays) }
                container.processor.invalidatePolicy()
                container.dao.requeueForPackages(container.sources.selected.value.toList())
                container.maintainStorage()
            }
            if (hideContent) lockContent()
            mutablePrivacyStatus.value = "隐私设置已保存。已发出的请求无法撤回；后续请求按新设置处理。"
            AnalysisScheduler.enqueue(getApplication())
        }
    }

    fun deleteHistory() {
        if (!requirePrivateAccess()) return
        write {
            container.processor.gate.withLock {
                if (!requirePrivateAccess()) return@write
                container.processor.invalidatePolicy()
                withContext(Dispatchers.IO) { container.clearHistory() }
            }
            mutablePrivacyStatus.value = "本地通知历史已清除；来源应用原通知和你的偏好保持不变"
            container.surface.refresh()
        }
    }
}
