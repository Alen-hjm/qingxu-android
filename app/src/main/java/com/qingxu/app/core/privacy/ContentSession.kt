package com.qingxu.app.core.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory only. Foreground use stays unlocked; returning from background has a short grace period. */
class ContentSession(private val elapsedTime: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private val mutableUnlocked = MutableStateFlow(false)
    val unlocked = mutableUnlocked.asStateFlow()
    private var foreground = true
    private var expiresAt: Long? = null

    @Synchronized fun enterForeground() {
        // Verify the existing background deadline before removing it, even when no timer ran.
        isUnlocked()
        foreground = true
        expiresAt = null
    }

    @Synchronized fun enterBackground() {
        if (!foreground) return // Repeated lifecycle callbacks must not extend the grace period.
        foreground = false
        expiresAt = if (mutableUnlocked.value) elapsedTime() + BACKGROUND_GRACE_MS else null
    }

    @Synchronized fun unlock() {
        expiresAt = if (foreground) null else elapsedTime() + BACKGROUND_GRACE_MS
        mutableUnlocked.value = true
    }

    /** Screen-off and explicit locking bypass the background grace period. */
    @Synchronized fun lock() { expiresAt = null; mutableUnlocked.value = false }

    @Synchronized fun isUnlocked(): Boolean {
        val deadline = expiresAt
        if (mutableUnlocked.value && deadline != null && elapsedTime() >= deadline) lock()
        return mutableUnlocked.value
    }

    private companion object { const val BACKGROUND_GRACE_MS = 60_000L }
}
