package com.qingxu.app.core.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory only. Activity pause / screen-off always revokes the session. */
class ContentSession(private val elapsedTime: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private val mutableUnlocked = MutableStateFlow(false)
    val unlocked = mutableUnlocked.asStateFlow()
    private var expiresAt = 0L
    @Synchronized fun unlock() { expiresAt = elapsedTime() + 120_000; mutableUnlocked.value = true }
    @Synchronized fun lock() { expiresAt = 0; mutableUnlocked.value = false }
    @Synchronized fun isUnlocked(): Boolean {
        if (elapsedTime() >= expiresAt) lock()
        return mutableUnlocked.value
    }
}
