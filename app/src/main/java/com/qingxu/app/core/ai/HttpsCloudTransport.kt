package com.qingxu.app.core.ai

import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

/** Shared bounded transport; errors contain no response text, message content, URL or credential. */
internal class HttpsCloudTransport : CloudTransport {
    override suspend fun post(settings: AiSettings, key: String, payload: JSONObject): String =
        withContext(Dispatchers.IO) {
            val uri = validateAiEndpoint(settings.endpoint, settings.provider)
            require(settings.model.isNotBlank()) { "请先填写模型名称。" }
            require(key.isNotBlank() && key.length <= 8192 && key.none { it.isISOControl() }) {
                "接口密钥不可用，请重新填写。"
            }
            var connection: HttpsURLConnection? = null
            var watchdog: Job? = null
            val deadline = System.nanoTime() + TOTAL_TIMEOUT_MS * 1_000_000
            fun remainingTimeout(): Int {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) throw SocketTimeoutException()
                return minOf(remaining, 20_000).toInt().coerceAtLeast(1)
            }
            try {
                ensureActive()
                coroutineContext[CloudRequestGuard]?.check?.invoke()
                connection = uri.toURL().openConnection() as HttpsURLConnection
                val activeConnection = connection
                watchdog = launch(Dispatchers.Default) {
                    delay(TOTAL_TIMEOUT_MS)
                    activeConnection.disconnect()
                }
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = remainingTimeout()
                connection.useCaches = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $key")
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
            } catch (known: AiServiceException) {
                throw known
            } catch (_: SocketTimeoutException) {
                throw AiServiceException("分析超时，本条通知已保留，请稍后重试。")
            } catch (_: Exception) {
                if (System.nanoTime() >= deadline) {
                    throw AiServiceException("分析超时，本条通知已保留，请稍后重试。")
                }
                throw AiServiceException("暂时无法连接模型服务，本条通知已保留，请检查网络和接口配置。")
            } finally {
                watchdog?.cancel()
                connection?.disconnect()
            }
        }

    private fun oversized() = AiServiceException("模型响应过大，本条通知已保留，稍后可以重试。")
    private companion object {
        const val MAX_RESPONSE_BYTES = 65_536
        const val TOTAL_TIMEOUT_MS = 40_000L
    }
}
