package com.qingxu.app.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object NotificationAccess {
    fun granted(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 27) {
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component(context)) == true
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component(context) }
        }
    } catch (_: SecurityException) {
        false
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    fun component(context: Context) = ComponentName(context, QingxuNotificationListener::class.java)
}
