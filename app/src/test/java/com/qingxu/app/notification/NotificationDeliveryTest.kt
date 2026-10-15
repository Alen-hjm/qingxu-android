package com.qingxu.app.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class NotificationDeliveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun grantAccess() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            "${context.packageName}/${QingxuNotificationListener::class.java.name}")
    }
    @Test fun postingTestIsNotSuccessUntilMatchingListenerReceipt() {
        grantAccess()
        val delivery = NotificationDelivery(context)
        delivery.startTest()
        assertTrue(delivery.state.value.testRunning)
        assertNull(delivery.state.value.testReceivedAt)
        val manager = context.getSystemService(NotificationManager::class.java)
        val actual = manager.activeNotifications.single { it.id == NotificationDelivery.TEST_ID }.notification
        val forged = Notification.Builder(context, NotificationDelivery.TEST_CHANNEL).build()
        delivery.receiveTest(forged)
        assertNull(delivery.state.value.testReceivedAt)
        delivery.receiveTest(actual)
        assertFalse(delivery.state.value.testRunning)
        assertNotNull(delivery.state.value.testReceivedAt)
        assertTrue(manager.activeNotifications.none { it.id == NotificationDelivery.TEST_ID })
    }
    @Test fun timeoutAndLateCallbackCannotClaimSuccess() {
        grantAccess()
        val delivery = NotificationDelivery(context)
        delivery.startTest()
        val actual = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(delivery.state.value.testRunning)
        assertNull(delivery.state.value.testReceivedAt)
        delivery.receiveTest(actual)
        assertNull(delivery.state.value.testReceivedAt)
    }
    @Test fun restartingTestKeepsNewNotificationUntilItsOwnDeadline() {
        grantAccess()
        val delivery = NotificationDelivery(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        delivery.startTest()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        delivery.startTest()
        val actual = manager.activeNotifications.single().notification
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(delivery.state.value.testRunning)
        assertEquals(1, manager.activeNotifications.size)
        delivery.receiveTest(actual)
        assertNotNull(delivery.state.value.testReceivedAt)
    }
    @Test fun revokedAccessCannotStartTestAndReplayDoesNotAdvanceMessageTimestamp() {
        val delivery = NotificationDelivery(context)
        delivery.recordPersisted(2000)
        delivery.recordPersisted(1000)
        assertEquals(2000L, delivery.state.value.lastReceivedAt)
        assertEquals(2000L, NotificationDelivery(context).state.value.lastReceivedAt)
        delivery.startTest()
        assertFalse(delivery.state.value.testRunning)
        assertNull(delivery.state.value.testReceivedAt)
    }
    @Test fun mutedTestChannelCannotProduceFalseReadiness() {
        grantAccess()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(NotificationDelivery.TEST_CHANNEL,
            "test", NotificationManager.IMPORTANCE_NONE))
        val delivery = NotificationDelivery(context)
        delivery.startTest()
        assertFalse(delivery.state.value.testRunning)
        assertNull(delivery.state.value.testReceivedAt)
        assertTrue(delivery.state.value.testMessage.contains("测试通知被系统关闭"))
    }
}
