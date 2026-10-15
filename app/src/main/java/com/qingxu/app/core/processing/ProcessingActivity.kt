package com.qingxu.app.core.processing

import com.qingxu.app.core.ai.AiProvider

enum class ProcessingOutcome { IDLE, SUCCEEDED, FAILED, DISCARDED }

/** Request activity only. No notification text, service URL, model output or credential is retained. */
data class ProcessingActivity(
    val running: Boolean = false,
    val settingsRevision: Long? = null,
    val provider: AiProvider? = null,
    val lastOutcome: ProcessingOutcome = ProcessingOutcome.IDLE,
    val lastCompletedAt: Long? = null,
)
