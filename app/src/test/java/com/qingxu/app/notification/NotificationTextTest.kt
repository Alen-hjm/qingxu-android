package com.qingxu.app.notification

import android.app.Application
import android.app.Notification
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationTextTest {
    private fun notification(extras: Bundle) =
        Notification.Builder(ApplicationProvider.getApplicationContext(), "messages").setExtras(extras).build()

    @Test fun ordinaryTextWorksWithoutMessagingExtras() {
        val extras = Bundle().apply { putCharSequence(Notification.EXTRA_TEXT, "普通消息") }
        assertEquals("普通消息", readNotificationBody(notification(extras)))
    }

    @Test fun expandedTextTakesPrecedenceOverCollapsedText() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TEXT, "短正文")
            putCharSequence(Notification.EXTRA_BIG_TEXT, "完整正文")
        }
        assertEquals("完整正文", readNotificationBody(notification(extras)))
    }

    @Test fun inboxLinesAreKeptInOrder() {
        val extras = Bundle().apply {
            putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("第一条", "第二条"))
        }
        assertEquals("第一条\n第二条", readNotificationBody(notification(extras)))
    }

    @Test fun messagingStyleKeepsLastFourMessagesAndSender() {
        @Suppress("DEPRECATION")
        val style = Notification.MessagingStyle("我").apply {
            (1..5).forEach { addMessage("消息$it", it.toLong(), "小李") }
        }
        val notification = Notification.Builder(ApplicationProvider.getApplicationContext(), "messages")
            .setStyle(style).build()
        assertEquals("小李：消息2\n小李：消息3\n小李：消息4\n小李：消息5", readNotificationBody(notification))
    }
}
