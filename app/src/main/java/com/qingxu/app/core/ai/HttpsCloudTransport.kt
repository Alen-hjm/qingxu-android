package com.qingxu.app.core.ai

import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.URL
import javax.net.ssl.SSLException
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

/** Shared bounded transport; errors contain no response text, message content, URL or credential. */
internal class HttpsCloudTransport(
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : CloudTransport {
    override suspend fun post(settings: AiSettings, key: String, payload: JSONObject): String =
        withContext(Dispatchers.IO) {
            val uri = resolveAiEndpoint(settings.endpoint, settings.provider, settings.endpointMode)
            require(settings.model.isNotBlank()) { "请先填写模型名称。" }
            require(key.isNotBlank() && key.length <= 8192 && key.none { it.isISOControl() }) {
                "接口密钥不可用，请重新填写。"
            }
            var connection: HttpsURLConnection? = null
            var watchdogScope: CoroutineScope? = null
            val requestJob = checkNotNull(coroutineContext[Job])
            val deadline = System.nanoTime() + TOTAL_TIMEOUT_MS * 1_000_000
            fun remainingTimeout(): Int {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) throw SocketTimeoutException()
                return minOf(remaining, 20_000).toInt().coerceAtLeast(1)
            }
            try {
                ensureActive()
                coroutineContext[CloudRequestGuard]?.check?.invoke()
                connection = openConnection(uri.toURL())
                val activeConnection = connection
                // Blocking URLConnection I/O does not observe coroutine cancellation. Keep this
                // monitor independent of the request, and always close its scope in finally.
                watchdogScope = CoroutineScope(Dispatchers.Default).also { scope ->
                    scope.launch {
                        while (requestJob.isActive) {
                            val remainingNanos = deadline - System.nanoTime()
                            if (remainingNanos <= 0) break
                            val remainingMillis = (remainingNanos + 999_999) / 1_000_000
                            delay(minOf(remainingMillis, CANCELLATION_POLL_MS))
                        }
                        runCatching { activeConnection.disconnect() }
                    }
                }
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = remainingTimeout()
                connection.useCaches = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connectionHeaders(settings, key).forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val bytesToSend = payload.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytesToSend.size)
                connection.outputStream.use {
                    coroutineContext[CloudRequestGuard]?.check?.invoke()
                    ensureActive()
                    it.write(bytesToSend)
                }
                ensureActive()
                connection.readTimeout = remainingTimeout()
                val status = connection.responseCode
                if (status !in 200..299) throw AiServiceException(statusMessage(status))
                if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw oversized()
                val bytes = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        ensureActive()
                        connection.readTimeout = remainingTimeout()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > MAX_RESPONSE_BYTES) throw oversized()
                        bytes.write(buffer, 0, count)
                    }
                }
                ensureActive()
                remainingTimeout()
                bytes.toString(Charsets.UTF_8.name())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // disconnect() may surface as IOException; cancellation must remain cancellation.
                ensureActive()
                throw when {
                    System.nanoTime() >= deadline || error is SocketTimeoutException ->
                        AiServiceException("连接超时，请检查网络或稍后重试。")
                    error is AiServiceException -> error
                    error is UnknownHostException -> AiServiceException("无法解析服务域名，请检查地址和手机网络。")
                    error is ConnectException -> AiServiceException("无法建立连接，请检查手机网络、代理或服务端口。")
                    error is SSLException -> AiServiceException("HTTPS 证书或安全连接验证失败，请检查服务地址和设备时间。")
                    else -> AiServiceException("暂时无法连接模型服务，本条通知已保留，请检查网络和接口配置。")
                }
            } finally {
                watchdogScope?.cancel()
                runCatching { connection?.disconnect() }
            }
        }

    private fun oversized() = AiServiceException("模型响应过大，本条通知已保留，稍后可以重试。")
    private companion object {
        const val MAX_RESPONSE_BYTES = 65_536
        const val TOTAL_TIMEOUT_MS = 40_000L
        const val CANCELLATION_POLL_MS = 100L
    }
}
