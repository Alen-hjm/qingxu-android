package com.qingxu.app.feature

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qingxu.app.QingxuApplication
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.notification.NotificationAccess
import com.qingxu.app.notification.NotificationActions
import com.qingxu.app.notification.QingxuNotificationListener
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
import com.qingxu.app.core.privacy.deviceIsPrivate
import com.qingxu.app.core.processing.AnalysisScheduler
import com.qingxu.app.data.local.SourceKindRule
import kotlinx.coroutines.flow.asStateFlow

data class SourceApp(val packageName: String, val label: String)

data class QingxuUiState(
    val entries: List<NotificationEntry> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val sourceApps: List<SourceApp> = emptyList(),
    val permissionGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val captureFailed: Boolean = false,
    val loading: Boolean = true,
)

class QingxuViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as QingxuApplication).container
    private val apps = MutableStateFlow<List<SourceApp>>(emptyList())
    private val access = MutableStateFlow(NotificationAccess.granted(application))
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private val listenerStatus = combine(NotificationActions.connected, container.repository.captureFailed) {
        connected, failed -> connected to failed
    }

    val onboarding = container.onboarding.state
    val aiSettings = container.ai.state
    val privacySettings = container.privacy.state
    val contentUnlocked = container.contentSession.unlocked
    private val mutablePrivacyStatus = MutableStateFlow("")
    val privacyStatus = mutablePrivacyStatus.asStateFlow()
    private val mutableAiStatus = MutableStateFlow("")
    val aiStatus = mutableAiStatus.asStateFlow()
    val rules = container.dao.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var testing = false

    val state = combine(
        container.repository.entries.catch {
            messageChannel.send("读取本地通知失败，请重新打开轻序")
            emit(emptyList())
        },
        container.sources.selected,
        apps,
        access,
        listenerStatus,
    ) { entries, selected, sourceApps, granted, status ->
        QingxuUiState(entries, selected, sourceApps, granted, status.first, status.second, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QingxuUiState())

    private var refreshing = false

    fun refresh() {
        AnalysisScheduler.enqueue(getApplication())
        access.value = NotificationAccess.granted(getApplication())
        if (access.value && !NotificationActions.connected.value) {
            try {
                NotificationListenerService.requestRebind(NotificationAccess.component(getApplication()))
            } catch (_: SecurityException) {
                messageChannel.trySend("请在系统设置中重新开启通知访问")
            }
        }
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                apps.value = withContext(Dispatchers.IO) { loadApps() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                messageChannel.send("暂时无法读取应用列表，请稍后重试")
            } finally {
                refreshing = false
            }
        }
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
                container.processor.invalidatePolicy()
                container.sources.setSelected(packageName, selected)
                container.dao.requeueForPackages(listOf(packageName))
            }
            if (selected) QingxuNotificationListener.refreshActive()
            AnalysisScheduler.enqueue(getApplication())
        }
    }

    fun saveAi(endpoint: String, model: String, key: String?, enabled: Boolean, allowed: Set<String>, interests: String, provider: AiProvider) {
        if (!requirePrivateAccess()) return
        viewModelScope.launch {
            try {
                container.ensureStorageReady()
                container.processor.gate.withLock {
                    withContext(Dispatchers.IO) { container.ai.save(endpoint, model, key, enabled, allowed, interests, provider) }
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

    fun clearAiKey() = write {
        container.processor.gate.withLock {
            container.processor.invalidatePolicy()
            withContext(Dispatchers.IO) { container.ai.clearKey() }
            container.dao.requeueForPackages(container.sources.selected.value.toList())
        }
        mutableAiStatus.value = "已移除密钥并关闭云端分析"
        AnalysisScheduler.enqueue(getApplication())
    }

    fun testAi() {
        if (!requirePrivateAccess()) return
        if (container.privacy.state.value.blockCloudWhenLocked && deviceIsPrivate(getApplication())) {
            mutableAiStatus.value = "设备锁定时不发起连接测试"
            return
        }
        if (testing) return
        testing = true
        mutableAiStatus.value = "正在发送固定虚构示例测试连接…"
        viewModelScope.launch {
            try {
                val sample = NotificationEntry("connection-test", "demo", "轻序虚构示例", "包裹已到达配送站",
                    "示例订单正在配送中，无需操作。", null, System.currentTimeMillis(), System.currentTimeMillis(), "demo")
                val connection = withContext(Dispatchers.IO) { container.ai.connectionSnapshot() }
                val privacyRevision = container.privacy.state.value.revision
                val guard = CloudRequestGuard {
                    if (container.ai.state.value.revision != connection.first.revision ||
                        container.privacy.state.value.revision != privacyRevision ||
                        (container.privacy.state.value.blockCloudWhenLocked && deviceIsPrivate(getApplication()))) {
                        throw ConnectionTestRevoked()
                    }
                }
                withContext(guard) {
                    guard.check()
                    CloudDecisionClient().classify(connection.first.copy(interests = ""), connection.second, sample)
                }
                mutableAiStatus.value = "连接成功，返回格式正确；未发送真实通知"
            } catch (_: ConnectionTestRevoked) {
                mutableAiStatus.value = "设备状态或配置已变化，已停止连接测试"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableAiStatus.value = "连接测试失败，请检查已保存的地址、模型、密钥和服务状态"
            } finally { testing = false }
        }
    }

    private class ConnectionTestRevoked : CancellationException("Connection test authorization changed")

    fun reanalyze() = write {
        container.processor.gate.withLock {
            container.processor.invalidatePolicy()
            container.dao.requeueForPackages(container.sources.selected.value.toList())
        }
        AnalysisScheduler.enqueue(getApplication())
        mutableAiStatus.value = "已安排重新整理，手动纠正的消息保持不变"
    }

    fun hideSimilar(entry: NotificationEntry) = write {
        if (entry.machineKind !in listOf("marketing", "transaction", "delivery", "event", "message")) return@write
        container.processor.gate.withLock {
            container.processor.invalidatePolicy()
            container.dao.saveRule(SourceKindRule(entry.packageName, entry.machineKind, true))
            container.dao.requeueForPackages(listOf(entry.packageName))
        }
        AnalysisScheduler.enqueue(getApplication())
        messageChannel.send("已设为收起此应用的同类消息，可在智能整理设置中撤销")
    }

    fun removeRule(rule: SourceKindRule) = write {
        container.processor.gate.withLock {
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

    fun classify(entry: NotificationEntry, label: UserLabel) = write {
        if (container.repository.classify(entry.notificationKey, label, entry.contentVersion, entry.contentHash) == 0) {
            messageChannel.send("消息内容已更新，请查看后再操作")
        }
    }
    fun restore(entry: NotificationEntry) = write {
        if (container.repository.restore(entry.notificationKey, entry.contentVersion, entry.contentHash) == 0) {
            messageChannel.send("消息状态已更新，请查看当前内容")
        }
    }
    fun toggleInterest(entry: NotificationEntry) = write {
        container.processor.gate.withLock {
            container.processor.invalidatePolicy()
            container.repository.setInterested(entry.notificationKey, !entry.interested)
            container.dao.requeueOne(entry.notificationKey)
        }
        AnalysisScheduler.enqueue(getApplication())
    }

    fun openOriginal(entry: NotificationEntry) {
        if (!requirePrivateAccess()) return
        if (!NotificationActions.open(entry.notificationKey)) {
            messageChannel.trySend("原通知已失效或没有跳转入口，请到来源应用查看")
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

    fun lockContent() = container.contentSession.lock()
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
                container.processor.invalidatePolicy()
                withContext(Dispatchers.IO) { container.clearHistory() }
            }
            mutablePrivacyStatus.value = "本地通知历史已清除；来源应用原通知和你的偏好保持不变"
        }
    }
}
