package com.qingxu.app.notification

import android.app.PendingIntent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** PendingIntents stay in memory; stale notifications cannot silently open another app. */
object NotificationActions {
    private val intents = ConcurrentHashMap<String, PendingIntent>()
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    fun remember(key: String, intent: PendingIntent?) {
        if (intent == null) intents.remove(key) else intents[key] = intent
    }
    fun remove(key: String) { intents.remove(key) }
    fun setConnected(value: Boolean) {
        mutableConnected.value = value
        if (!value) intents.clear()
    }
    fun open(key: String): Boolean {
        val intent = intents[key] ?: return false
        return try {
            intent.send()
            true
        } catch (_: PendingIntent.CanceledException) {
            intents.remove(key)
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
