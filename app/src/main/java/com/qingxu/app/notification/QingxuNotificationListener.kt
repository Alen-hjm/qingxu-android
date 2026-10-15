package com.qingxu.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.qingxu.app.QingxuApplication
import com.qingxu.app.data.local.CapturedNotification
import java.lang.ref.WeakReference
import java.security.MessageDigest

class QingxuNotificationListener : NotificationListenerService() {
    private val appLabels = mutableMapOf<String, String>()
    private val container get() = (application as QingxuApplication).container

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = WeakReference(this)
        NotificationActions.setConnected(true)
        NotificationConnectionRecovery.onConnected(this)
        captureActive()
    }

    override fun onListenerDisconnected() {
        markDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (instance?.get() === this) captureSafely(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (instance?.get() !== this) return
        handleNotificationSafely {
            NotificationActions.remove(sbn.key)
            container.repository.enqueueRemoval(sbn.key, System.currentTimeMillis())
        }
    }

    private fun captureActive() {
        if (instance?.get() !== this || !NotificationActions.connected.value) return
        val active = try {
            activeNotifications
        } catch (failure: RuntimeException) {
            reportFailure(failure)
            markDisconnected()
            return
        }
        if (active == null) {
            markDisconnected()
            return
        }
        active.forEach(::captureSafely)
    }

    private fun captureSafely(sbn: StatusBarNotification) {
        handleNotificationSafely { capture(sbn) }
    }

    private fun markDisconnected() {
        val owner = instance?.get()
        // A late callback from an older service must not take the replacement offline.
        if (owner != null && owner !== this) return
        instance = null
        NotificationActions.setConnected(false)
        NotificationConnectionRecovery.request(this)
    }

    private fun capture(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || !container.sources.accepts(sbn.packageName)) return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(300)
        val body = readNotificationBody(notification).take(4000)
        if (title.isBlank() && body.isBlank()) return
        val label = appLabels.getOrPut(sbn.packageName) {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                sbn.packageName
            }
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${title.length}:$title${body.length}:$body".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        NotificationActions.remember(sbn.key, notification.contentIntent)
        container.repository.enqueueCapture(CapturedNotification(
            notificationKey = sbn.key,
            packageName = sbn.packageName,
            appLabel = label,
            title = title,
            body = body,
            channelId = notification.channelId,
            postedAt = sbn.postTime,
            capturedAt = System.currentTimeMillis(),
            contentHash = hash,
        ))
    }

    override fun onDestroy() {
        markDisconnected()
        super.onDestroy()
    }

    companion object {
        private var instance: WeakReference<QingxuNotificationListener>? = null
        /** Called on the main thread after the user enables a source. */
        fun refreshActive() { instance?.get()?.captureActive() }
    }
}

/** A malformed notification must not interrupt later callbacks or active-notification sync. */
internal fun handleNotificationSafely(handle: () -> Unit) {
    try {
        handle()
    } catch (failure: RuntimeException) {
        reportFailure(failure)
    }
}

private fun reportFailure(failure: RuntimeException) {
    // Do not include exception messages, titles, bodies, or the notification's originating app.
    Log.w("NotificationCapture", "Notification callback failed (${failure.javaClass.simpleName})")
}
