package com.qingxu.app.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object NotificationAccess {
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 27) {
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(component(context))
    } else {
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    fun component(context: Context) = ComponentName(context, QingxuNotificationListener::class.java)
}
