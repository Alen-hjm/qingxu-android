package com.qingxu.app.data.repository

import android.util.Log
import com.qingxu.app.data.local.CapturedNotification
import com.qingxu.app.data.local.NotificationDao
import com.qingxu.app.data.local.UserLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NotificationRepository(
    private val dao: NotificationDao,
    private val sources: SourcePreferences,
    private val ensureReady: suspend () -> Unit = {},
    private val acceptCapture: (CapturedNotification) -> Boolean = { true },
    private val mutationGate: Mutex = Mutex(),
    private val onCaptured: () -> Unit = {},
) {
    // One application-owned queue preserves callback order, including across service restarts.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val mutableCaptureFailed = MutableStateFlow(false)
    val captureFailed = mutableCaptureFailed.asStateFlow()
    val entries = flow { ensureReady(); emitAll(dao.observeAll()) }

    init {
        scope.launch {
            for (event in events) {
                try {
                    when (event) {
                        is Event.Posted -> capture(event.notification)
                        is Event.Removed -> markRemoved(event.key, event.at)
                    }
                    mutableCaptureFailed.value = false
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableCaptureFailed.value = true
                    Log.e("QingxuRepository", "Local persistence failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    fun enqueueCapture(notification: CapturedNotification) { events.trySend(Event.Posted(notification)) }
    fun enqueueRemoval(key: String, at: Long) { events.trySend(Event.Removed(key, at)) }
    suspend fun capture(notification: CapturedNotification) {
        ensureReady()
        mutationGate.withLock {
            if (!sources.accepts(notification.packageName)) return
            if (!acceptCapture(notification)) return
            dao.capture(notification)
            onCaptured()
        }
    }
    suspend fun classify(key: String, label: UserLabel, version: Int, hash: String) = dao.classify(key, label, version, hash)
    suspend fun restore(key: String, version: Int, hash: String) = dao.restore(key, version, hash)
    suspend fun setInterested(key: String, value: Boolean) = dao.setInterested(key, value)
    suspend fun markRemoved(key: String, at: Long) = dao.markRemoved(key, at)

    private sealed interface Event {
        data class Posted(val notification: CapturedNotification) : Event
        data class Removed(val key: String, val at: Long) : Event
    }
}
