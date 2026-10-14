package com.qingxu.app.notification

import android.app.Notification
import androidx.core.app.NotificationCompat

internal fun readNotificationBody(notification: Notification): String {
    val extras = notification.extras
    val messages = NotificationCompat.MessagingStyle
        .extractMessagingStyleFromNotification(notification)?.messages.orEmpty()
    if (messages.isNotEmpty()) return messages.takeLast(4).joinToString("\n") { message ->
        val sender = message.person?.name?.toString().orEmpty()
        if (sender.isBlank()) message.text?.toString().orEmpty()
        else "$sender：${message.text?.toString().orEmpty()}"
    }
    extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        ?.takeIf { it.isNotBlank() }?.let { return it }
    extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        ?.joinToString("\n")?.takeIf { it.isNotBlank() }?.let { return it }
    return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
}
