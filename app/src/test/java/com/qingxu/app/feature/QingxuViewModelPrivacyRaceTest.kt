package com.qingxu.app.feature

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.qingxu.app.AppContainer
import com.qingxu.app.core.ai.AiAuthMode
import com.qingxu.app.core.ai.AiEndpointMode
import com.qingxu.app.core.ai.AiProvider
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.privacy.AesGcmNotificationCipher
import com.qingxu.app.core.privacy.CloudPermission
import com.qingxu.app.core.privacy.CloudPrivacyGate
import com.qingxu.app.core.processing.AnalysisScheduler
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.QingxuDatabase
import com.qingxu.app.data.local.SenderPreferencePolicy
import com.qingxu.app.data.local.SenderPriorityRule
import com.qingxu.app.data.local.SourceKindRule
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.notification.SurfaceSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

/** A pending user action must not outlive the private session while waiting for analysis. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QingxuViewModelPrivacyRaceTest {
    private lateinit var application: Application
    private lateinit var container: AppContainer
    private lateinit var viewModel: QingxuViewModel
    private lateinit var persistedChat: NotificationEntry
    private lateinit var persistedPromotion: NotificationEntry
    private val store = ViewModelStore()
    private val scheduledAnalyses = AtomicInteger()
    private val chat = entry("chat", "com.tencent.mm", "小明", "周末见，记得带书")
    private val promotion = entry("promotion", "com.taobao.taobao", "限时优惠", "限时优惠，马上抢购")
        .copy(machineBucket = "HIDDEN", machineKind = "marketing", machineConfidence = 0.95)

    @Before fun setup() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        container = AppContainer(application)
        container.privacy.save(true, true, container.privacy.state.value.localOnlyPackages, 0)
        container.dao.configureCipher(AesGcmNotificationCipher { SecretKeySpec(ByteArray(32) { 4 }, "AES") })
        container.ensureStorageReady()
        container.sources.setSelected(chat.packageName, true)
        container.sources.setSelected(promotion.packageName, true)
        container.dao.save(chat)
        container.dao.save(promotion)
        persistedChat = container.dao.find(chat.notificationKey)!!
        persistedPromotion = container.dao.find(promotion.notificationKey)!!
        val factory = object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker =
                object : Worker(context, parameters) {
                    override fun doWork(): Result {
                        scheduledAnalyses.incrementAndGet()
                        return Result.success()
                    }
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(application, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        viewModel = QingxuViewModel(application, container)
        store.put("privacy-race", viewModel)
        viewModel.unlockContent()
    }

    @After fun cleanup() {
        store.clear()
        WorkManagerTestInitHelper.closeWorkDatabase()
        val databaseField = AppContainer::class.java.getDeclaredField("database").apply { isAccessible = true }
        (databaseField.get(container) as QingxuDatabase).close()
    }

    @Test fun privacyAndSummaryChangesCannotCommitAfterWaitingSessionLocks() {
        val before = container.privacy.state.value
        whileGateHeldThenLock(
            { viewModel.savePrivacy(false, false, emptySet(), 7) },
            { viewModel.saveSurface(summary = true, removePromotions = true) },
        )
        assertEquals(before, container.privacy.state.value)
        assertEquals(SurfaceSettings(), container.surfacePreferences.state.value)
        assertNoAnalysisScheduled()
    }

    @Test fun notificationCallbacksCannotChangeOrRequeueRowsAfterWaitingSessionLocks() = runBlocking {
        whileGateHeldThenLock(
            { viewModel.toggleInterest(chat) },
            { viewModel.classify(chat, UserLabel.IMPORTANT_URGENT) },
            { viewModel.setHandled(chat, true) },
            { viewModel.restore(promotion) },
            { viewModel.reanalyze() },
        )
        assertEquals(persistedChat, container.dao.find(chat.notificationKey))
        assertEquals(persistedPromotion, container.dao.find(promotion.notificationKey))
        assertEquals(0L, container.processor.policyGeneration)
        assertNoAnalysisScheduled()
    }

    @Test fun storedPreferencesAndSourceOptInCannotCommitAfterWaitingSessionLocks() = runBlocking {
        val existingRule = SourceKindRule("another.shop", "marketing", true)
        val anotherConversation = chat.copy(title = "小红")
        val existingPriority = SenderPriorityRule(chat.packageName, SenderPreferencePolicy.senderScope(anotherConversation)!!)
        container.dao.saveRule(existingRule)
        container.dao.savePrioritySender(existingPriority)
        val selected = container.sources.selected.value
        whileGateHeldThenLock(
            { viewModel.hideSimilar(promotion) },
            { viewModel.removeRule(existingRule) },
            { viewModel.prioritizeSender(chat) },
            { viewModel.removePriority(existingPriority) },
            { viewModel.selectSource("new.source", true) },
        )
        assertEquals(listOf(existingRule), container.dao.rules())
        assertEquals(listOf(existingPriority), container.dao.prioritySenders())
        assertEquals(selected, container.sources.selected.value)
        assertEquals(persistedChat, container.dao.find(chat.notificationKey))
        assertEquals(persistedPromotion, container.dao.find(promotion.notificationKey))
        assertNoAnalysisScheduled()
    }

    @Test fun aiConfigurationAndHistoryDeletionCannotCommitAfterWaitingSessionLocks() = runBlocking {
        val beforeAi = container.ai.state.value
        val beforePrivacy = container.privacy.state.value
        whileGateHeldThenLock(
            { viewModel.saveAi("https://provider.invalid/v1", "test-model", null, false, emptySet(), "",
                AiProvider.CHAT_COMPLETIONS, AiEndpointMode.BASE_URL, AiAuthMode.BEARER) },
            { viewModel.deleteHistory() },
        )
        assertEquals(beforeAi, container.ai.state.value)
        assertEquals(beforePrivacy, container.privacy.state.value)
        assertEquals(persistedChat, container.dao.find(chat.notificationKey))
        assertEquals(persistedPromotion, container.dao.find(promotion.notificationKey))
        assertNoAnalysisScheduled()
    }

    @Test fun enablingOptionalLockRequiresAuthenticationEvenWhenViewingIsAllowed() {
        val localOnly = container.privacy.state.value.localOnlyPackages
        container.privacy.save(false, true, localOnly, 0)
        viewModel.lockContent()
        assertTrue(viewModel.hasContentAccess())
        assertFalse(container.contentSession.isUnlocked())
        val before = container.privacy.state.value
        viewModel.savePrivacy(true, true, localOnly, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, container.privacy.state.value)
        assertFalse(container.privacy.state.value.hideContent)
        assertNoAnalysisScheduled()
    }

    @Test fun authenticatedOptInKeepsTheCurrentSessionUnlockedAndDoesNotRequeueMessages() = runBlocking {
        val localOnly = container.privacy.state.value.localOnlyPackages
        container.privacy.save(false, true, localOnly, 0)
        viewModel.lockContent()
        viewModel.unlockContent()
        viewModel.savePrivacy(true, true, localOnly, 0)
        awaitSuccessfulPrivacySave()
        assertTrue(container.privacy.state.value.hideContent)
        assertTrue(container.contentSession.isUnlocked())
        assertTrue(viewModel.hasContentAccess())
        assertEquals(persistedChat, container.dao.find(chat.notificationKey))
        assertEquals(persistedPromotion, container.dao.find(promotion.notificationKey))
        assertEquals(0L, container.processor.policyGeneration)
        assertNoAnalysisScheduled()
    }

    @Test fun enablingOptionalLockRechecksAuthenticationAfterWaitingForProcessorGate() {
        val localOnly = container.privacy.state.value.localOnlyPackages
        container.privacy.save(false, true, localOnly, 0)
        val before = container.privacy.state.value
        whileGateHeldThenLock({ viewModel.savePrivacy(true, true, localOnly, 0) })
        assertEquals(before, container.privacy.state.value)
        assertFalse(container.privacy.state.value.hideContent)
        assertTrue(viewModel.hasContentAccess())
        assertNoAnalysisScheduled()
    }

    @Test fun changingScreenshotProtectionDoesNotReclassifyMessagesOrChangeCloudPermission() = runBlocking {
        val localOnly = container.privacy.state.value.localOnlyPackages
        container.privacy.save(false, true, localOnly, 0, preventScreenshots = false)
        viewModel.lockContent()
        val before = container.privacy.state.value
        val beforeAi = container.ai.state.value
        val cloud = AiSettings(enabled = true, allowedPackages = setOf(promotion.packageName))
        assertEquals(CloudPermission.ALLOWED, CloudPrivacyGate.permission(promotion, cloud, before, false))
        viewModel.savePrivacy(false, true, localOnly, 0, preventScreenshots = true)
        awaitSuccessfulPrivacySave()
        val after = container.privacy.state.value
        assertTrue(after.preventScreenshots)
        assertFalse(after.hideContent)
        assertFalse(container.contentSession.isUnlocked())
        assertTrue(viewModel.hasContentAccess())
        assertEquals(before.copy(revision = after.revision, preventScreenshots = true), after)
        assertEquals(beforeAi, container.ai.state.value)
        assertEquals(CloudPermission.ALLOWED, CloudPrivacyGate.permission(promotion, cloud, after, false))
        assertEquals(CloudPermission.LOCKED, CloudPrivacyGate.permission(promotion, cloud, after, true))
        assertEquals(persistedChat, container.dao.find(chat.notificationKey))
        assertEquals(persistedPromotion, container.dao.find(promotion.notificationKey))
        assertEquals(0L, container.processor.policyGeneration)
        assertNoAnalysisScheduled()
    }

    private suspend fun awaitSuccessfulPrivacySave() = withTimeout(10_000) {
        while (!viewModel.privacyStatus.value.startsWith("已保存")) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(1)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun whileGateHeldThenLock(vararg actions: () -> Unit) {
        runBlocking { container.processor.gate.lock() }
        try {
            actions.forEach { it() }
            // Run each launch until it suspends at the occupied gate, while authorization is valid.
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(container.contentSession.isUnlocked())
            viewModel.lockContent()
        } finally {
            container.processor.gate.unlock()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(container.contentSession.isUnlocked())
    }

    private fun assertNoAnalysisScheduled() {
        assertEquals(0, scheduledAnalyses.get())
        assertTrue(WorkManager.getInstance(application).getWorkInfosForUniqueWork(AnalysisScheduler.INCOMING_WORK)
            .get(10, TimeUnit.SECONDS).isEmpty())
    }

    private fun entry(key: String, packageName: String, title: String, body: String) = NotificationEntry(
        key, packageName, "来源应用", title, body, null, 1000, 1000, key,
        machineBucket = "LATER", machineKind = "message", machineReason = "本机结果", machineConfidence = 0.9,
        analysisSource = "local", analysisStatus = "done",
    )
}
