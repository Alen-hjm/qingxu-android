package com.qingxu.app.core.processing

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qingxu.app.core.ai.AiPreferences
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.core.ai.CloudRequestGuard
import kotlinx.coroutines.currentCoroutineContext
import com.qingxu.app.core.decision.Decision
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.effectiveBucket
import com.qingxu.app.data.local.*
import com.qingxu.app.data.repository.SourcePreferences
import com.qingxu.app.core.privacy.AesGcmNotificationCipher
import com.qingxu.app.core.privacy.PrivacySettings
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationProcessorTest {
    private lateinit var db: QingxuDatabase
    private lateinit var dao: NotificationDao
    private lateinit var sources: SourcePreferences
    private lateinit var preferences: AiPreferences
    private lateinit var processor: NotificationProcessor
    private var calls = 0
    private var privacy = PrivacySettings()
    private var locked = false
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("smart_sorting", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("notification_sources", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, QingxuDatabase::class.java).build()
        dao = db.notifications(); sources = SourcePreferences(context); preferences = AiPreferences(context)
        dao.configureCipher(AesGcmNotificationCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        sources.setSelected("com.taobao.taobao", true)
        processor = NotificationProcessor(dao, sources, preferences,
            cloudAnalyze = { _, _, _ -> calls++; error("Network must not be called") }, enqueue = {})
    }
    @After fun close() { db.close() }
    private suspend fun capture(key: String, title: String) {
        dao.capture(CapturedNotification(key, "com.taobao.taobao", "淘宝", title, "", null, 100L, 101L, title))
    }
    @Test fun noCloudConfigurationStillSortsPromotionsAndTransactions() = runBlocking {
        capture("promo", "限时优惠，马上抢购")
        capture("refund", "退款失败，请补充资料")
        capture("ambiguous", "订单已发货，限时优惠券待领取")
        processor.processBatch(0)
        assertEquals(DecisionBucket.HIDDEN, dao.find("promo")!!.effectiveBucket())
        assertEquals(DecisionBucket.NOW, dao.find("refund")!!.effectiveBucket())
        assertEquals(DecisionBucket.LATER, dao.find("ambiguous")!!.effectiveBucket())
        assertEquals(0, calls)
        assertEquals(ProcessingActivity(), processor.activity.value)
    }
    @Test fun interestAndStarKeepPromotionsVisibleAndRestoreOverridesAutomation() = runBlocking {
        preferences.save("", "", null, false, emptySet(), "摄影")
        capture("interest", "摄影限时优惠")
        capture("star", "限时优惠，马上抢购")
        dao.setInterested("star", true)
        processor.processBatch(0)
        assertEquals(DecisionBucket.LATER, dao.find("interest")!!.effectiveBucket())
        assertEquals(DecisionBucket.LATER, dao.find("star")!!.effectiveBucket())
        dao.classify("star", UserLabel.FILTERED)
        dao.restore("star")
        dao.requeueForPackages(listOf("com.taobao.taobao"))
        processor.processBatch(0)
        assertEquals(DecisionBucket.LATER, dao.find("star")!!.effectiveBucket())
    }
    @Test fun legacyNonMarketingRuleCannotHideDeliveryOrUrgentTransaction() = runBlocking {
        capture("refund", "退款失败，请补充资料")
        capture("delivery", "包裹已到配送站")
        dao.saveRule(SourceKindRule("com.taobao.taobao", "delivery", true))
        dao.saveRule(SourceKindRule("com.taobao.taobao", "transaction", true))
        processor.processBatch(0)
        assertEquals(DecisionBucket.NOW, dao.find("refund")!!.effectiveBucket())
        assertEquals(DecisionBucket.LATER, dao.find("delivery")!!.effectiveBucket())
        assertEquals("local", dao.find("delivery")!!.analysisSource)
    }

    @Test fun marketingPreferenceCannotHideMixedOrInsufficientContentAndInterestsRemainVisible() = runBlocking {
        dao.saveRule(SourceKindRule("com.taobao.taobao", "marketing", true))
        preferences.save("", "", null, false, emptySet(), "摄影")
        capture("mixed-preference", "订单已发货，限时优惠券待领取")
        capture("pure-preference", "限时优惠，马上抢购")
        capture("interest-preference", "摄影限时优惠")
        processor.processBatch(0)
        assertEquals(DecisionBucket.LATER, dao.find("mixed-preference")!!.effectiveBucket())
        assertEquals(DecisionBucket.HIDDEN, dao.find("pure-preference")!!.effectiveBucket())
        assertEquals("preference", dao.find("pure-preference")!!.analysisSource)
        assertEquals(DecisionBucket.LATER, dao.find("interest-preference")!!.effectiveBucket())
    }
    @Test fun verificationCodeAndDisabledSourceRemainVisible() = runBlocking {
        capture("otp", "验证码123456，限时优惠")
        processor.processBatch(0)
        assertEquals(DecisionBucket.LATER, dao.find("otp")!!.effectiveBucket())
        assertEquals("local", dao.find("otp")!!.analysisSource)
        capture("disabled", "限时优惠，马上抢购")
        sources.setSelected("com.taobao.taobao", false)
        processor.processBatch(0)
        assertEquals("paused", dao.find("disabled")!!.analysisStatus)
        assertEquals(0, calls)
    }

    /** Dummy credentials are test data only; the injected snapshot bypasses Android Keystore. */
    private fun configureFakeCloud(analyze: suspend (AiSettings, String, NotificationEntry) -> Decision) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("smart_sorting", Context.MODE_PRIVATE).edit()
            .putString("endpoint", "https://provider.invalid/v1/chat/completions")
            .putString("model", "fake-model")
            .putBoolean("enabled", true)
            .putStringSet("allowed_packages", setOf("com.taobao.taobao"))
            .putString("key_iv", "dummy-iv")
            .putString("key_ciphertext", "dummy-ciphertext")
            .putLong("revision", 1)
            .commit()
        preferences = AiPreferences(context)
        processor = NotificationProcessor(
            dao, sources, preferences,
            cloudAnalyze = { settings, key, entry -> calls++; analyze(settings, key, entry) },
            connectionSnapshot = { preferences.state.value to "fake-key-never-sent" },
            privacySettings = { privacy }, deviceLocked = { locked },
            enqueue = {},
        )
    }

    private val hiddenCloudResult = Decision(
        DecisionBucket.HIDDEN, "marketing", "模拟云端判断为推广", 0.99, "ai",
    )

    /** Suspends the fake request at a deterministic boundary while a competing user action runs. */
    private suspend fun withPausedCloud(key: String, changeWhileInFlight: suspend () -> Unit) =
        withTimeout(10_000) {
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                configureFakeCloud { _, _, _ ->
                    entered.complete(Unit)
                    release.await()
                    hiddenCloudResult
                }
                capture(key, "订单有新的进展，请查看详情")
                val request = async { processor.processBatch(0) }
                entered.await()
                try {
                    changeWhileInFlight()
                } finally {
                    release.complete(Unit)
                }
                request.await()
            }
        }

    @Test fun fakeCloudSuccessUsesConsistentSettingsAndClassifiesThePendingEntry() = runBlocking {
        configureFakeCloud { settings, key, entry ->
            assertTrue(processor.activity.value.running)
            assertEquals(settings.revision, processor.activity.value.settingsRevision)
            assertEquals("https://provider.invalid/v1/chat/completions", settings.endpoint)
            assertEquals("fake-model", settings.model)
            assertEquals("fake-key-never-sent", key)
            assertEquals("cloud-input", entry.notificationKey)
            hiddenCloudResult
        }
        capture("cloud-success", "春季特惠开始了")
        assertFalse(processor.processBatch(0))
        val result = dao.find("cloud-success")!!
        assertEquals(DecisionBucket.HIDDEN, result.effectiveBucket())
        assertEquals("done", result.analysisStatus)
        assertEquals("ai", result.analysisSource)
        assertEquals(1, calls)
        assertFalse(processor.activity.value.running)
        assertEquals(ProcessingOutcome.SUCCEEDED, processor.activity.value.lastOutcome)
        assertNotNull(processor.activity.value.lastCompletedAt)
    }

    @Test fun cloudFailuresRetryTwiceThenRemainVisibleWithoutFurtherAutomaticRequests() = runBlocking {
        configureFakeCloud { _, _, _ -> error("private-provider-response-not-for-users") }
        capture("cloud-failure", "订单有新的进展，请查看详情")

        for (attempt in 0..2) {
            assertEquals(attempt < 2, processor.processBatch(attempt))
            val result = dao.find("cloud-failure")!!
            assertEquals(listOf("retry", "retry2", "failed")[attempt], result.analysisStatus)
            assertEquals(DecisionBucket.LATER, result.effectiveBucket())
            assertEquals("local", result.analysisSource)
            assertFalse(result.machineReason.contains("private-provider-response-not-for-users"))
            assertEquals(attempt + 1, calls)
            assertFalse(processor.activity.value.running)
            assertEquals(ProcessingOutcome.FAILED, processor.activity.value.lastOutcome)
        }
        assertEquals(0, dao.pendingCount())
        assertFalse(processor.processBatch(0))
        assertEquals(3, calls)
    }

    @Test fun manualCorrectionDuringCloudRequestCannotBeOverwrittenByLateHiddenResult() = runBlocking {
        withPausedCloud("corrected") {
            dao.classify("corrected", UserLabel.IMPORTANT_URGENT)
        }
        val result = dao.find("corrected")!!
        assertEquals(DecisionBucket.NOW, result.effectiveBucket())
        assertEquals("manual", result.labelSource)
        assertEquals("manual", result.analysisStatus)
        assertEquals(result.contentVersion, result.manualVersion)
        assertEquals("unknown", result.machineKind)
        assertEquals(1, calls)
        assertFalse(processor.activity.value.running)
        assertEquals(ProcessingOutcome.DISCARDED, processor.activity.value.lastOutcome)
    }

    @Test fun cancelledCloudRequestStopsActivityWithoutClaimingSuccessOrFailure() = runBlocking {
        configureFakeCloud { _, _, _ -> throw kotlinx.coroutines.CancellationException("test cancellation") }
        capture("cancelled-request", "订单有新的进展，请查看详情")
        try {
            processor.processBatch(0)
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(processor.activity.value.running)
        assertEquals(ProcessingOutcome.DISCARDED, processor.activity.value.lastOutcome)
        assertEquals("pending", dao.find("cancelled-request")!!.analysisStatus)
    }

    @Test fun completionDuringCloudRequestCannotBeChangedByItsLateResult() = runBlocking {
        lateinit var beforeCompletion: NotificationEntry
        withPausedCloud("handled-cloud") {
            beforeCompletion = dao.find("handled-cloud")!!
            assertEquals(1, dao.setHandled(beforeCompletion.notificationKey, true,
                beforeCompletion.contentVersion, beforeCompletion.contentHash))
        }
        val handled = dao.find("handled-cloud")!!
        assertNotNull(handled.handledAt)
        assertEquals(beforeCompletion.machineBucket, handled.machineBucket)
        assertEquals(beforeCompletion.machineReason, handled.machineReason)
        assertEquals(beforeCompletion.analysisSource, handled.analysisSource)
        assertEquals(0, dao.pendingCount())
        assertFalse(processor.processBatch(0))
        assertEquals(1, calls)
    }

    @Test fun explicitConversationPreferencePrioritizesOnlyReadableMatchingChatLocally() = runBlocking {
        sources.setSelected("com.tencent.mm", true)
        sources.setSelected("com.tencent.mobileqq", true)
        val now = System.currentTimeMillis()
        suspend fun chat(key: String, title: String, body: String, pkg: String = "com.tencent.mm") {
            dao.capture(CapturedNotification(key, pkg, if (pkg == "com.tencent.mm") "微信" else "QQ",
                title, body, null, now, now, "$title:$body"))
        }
        chat("preferred-chat", "妈妈", "今天晚点回家吃饭")
        val first = dao.find("preferred-chat")!!
        dao.savePrioritySender(SenderPriorityRule(first.packageName, SenderPreferencePolicy.senderScope(first)!!))
        chat("next-chat", "妈妈", "到家给我发个消息")
        chat("other-chat", "同事", "周末一起吃饭吧")
        chat("other-app", "妈妈", "今天晚点回家吃饭", "com.tencent.mobileqq")
        chat("hidden-chat", "妈妈", "解锁后查看消息")
        processor.processBatch(0)
        assertEquals(DecisionBucket.NOW, dao.find("preferred-chat")!!.effectiveBucket())
        assertEquals(DecisionBucket.NOW, dao.find("next-chat")!!.effectiveBucket())
        assertEquals("preference", dao.find("next-chat")!!.analysisSource)
        assertTrue(dao.find("next-chat")!!.machineReason.contains("这个会话"))
        assertEquals(DecisionBucket.LATER, dao.find("other-chat")!!.effectiveBucket())
        assertEquals(DecisionBucket.LATER, dao.find("other-app")!!.effectiveBucket())
        assertEquals(DecisionBucket.REVIEW, dao.find("hidden-chat")!!.effectiveBucket())
        assertEquals(0, calls)
    }

    @Test fun conversationPreferenceDoesNotOverrideManualCorrectionOrCompletionAndKeepsSafetyReason() = runBlocking {
        sources.setSelected("com.tencent.mm", true)
        val now = System.currentTimeMillis()
        suspend fun chat(key: String, body: String) {
            dao.capture(CapturedNotification(key, "com.tencent.mm", "微信", "家人", body, null, now, now, body))
        }
        chat("manual-chat", "今天晚点回家吃饭")
        val first = dao.find("manual-chat")!!
        dao.savePrioritySender(SenderPriorityRule(first.packageName, SenderPreferencePolicy.senderScope(first)!!))
        dao.classify("manual-chat", UserLabel.IMPORTANT)
        chat("handled-chat", "已到家，晚安")
        val done = dao.find("handled-chat")!!
        dao.setHandled(done.notificationKey, true, done.contentVersion, done.contentHash)
        chat("risk-chat", "我在急诊，你来一下")
        dao.saveRule(SourceKindRule("com.tencent.mm", "message", true))
        dao.saveRule(SourceKindRule("com.tencent.mm", "medical", true))
        processor.processBatch(0)
        assertEquals(DecisionBucket.LATER, dao.find("manual-chat")!!.effectiveBucket())
        assertEquals("manual", dao.find("manual-chat")!!.analysisSource.ifBlank { dao.find("manual-chat")!!.labelSource })
        assertNotNull(dao.find("handled-chat")!!.handledAt)
        assertEquals(DecisionBucket.REVIEW, dao.find("handled-chat")!!.effectiveBucket())
        assertEquals(DecisionBucket.NOW, dao.find("risk-chat")!!.effectiveBucket())
        assertEquals("medical", dao.find("risk-chat")!!.machineKind)
        assertEquals("local", dao.find("risk-chat")!!.analysisSource)
    }

    @Test fun updatedNotificationContentCannotReceiveThePreviousContentsCloudResult() = runBlocking {
        withPausedCloud("updated") {
            capture("updated", "退款失败，请补充资料")
        }
        val updated = dao.find("updated")!!
        assertEquals(2, updated.contentVersion)
        assertEquals("退款失败，请补充资料", updated.title)
        assertEquals("pending", updated.analysisStatus)
        assertEquals(DecisionBucket.REVIEW, updated.effectiveBucket())
        assertEquals("unknown", updated.machineKind)

        // The newly saved content is processed on its own merits on the following pass.
        processor.processBatch(0)
        assertEquals(DecisionBucket.NOW, dao.find("updated")!!.effectiveBucket())
        assertEquals(1, calls)
    }

    @Test fun disablingCloudWhileRequestIsInFlightDiscardsItsLateResult() = runBlocking {
        withPausedCloud("disabled-cloud") {
            processor.gate.withLock {
                val settings = preferences.state.value
                preferences.save(settings.endpoint, settings.model, null, false, settings.allowedPackages, settings.interests)
                processor.invalidatePolicy()
                dao.requeueForPackages(listOf("com.taobao.taobao"))
            }
        }
        assertPendingAndVisible("disabled-cloud")
        processor.processBatch(0)
        assertEquals("local_only", dao.find("disabled-cloud")!!.analysisStatus)
        assertEquals(DecisionBucket.LATER, dao.find("disabled-cloud")!!.effectiveBucket())
        assertEquals(1, calls)
    }

    @Test fun withdrawingCloudSourcePermissionDiscardsLateResultAndPreventsNextRequest() = runBlocking {
        withPausedCloud("cloud-permission") {
            processor.gate.withLock {
                val settings = preferences.state.value
                preferences.save(settings.endpoint, settings.model, null, true, emptySet(), settings.interests)
                processor.invalidatePolicy()
                dao.requeueForPackages(listOf("com.taobao.taobao"))
            }
        }
        assertTrue(preferences.state.value.enabled)
        assertTrue(preferences.state.value.allowedPackages.isEmpty())
        assertPendingAndVisible("cloud-permission")
        processor.processBatch(0)
        assertEquals("local_only", dao.find("cloud-permission")!!.analysisStatus)
        assertEquals(1, calls)
    }

    @Test fun stoppingSourceCollectionDiscardsItsLateCloudResult() = runBlocking {
        withPausedCloud("source-disabled") {
            processor.gate.withLock {
                sources.setSelected("com.taobao.taobao", false)
                processor.invalidatePolicy()
                dao.requeueForPackages(listOf("com.taobao.taobao"))
            }
        }
        assertPendingAndVisible("source-disabled")
        processor.processBatch(0)
        assertEquals("paused", dao.find("source-disabled")!!.analysisStatus)
        assertEquals(DecisionBucket.REVIEW, dao.find("source-disabled")!!.effectiveBucket())
        assertEquals(1, calls)
    }

    @Test fun localPolicyGenerationAloneInvalidatesACloudResultWithoutChangingApiSettings() = runBlocking {
        var revision = 0L
        withPausedCloud("policy-changed") {
            revision = preferences.state.value.revision
            processor.gate.withLock {
                dao.setInterested("policy-changed", true)
                processor.invalidatePolicy()
                dao.requeueOne("policy-changed")
            }
        }
        assertEquals(revision, preferences.state.value.revision)
        assertPendingAndVisible("policy-changed")
        assertTrue(dao.find("policy-changed")!!.interested)
        assertEquals(1, calls)
    }

    @Test fun lockedDeviceDefersCloudWithoutSpendingRetriesAndUnlockResumes() = runBlocking {
        configureFakeCloud { _, _, _ -> hiddenCloudResult }
        locked = true
        capture("locked", "有新的进展，请查看详情")
        assertFalse(processor.processBatch(0))
        assertEquals(0, calls)
        assertEquals("privacy_waiting", dao.find("locked")!!.analysisStatus)
        assertEquals(DecisionBucket.LATER, dao.find("locked")!!.effectiveBucket())
        locked = false
        processor.processBatch(0)
        assertEquals(1, calls)
        assertEquals("done", dao.find("locked")!!.analysisStatus)
    }

    @Test fun localOnlyOverridesAnOtherwiseEnabledCloudSource() = runBlocking {
        configureFakeCloud { _, _, _ -> hiddenCloudResult }
        privacy = privacy.copy(localOnlyPackages = setOf("com.taobao.taobao"))
        capture("private", "有新的进展，请查看详情")
        processor.processBatch(0)
        assertEquals(0, calls)
        assertEquals("local_only", dao.find("private")!!.analysisStatus)
    }

    @Test fun privacyRevocationDiscardsLateResultAndPreventsAnotherRequest() = runBlocking {
        withPausedCloud("privacy-revoked") {
            processor.gate.withLock {
                privacy = privacy.copy(localOnlyPackages = setOf("com.taobao.taobao"), revision = privacy.revision + 1)
                processor.invalidatePolicy()
                dao.requeueForPackages(listOf("com.taobao.taobao"))
            }
        }
        assertPendingAndVisible("privacy-revoked")
        processor.processBatch(0)
        assertEquals(1, calls)
        assertEquals("local_only", dao.find("privacy-revoked")!!.analysisStatus)
    }

    @Test fun deletingHistoryDuringRequestCannotResurrectItsNotification() = runBlocking {
        withPausedCloud("deleted") {
            processor.gate.withLock {
                processor.invalidatePolicy()
                dao.deleteAllNotifications()
            }
        }
        assertNull(dao.find("deleted"))
    }

    @Test fun deletingBatchWhileFirstRequestRunsPreventsUploadingTheRemainingRows() = runBlocking {
        configureFakeCloud { _, _, _ ->
            processor.gate.withLock {
                privacy = privacy.copy(deletedBefore = 200, revision = privacy.revision + 1)
                processor.invalidatePolicy()
                dao.deleteAllNotifications()
            }
            hiddenCloudResult
        }
        capture("first", "有新的进展，请查看详情")
        capture("second", "有其他进展，请查看详情")
        processor.processBatch(0)
        assertEquals(1, calls)
        assertEquals(0, dao.pendingCount())
    }

    @Test fun deletionBeforeTransportBodyWriteCancelsUploadWithoutSpendingRetries() = runBlocking {
        var bytesSent = false
        configureFakeCloud { _, _, _ ->
            processor.gate.withLock {
                processor.invalidatePolicy()
                dao.deleteAllNotifications()
            }
            currentCoroutineContext()[CloudRequestGuard]!!.check()
            bytesSent = true
            hiddenCloudResult
        }
        capture("pre-send", "有新的进展，请查看详情")
        assertFalse(processor.processBatch(0))
        assertFalse(bytesSent)
        assertNull(dao.find("pre-send"))
    }

    @Test fun unsafeHiddenCloudResultIsRejectedUsingOriginalNotification() = runBlocking {
        configureFakeCloud { _, _, _ -> hiddenCloudResult }
        capture("mixed", "订单有新的进展，请查看详情")
        processor.processBatch(0)
        assertEquals(DecisionBucket.REVIEW, dao.find("mixed")!!.effectiveBucket())
    }

    @Test fun wholeLocalBatchIsSortedBeforeTheFirstSlowCloudRequest() = runBlocking {
        withTimeout(10_000) {
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                configureFakeCloud { _, _, _ -> entered.complete(Unit); release.await(); hiddenCloudResult }
                capture("cloud-first", "有新的进展，请查看详情")
                capture("local-next", "退款失败，请补充资料")
                capture("promotion-next", "限时优惠，马上抢购")
                val request = async { processor.processBatch(0) }
                entered.await()
                try {
                    assertEquals(DecisionBucket.NOW, dao.find("local-next")!!.effectiveBucket())
                    assertEquals("done", dao.find("local-next")!!.analysisStatus)
                    assertEquals(DecisionBucket.HIDDEN, dao.find("promotion-next")!!.effectiveBucket())
                    assertEquals(DecisionBucket.LATER, dao.find("cloud-first")!!.effectiveBucket())
                } finally { release.complete(Unit) }
                request.await()
                Unit
            }
        }
    }

    @Test fun freshNotificationDoesNotInheritOldRetriesOrTheirAttemptBudget() = runBlocking {
        configureFakeCloud { _, _, _ -> error("provider offline") }
        capture("old-retry", "有新的进展，请查看详情")
        processor.processBatch(0, includeRetry = false)
        capture("fresh", "还有一条新的进展，请查看详情")
        // Even an old WorkRequest's attempt number cannot consume this row's first request budget.
        processor.processBatch(99, includeRetry = false)
        assertEquals("retry", dao.find("old-retry")!!.analysisStatus)
        assertEquals("retry", dao.find("fresh")!!.analysisStatus)
        assertEquals(2, calls)
        processor.processBatch(0, onlyRetry = true)
        assertEquals("retry2", dao.find("old-retry")!!.analysisStatus)
        assertEquals("retry2", dao.find("fresh")!!.analysisStatus)
        processor.processBatch(0, onlyRetry = true)
        assertEquals("failed", dao.find("old-retry")!!.analysisStatus)
        assertEquals("failed", dao.find("fresh")!!.analysisStatus)
        assertEquals(0, dao.retryCount())
    }

    @Test fun cloudBudgetYieldsAndLeavesRemainingFreshRowsWithUsableLocalResults() = runBlocking {
        configureFakeCloud { _, _, _ -> hiddenCloudResult }
        repeat(5) { capture("cloud-$it", "作者更新了一篇文章 $it") }
        processor.processBatch(0, includeRetry = false)
        assertEquals(2, calls)
        assertEquals(3, dao.pendingCount())
        assertEquals(DecisionBucket.LATER, dao.find("cloud-4")!!.effectiveBucket())
        processor.processBatch(0, includeRetry = false)
        assertEquals(4, calls)
        assertEquals(1, dao.pendingCount())
    }

    @Test fun sensitiveCodeNeverCallsCloudEvenWhenTheSourceIsExplicitlyAllowed() = runBlocking {
        configureFakeCloud { _, _, _ -> error("Must not upload secrets") }
        val now = System.currentTimeMillis()
        dao.capture(CapturedNotification("secret", "com.taobao.taobao", "淘宝", "验证码123456", "", null, now, now, "otp"))
        processor.processBatch(0)
        assertEquals(DecisionBucket.NOW, dao.find("secret")!!.effectiveBucket())
        assertEquals("local", dao.find("secret")!!.analysisSource)
        assertEquals("local_only", dao.find("secret")!!.analysisStatus)
        assertEquals(0, calls)
    }

    private suspend fun assertPendingAndVisible(key: String) {
        val result = dao.find(key)!!
        assertEquals("pending", result.analysisStatus)
        assertEquals(DecisionBucket.REVIEW, result.effectiveBucket())
        assertEquals("unknown", result.machineKind)
        assertFalse(result.machineReason.contains(hiddenCloudResult.reason))
    }
}
