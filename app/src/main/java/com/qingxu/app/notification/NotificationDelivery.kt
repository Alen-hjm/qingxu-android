package com.qingxu.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class DeliveryState(
    val lastReceivedAt: Long? = null,
    val testRunning: Boolean = false,
    val testReceivedAt: Long? = null,
    val testMessage: String = "",
)

/** Diagnostics retain timestamps only. A test succeeds exclusively through the listener callback. */
class NotificationDelivery(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("notification_delivery", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(DeliveryState(
        lastReceivedAt = prefs.getLong("last_received", 0).takeIf { it > 0 },
    ))
    val state = mutableState.asStateFlow()
    private var pendingToken: String? = null
    private var deadline = 0L

    @Synchronized fun recordPersisted(postedAt: Long) {
        val timestamp = maxOf(mutableState.value.lastReceivedAt ?: 0, postedAt.coerceAtMost(System.currentTimeMillis()))
        prefs.edit().putLong("last_received", timestamp).apply()
        mutableState.value = mutableState.value.copy(lastReceivedAt = timestamp.takeIf { it > 0 })
    }

    @Synchronized fun startTest() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!NotificationAccess.granted(context)) { fail("先开启通知访问，再测试接收"); return }
        if (manager?.areNotificationsEnabled() != true) { fail("请允许轻序发送测试通知"); return }
        manager.createNotificationChannel(NotificationChannel(TEST_CHANNEL, "接收测试", NotificationManager.IMPORTANCE_LOW))
        if (manager.getNotificationChannel(TEST_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            fail("测试通知被系统关闭，请在通知设置中开启“接收测试”"); return
        }
        val token = UUID.randomUUID().toString()
        pendingToken = token
        deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        mutableState.value = mutableState.value.copy(testRunning = true, testReceivedAt = null, testMessage = "正在验证真实接收链路…")
        try {
            manager.notify(TEST_ID, Notification.Builder(context, TEST_CHANNEL)
                .setSmallIcon(com.qingxu.app.R.drawable.qingxu_notification)
                .setContentTitle("轻序接收测试").setContentText("仅用于确认通知连接，接收后自动移除")
                .setVisibility(Notification.VISIBILITY_SECRET).setTimeoutAfter(10_000)
                .setExtras(Bundle().apply { putString(TEST_TOKEN, token) }).build())
        } catch (_: SecurityException) { fail("请允许轻序发送测试通知"); return }
        scope.launch {
            delay(10_000)
            synchronized(this@NotificationDelivery) {
                if (pendingToken == token) {
                    fail("暂未收到测试。请重新连接，仍失败时检查通知访问与后台限制")
                    manager.cancel(TEST_ID)
                }
            }
        }
    }

    @Synchronized fun receiveTest(notification: Notification): Boolean {
        if (notification.channelId != TEST_CHANNEL) return false
        val token = notification.extras.getString(TEST_TOKEN)
        if (pendingToken != null && token == pendingToken && android.os.SystemClock.elapsedRealtime() <= deadline) {
            pendingToken = null
            mutableState.value = mutableState.value.copy(testRunning = false,
                testReceivedAt = System.currentTimeMillis(), testMessage = "测试通过，系统已把通知交给轻序")
            context.getSystemService(NotificationManager::class.java)?.cancel(TEST_ID)
        }
        return true
    }

    @Synchronized fun fail(message: String) {
        pendingToken = null
        mutableState.value = mutableState.value.copy(testRunning = false, testReceivedAt = null, testMessage = message)
    }

    companion object {
        const val TEST_CHANNEL = "delivery_test"
        const val TEST_ID = 17001
        private const val TEST_TOKEN = "qingxu_delivery_token"
    }
}
