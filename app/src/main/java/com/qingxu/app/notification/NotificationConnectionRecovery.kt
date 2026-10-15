package com.qingxu.app.notification

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Requests a system rebind; only onListenerConnected may mark the listener connected. */
object NotificationConnectionRecovery {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var controller: ListenerRecoveryController? = null
    private var installed = false
    private val mutableRecovering = MutableStateFlow(false)
    val recovering = mutableRecovering.asStateFlow()

    fun install(application: Application) {
        onMain {
            if (!installed) {
                installed = true
                application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) { request(application) }
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                })
            }
            request(application)
        }
    }

    fun request(context: Context, force: Boolean = false) {
        val applicationContext = context.applicationContext
        onMain {
            if (!NotificationAccess.granted(applicationContext)) NotificationActions.setConnected(false)
            controller(applicationContext).request(force)
        }
    }

    internal fun onConnected(context: Context) {
        val applicationContext = context.applicationContext
        onMain { controller(applicationContext).onConnected() }
    }

    private fun controller(context: Context): ListenerRecoveryController = controller ?: ListenerRecoveryController(
        now = SystemClock::elapsedRealtime,
        hasPermission = { NotificationAccess.granted(context) },
        isConnected = { NotificationActions.connected.value },
        onRecoveringChanged = { mutableRecovering.value = it },
        requestRebind = {
            try {
                NotificationListenerService.requestRebind(NotificationAccess.component(context))
            } catch (failure: RuntimeException) {
                // Exception messages can contain OEM details; do not log notification data or credentials.
                Log.w("NotificationRecovery", "System rebind failed (${failure.javaClass.simpleName})")
            }
        },
        schedule = { delay, callback ->
            val task = Runnable { callback() }
            handler.postDelayed(task, delay)
            val cancel: () -> Unit = { handler.removeCallbacks(task) }
            cancel
        },
    ).also { controller = it }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }
}

/** Main-thread, finite recovery window. No repeating job survives the third attempt. */
internal class ListenerRecoveryController(
    private val now: () -> Long,
    private val hasPermission: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val requestRebind: () -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onRecoveringChanged: (Boolean) -> Unit = {},
) {
    private var cancelPending: (() -> Unit)? = null
    private var attempts = 0
    private var lastAttemptAt: Long? = null
    private var exhaustedAt: Long? = null
    private var active = false

    fun request(force: Boolean = false) {
        if (!hasPermission() || isConnected()) {
            onConnected()
            return
        }
        if (active) return
        if (!force && exhaustedAt?.let { now() - it < COOLDOWN_MS } == true) return
        exhaustedAt = null
        attempts = 0
        active = true
        onRecoveringChanged(true)
        enqueue(lastAttemptAt?.let { (MIN_INTERVAL_MS - (now() - it)).coerceAtLeast(0) } ?: 0)
    }

    fun onConnected() {
        cancelPending?.invoke()
        cancelPending = null
        attempts = 0
        exhaustedAt = null
        active = false
        onRecoveringChanged(false)
    }

    private fun enqueue(delay: Long) {
        cancelPending = schedule(delay) {
            cancelPending = null
            if (!hasPermission() || isConnected()) {
                onConnected()
                return@schedule
            }
            lastAttemptAt = now()
            attempts += 1
            requestRebind()
            if (!active) return@schedule
            if (!hasPermission() || isConnected()) {
                onConnected()
                return@schedule
            }
            if (attempts < MAX_ATTEMPTS) {
                enqueue(if (attempts == 1) MIN_INTERVAL_MS else SECOND_RETRY_MS)
            } else {
                // Allow the final binder request to finish before presenting a disconnected state.
                cancelPending = schedule(MIN_INTERVAL_MS) {
                    cancelPending = null
                    if (!hasPermission() || isConnected()) onConnected() else {
                        exhaustedAt = now()
                        active = false
                        onRecoveringChanged(false)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MIN_INTERVAL_MS = 10_000L
        const val SECOND_RETRY_MS = 30_000L
        const val COOLDOWN_MS = 120_000L
    }
}
