package com.qingxu.app.core.ai

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CloudRequestGuardTest {
    @Test fun revokedAuthorizationCancelsBeforeCreatingConnectionAndRetainsCancellation() = runBlocking {
        var checks = 0
        val revoked = CancellationException("Synthetic authorization was withdrawn")
        val guard = CloudRequestGuard { checks++; throw revoked }
        try {
            withContext(guard) {
                // .invalid cannot be a real provider; the guard must exit before connection creation.
                HttpsCloudTransport().post(
                    AiSettings(endpoint = "https://never-connect.invalid/v1/chat/completions", model = "synthetic"),
                    "synthetic-key", JSONObject(),
                )
            }
            fail("A revoked request must not proceed")
        } catch (cancelled: CancellationException) {
            // Coroutine stack-trace recovery may clone CancellationException across withContext.
            // It must remain a cancellation and retain the guard's origin, not become a service error.
            assertEquals(revoked.javaClass, cancelled.javaClass)
            assertEquals(revoked.message, cancelled.message)
            val chain = generateSequence<Throwable>(cancelled) { it.cause }.take(10).toList()
            assertTrue(chain.none { it is AiServiceException })
            assertEquals(revoked.javaClass, chain.last().javaClass)
            assertEquals(revoked.message, chain.last().message)
        }
        assertEquals(1, checks)
    }
}
