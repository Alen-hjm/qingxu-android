package com.qingxu.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderPreferencePolicyTest {
    private val chat = NotificationEntry(
        "chat", "com.tencent.mm", "微信", "家人", "今天回家吃饭吗", null, 1, 1, "hash",
    )

    @Test fun explicitConversationScopeIsStableAcrossMessagesButDoesNotClaimSenderIdentity() {
        val scope = SenderPreferencePolicy.senderScope(chat)!!
        assertTrue(scope.matches(Regex("[0-9a-f]{64}")))
        assertEquals(scope, SenderPreferencePolicy.senderScope(chat.copy(body = "下一条内容", notificationKey = "new")))
        assertEquals(scope, SenderPreferencePolicy.senderScope(chat.copy(title = "  家人  ")))
        assertNotEquals(scope, SenderPreferencePolicy.senderScope(chat.copy(title = "工作群")))
        assertNotEquals(scope, SenderPreferencePolicy.senderScope(chat.copy(packageName = "com.tencent.mobileqq", appLabel = "QQ")))
        assertNotNull(SenderPreferencePolicy.senderScope(chat.copy(title = "产品 2026 群")))
    }

    @Test fun summaryTitlesAndApplicationTitlesCannotCreateConversationRules() {
        listOf("", "微信", "微信消息", "微信通知", "3条新消息", "3 个会话", "2 new messages", "123456", "家人 (3)", "家人\n工作群").forEach {
            assertNull(it, SenderPreferencePolicy.senderScope(chat.copy(title = it)))
        }
    }

    @Test fun hiddenAndMissingBodiesCannotCreateConversationRulesEvenWithSpecificTitle() {
        listOf("", "通知内容已隐藏", "解锁后查看消息", "[3条新消息]", "您有一条新消息", "你收到了2条消息", "Message content hidden").forEach {
            assertNull(it, SenderPreferencePolicy.senderScope(chat.copy(body = it)))
        }
    }

    @Test fun applicationDisplayNameCannotMakeAnUnknownSourceAChatApplication() {
        assertNull(SenderPreferencePolicy.senderScope(chat.copy(packageName = "example.shopping", appLabel = "微信")))
        assertNull(SenderPreferencePolicy.senderScope(chat.copy(packageName = "com.tencent.mm.fake")))
    }
}
