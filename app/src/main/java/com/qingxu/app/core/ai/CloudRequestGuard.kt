package com.qingxu.app.core.ai

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Rechecks current authorization immediately before transport can send notification data. */
class CloudRequestGuard(val check: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CloudRequestGuard>
}
