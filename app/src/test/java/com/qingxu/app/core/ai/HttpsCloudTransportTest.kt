package com.qingxu.app.core.ai

import android.app.Application
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HttpsCloudTransportTest {
    @Test fun baseUrlAndClaudeHeadersReachTheConnectionWithoutChangingPayload() = runBlocking {
        val fake = FakeConnection(URL("https://provider.invalid"))
        var resolved: URL? = null
        val transport = HttpsCloudTransport { url -> resolved = url; fake }
        val payload = JSONObject().put("model", "synthetic").put("stream", false)
        val settings = AiSettings(endpoint = "https://provider.invalid/v1/", model = "synthetic",
            provider = AiProvider.ANTHROPIC_MESSAGES)
        assertEquals("{}", transport.post(settings, "synthetic-key", payload))
        assertEquals("https://provider.invalid/v1/messages", resolved.toString())
        assertEquals("synthetic-key", fake.getRequestProperty("x-api-key"))
        assertEquals("2023-06-01", fake.getRequestProperty("anthropic-version"))
        assertNull(fake.getRequestProperty("Authorization"))
        assertEquals(payload.toString(), fake.sent.toString("UTF-8"))
        assertFalse(fake.instanceFollowRedirects)
        assertTrue(fake.disconnected)
    }

    @Test fun selectedBearerModeNeverAlsoSendsApiKeyHeader() = runBlocking {
        val fake = FakeConnection(URL("https://provider.invalid"))
        HttpsCloudTransport { fake }.post(AiSettings(endpoint = "https://provider.invalid", model = "synthetic",
            provider = AiProvider.ANTHROPIC_MESSAGES, authMode = AiAuthMode.BEARER), "synthetic-key", JSONObject())
        assertEquals("Bearer synthetic-key", fake.getRequestProperty("Authorization"))
        assertNull(fake.getRequestProperty("x-api-key"))
    }

    @Test fun serverErrorsKeepSafeStatusAndNeverReadResponseBodyOrFollowRedirect() = runBlocking {
        listOf(400, 401, 403, 404, 429, 302, 500).forEach { status ->
            val fake = FakeConnection(URL("https://provider.invalid"), status)
            try {
                HttpsCloudTransport { fake }.post(AiSettings(endpoint = "https://provider.invalid", model = "synthetic"),
                    "synthetic-key", JSONObject())
                fail("Expected service error")
            } catch (error: AiServiceException) {
                assertEquals(statusMessage(status), error.message)
                assertFalse(error.message.orEmpty().contains("synthetic-key"))
                // Coroutine stack-trace recovery may clone this exception as its own safe cause.
                val chain = generateSequence<Throwable>(error) { it.cause }.take(10).toList()
                assertTrue(chain.all { it is AiServiceException && it.message == statusMessage(status) })
            }
            assertFalse(fake.responseRead)
            assertFalse(fake.instanceFollowRedirects)
            assertTrue(fake.disconnected)
        }
    }

    @Test fun revocationAfterOpeningStillPreventsWritingAnyBody() = runBlocking {
        val fake = FakeConnection(URL("https://provider.invalid"))
        var checked = 0
        val guard = CloudRequestGuard {
            checked++
            if (checked == 2) throw CancellationException("Synthetic revocation")
        }
        try {
            withContext(guard) {
                HttpsCloudTransport { fake }.post(AiSettings(endpoint = "https://provider.invalid", model = "synthetic"),
                    "synthetic-key", JSONObject().put("synthetic", "data"))
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(2, checked)
        assertEquals(0, fake.sent.size())
        assertTrue(fake.disconnected)
    }

    @Test fun cancellingBlockedWriteDisconnectsPromptlyAndPreservesCancellation() = runBlocking {
        val fake = BlockingWriteConnection()
        val failure = AtomicReference<Throwable?>()
        val request = launch(Dispatchers.Default) {
            try {
                HttpsCloudTransport { fake }.post(
                    AiSettings(endpoint = "https://provider.invalid", model = "synthetic"),
                    "synthetic-key", JSONObject().put("synthetic", "data"),
                )
            } catch (error: Throwable) {
                failure.set(error)
                throw error
            }
        }
        try {
            assertTrue("Request did not reach the blocking write", fake.writeEntered.await(5, TimeUnit.SECONDS))
            val started = System.nanoTime()
            request.cancel(CancellationException("Synthetic draft was replaced"))
            withTimeout(3_000) { request.join() }
            assertTrue("Cancellation waited for the full network timeout", System.nanoTime() - started < 3_000_000_000L)
            assertTrue(request.isCancelled)
            assertTrue(failure.get() is CancellationException)
            assertTrue("Watchdog and finally must both release the connection", fake.disconnectCalls.get() >= 2)
            assertEquals(1, fake.closedStreams.get())
        } finally {
            // Also release the fake if this regression test fails, so no worker is stranded.
            fake.disconnect()
            request.cancel()
            withTimeout(3_000) { request.join() }
        }
    }

    private class BlockingWriteConnection : HttpsURLConnection(URL("https://provider.invalid")) {
        val writeEntered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        val disconnectCalls = AtomicInteger()
        val closedStreams = AtomicInteger()
        override fun connect() {}
        override fun disconnect() { disconnectCalls.incrementAndGet(); released.countDown() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writeEntered.countDown()
                check(released.await(10, TimeUnit.SECONDS)) { "Test connection was not released" }
                throw IOException("Synthetic connection was disconnected")
            }
            override fun close() { closedStreams.incrementAndGet() }
        }
    }

    private class FakeConnection(url: URL, private val code: Int = 200) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var responseRead = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getResponseCode() = code
        override fun getContentLengthLong() = 2L
        override fun getInputStream(): ByteArrayInputStream {
            responseRead = true
            return ByteArrayInputStream("{}".toByteArray())
        }
    }
}
