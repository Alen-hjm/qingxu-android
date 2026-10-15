package com.qingxu.app.core.decision

import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalClassifierTest {
    private fun entry(text: String, packageName: String = "com.taobao.taobao", app: String = "淘宝") =
        NotificationEntry("key", packageName, app, "通知", text, null, 1L, 2L, "hash")

    @Test fun shoppingPromotionCanBeQuietlyHidden() {
        val result = LocalClassifier.classify(entry("今晚限时优惠，领取专属福利"))!!
        assertEquals(DecisionBucket.HIDDEN, result.bucket)
        assertEquals("marketing", result.kind)
    }

    @Test fun transactionMixedWithMarketingIsNotHidden() {
        assertEquals(DecisionBucket.LATER, LocalClassifier.classify(entry("您的订单已发货，赠送优惠券待领取"))!!.bucket)
        assertEquals(DecisionBucket.NOW, LocalClassifier.classify(entry("退款失败，另有专属优惠"))!!.bucket)
    }

    @Test fun transactionFailureNeedsAttention() {
        val result = LocalClassifier.classify(entry("退款失败，请补充资料"))!!
        assertEquals(DecisionBucket.NOW, result.bucket)
        assertEquals("transaction", result.kind)
    }

    @Test fun ordinaryDeliveryUpdateCanWait() {
        val result = LocalClassifier.classify(entry("您的包裹已发货，物流已更新"))!!
        assertEquals(DecisionBucket.LATER, result.bucket)
        assertEquals("delivery", result.kind)
    }

    @Test fun chatQuotingPromotionIsNotAutomaticallyHidden() {
        assertNull(LocalClassifier.classify(entry("今晚限时优惠，帮我买一个", "com.tencent.mm", "微信")))
    }

    @Test fun unsupportedSourceAndUnclearTextRemainUnclassified() {
        assertNull(LocalClassifier.classify(entry("今晚限时优惠", "example.reader", "阅读器")))
        assertNull(LocalClassifier.classify(entry("有一条新消息")))
    }

    @Test fun eventNeedsExplicitImminentTime() {
        assertEquals(DecisionBucket.NOW, LocalClassifier.classify(entry("会议将在10分钟后开始", "calendar", "日历"), now = 2L)!!.bucket)
        assertEquals(DecisionBucket.NOW, LocalClassifier.classify(entry("课程将在十分钟后开始", "calendar", "日历"), now = 2L)!!.bucket)
        assertEquals(DecisionBucket.LATER, LocalClassifier.classify(entry("会议明天开始", "calendar", "日历"))!!.bucket)
        assertEquals(DecisionBucket.LATER, LocalClassifier.classify(entry("会议将在120分钟后开始", "calendar", "日历"))!!.bucket)
    }

    @Test fun replayedOldEventNeverAppearsAsImminent() {
        val old = entry("会议将在10分钟后开始", "calendar", "日历")
        assertEquals(DecisionBucket.REVIEW, LocalClassifier.classify(old, now = 31 * 60_000L)!!.bucket)
        assertEquals(DecisionBucket.REVIEW, LocalClassifier.classify(old.copy(postedAt = 31 * 60_000L), now = 1L)!!.bucket)
    }

    @Test fun manualCorrectionsTakePriorityAndStaleVersionsDoNot() {
        val machine = entry("广告").copy(machineBucket = "HIDDEN")
        val manual = machine.copy(userLabel = UserLabel.IMPORTANT, labelSource = "manual", manualVersion = 1)
        assertEquals(DecisionBucket.LATER, manual.effectiveBucket())
        assertEquals(DecisionBucket.HIDDEN, manual.copy(contentVersion = 2).effectiveBucket())
        assertEquals(DecisionBucket.LATER, manual.copy(contentVersion = 2, manualVersion = 0).effectiveBucket())
    }

    @Test fun ordinaryChatsGetAVisibleAutomaticLocationAndUrgentRepliesRiseToNow() {
        val chat = entry("周末一起吃个饭吧", "com.tencent.mm", "微信")
        assertEquals(DecisionBucket.LATER, LocalClassifier.fallback(chat).bucket)
        assertEquals("message", LocalClassifier.fallback(chat).kind)
        assertEquals(DecisionBucket.NOW,
            LocalClassifier.classify(chat.copy(body = "有急事，请马上回电"), now = 2L)!!.bucket)
        assertNull(LocalClassifier.classify(chat.copy(body = "不着急，不用马上回复"), now = 2L))
        assertNull(LocalClassifier.classify(chat.copy(body = "有急事，请马上回电"), now = 31 * 60_000L))
    }

    @Test fun codesArePrioritizedLocallyAndSecretsCannotBeHiddenAsAds() {
        val code = entry("登录验证码123456，有效期5分钟")
        assertEquals(DecisionBucket.NOW, LocalClassifier.classify(code, now = 2L)!!.bucket)
        assertEquals(DecisionBucket.LATER, LocalClassifier.classify(code, now = 31 * 60_000L)!!.bucket)
        val secret = entry("身份证信息已更新，今晚限时优惠")
        assertNull(LocalClassifier.classify(secret))
        assertEquals(DecisionBucket.LATER, LocalClassifier.fallback(secret).bucket)
    }

    @Test fun hiddenBodiesStayUncertainWhileReadableUnknownNotificationsCanWait() {
        assertEquals(DecisionBucket.REVIEW, LocalClassifier.fallback(entry("你有一条新消息")).bucket)
        assertEquals(DecisionBucket.REVIEW, LocalClassifier.fallback(entry("解锁后查看消息")).bucket)
        assertEquals(DecisionBucket.LATER, LocalClassifier.fallback(entry("周末一起吃饭吧").copy(title = "新消息")).bucket)
        assertEquals(DecisionBucket.LATER, LocalClassifier.fallback(entry("你关注的作者更新了一篇文章")).bucket)
        assertEquals(DecisionBucket.LATER, LocalClassifier.classify(entry("支付成功，已扣款20元"))!!.bucket)
    }

    @Test fun safetyAndMedicalHelpInChatAreCheckedBeforeOrdinaryMessageFallback() {
        val chat = entry("我在急诊，你来一下", "com.tencent.mm", "微信")
        for (body in listOf("我在急诊，你来一下", "妈妈突然昏倒了，快过来", "我喘不过气，请叫120", "家里着火了，快报警", "账号被盗了")) {
            val result = LocalClassifier.classify(chat.copy(body = body), now = 2L)!!
            assertEquals(body, DecisionBucket.NOW, result.bucket)
            assertTrue(result.kind in setOf("medical", "security"))
        }
        assertEquals(DecisionBucket.NOW, LocalClassifier.classify(chat.copy(body = "我在急诊，你来一下。不着急带东西"), now = 2L)!!.bucket)
    }

    @Test fun negatedQuotedHistoricalAndPromotionalRisksDoNotClaimAnImmediateEmergency() {
        val chat = entry("", "com.tencent.mm", "微信")
        for (body in listOf("我没有呼吸困难，只是累了", "我不在急诊，不用过来", "我不在急诊，你来一下", "没有燃气泄漏，已确认安全", "火灾警报已解除", "转发一篇科普：呼吸困难怎么办，赶快了解", "电影台词：我在急诊，你来一下", "如果发生燃气泄漏，请立即联系物业", "燃气泄漏保险限时优惠，马上购买", "昨天我在急诊，你来一下")) {
            assertNull(body, LocalClassifier.classify(chat.copy(body = body), now = 2L))
        }
        assertEquals(DecisionBucket.LATER,
            LocalClassifier.classify(chat.copy(body = "我在急诊，你来一下"), now = 31 * 60_000L)!!.bucket)
    }

    @Test fun defaultVisiblePlacementDoesNotCountAsConfidentUnderstanding() {
        val ordinary = entry("周末一起吃饭吧", "com.tencent.mm", "微信")
        val fallback = LocalClassifier.fallback(ordinary)
        val organized = ordinary.copy(machineBucket = fallback.bucket.name, machineKind = fallback.kind,
            machineConfidence = fallback.confidence, analysisSource = "local")
        assertFalse(organized.isConfidentlyOrganized())
        val explicit = organized.copy(machineBucket = "NOW", machineKind = "medical", machineConfidence = 0.94)
        assertTrue(explicit.isConfidentlyOrganized())
        assertFalse(explicit.copy(handledAt = 100L).isConfidentlyOrganized())
        assertFalse(explicit.copy(labelSource = "manual", manualVersion = 1).isConfidentlyOrganized())
        assertFalse(explicit.copy(machineConfidence = Double.NaN).isConfidentlyOrganized())
    }

    @Test fun longTermHidingIsOnlyEligibleForHighConfidencePureMarketing() {
        assertTrue(Decision(DecisionBucket.HIDDEN, "marketing", "", 0.99).isLongTermHideEligible())
        assertFalse(Decision(DecisionBucket.NOW, "marketing", "", 0.99).isLongTermHideEligible())
        assertFalse(Decision(DecisionBucket.LATER, "delivery", "", 0.99).isLongTermHideEligible())
        assertFalse(Decision(DecisionBucket.HIDDEN, "marketing", "", 0.9).isLongTermHideEligible())
    }

    @Test fun expiredTimeBoundSignalsLeaveNowWithoutChangingManualOrConversationPriority() {
        val base = entry("会议将在10分钟后开始").copy(machineBucket = "NOW", analysisSource = "local")
        val later = 31 * 60_000L
        for (kind in listOf("verification", "event", "message", "security", "medical")) {
            val notification = base.copy(machineKind = kind)
            assertEquals(DecisionBucket.NOW, notification.displayBucket(now = 2L))
            assertEquals(DecisionBucket.LATER, notification.displayBucket(now = later))
            assertTrue(notification.displayReason(later).contains("较早"))
        }
        assertEquals(DecisionBucket.NOW, base.copy(machineKind = "transaction").displayBucket(later))
        assertEquals(DecisionBucket.NOW, base.copy(machineKind = "message", analysisSource = "preference").displayBucket(later))
        assertEquals(DecisionBucket.NOW, base.copy(machineKind = "verification", labelSource = "manual",
            userLabel = UserLabel.IMPORTANT_URGENT, manualVersion = 1).displayBucket(later))
        assertEquals(DecisionBucket.LATER, base.copy(machineKind = "event", postedAt = later).displayBucket(now = 1L))
    }
}
