package com.qingxu.app.core.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiProvider { CHAT_COMPLETIONS, JEV }

data class AiSettings(
    val endpoint: String = "",
    val model: String = "",
    val enabled: Boolean = false,
    val allowedPackages: Set<String> = emptySet(),
    val interests: String = "",
    val hasKey: Boolean = false,
    val revision: Long = 0,
    val provider: AiProvider = AiProvider.CHAT_COMPLETIONS,
)

/** Complete HTTPS endpoint. The Jev adapter deliberately supports the official service only. */
internal fun validateAiEndpoint(value: String, provider: AiProvider = AiProvider.CHAT_COMPLETIONS): URI {
    val endpoint = value.trim()
    require(endpoint.length <= 2048 && endpoint.none { it.isISOControl() }) {
        "接口地址格式不正确，请填写完整的 HTTPS 地址。"
    }
    val uri = try {
        URI(endpoint)
    } catch (_: Exception) {
        throw IllegalArgumentException("接口地址格式不正确，请填写完整的 HTTPS 地址。")
    }
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "接口必须使用 HTTPS，例如 https://api.example.com/v1/chat/completions。"
    }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "接口地址不能包含账号、密钥、查询参数或锚点。请将密钥填入专用输入框。"
    }
    require(uri.port == -1 || uri.port in 1..65535) { "接口地址的端口不正确。" }
    require(!uri.path.isNullOrBlank() && uri.path != "/") {
        "请填写完整的接口路径，例如 /v1/chat/completions。"
    }
    if (provider == AiProvider.JEV) {
        require(uri.host.equals("api.typesafe.ai", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) && uri.rawPath == "/v1/systemone") {
            "Jev 请使用官方接口 https://api.typesafe.ai/v1/systemone。"
        }
    }
    return uri
}

/** Settings stay local. Only AES-GCM ciphertext and its random IV enter preferences. */
class AiPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("smart_sorting", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(readSettings())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun save(
        endpoint: String,
        model: String,
        key: String?,
        enabled: Boolean,
        allowedPackages: Set<String>,
        interests: String,
        provider: AiProvider = mutableState.value.provider,
    ) {
        val cleanEndpoint = endpoint.trim()
        val cleanModel = model.trim()
        val cleanInterests = interests.trim()
        val replacementKey = key?.trim()?.takeIf { it.isNotBlank() }
        if (cleanEndpoint.isNotBlank()) validateAiEndpoint(cleanEndpoint, provider)
        require(cleanModel.length <= 200 && cleanModel.none { it.isISOControl() }) { "模型名称格式不正确。" }
        require(cleanInterests.length <= 2000) { "关注内容请控制在 2000 字以内。" }
        if (replacementKey != null) {
            require(replacementKey.length <= 8192 && replacementKey.none { it.isISOControl() }) {
                "密钥格式不正确，请检查是否含有换行或其他无效字符。"
            }
        }
        if (mutableState.value.hasKey && replacementKey == null &&
            (provider != mutableState.value.provider ||
                endpointAuthority(cleanEndpoint) != endpointAuthority(mutableState.value.endpoint))
        ) {
            throw IllegalArgumentException("更换服务类型或地址时请重新填写该服务的密钥，或先删除原密钥。")
        }
        val hasKey = replacementKey != null || mutableState.value.hasKey
        if (enabled) {
            require(cleanEndpoint.isNotBlank()) { "请先填写 HTTPS 接口地址。" }
            require(cleanModel.isNotBlank()) { "请先填写模型名称。" }
            require(hasKey) { "请先填写接口密钥。" }
        }
        val next = AiSettings(
            endpoint = cleanEndpoint,
            model = cleanModel,
            enabled = enabled,
            allowedPackages = allowedPackages.toSet(),
            interests = cleanInterests,
            hasKey = hasKey,
            revision = mutableState.value.revision,
            provider = provider,
        )
        if (replacementKey == null && next == mutableState.value) return

        val editor = preferences.edit()
        if (replacementKey != null) {
            val encrypted = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
                cipher.iv to cipher.doFinal(replacementKey.toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
                throw IllegalArgumentException("无法安全保存密钥，请检查设备安全设置后重试。")
            }
            editor.putString("key_iv", Base64.encodeToString(encrypted.first, Base64.NO_WRAP))
            editor.putString("key_ciphertext", Base64.encodeToString(encrypted.second, Base64.NO_WRAP))
        }
        val revision = next.revision + 1
        val committed = editor.putString("endpoint", next.endpoint)
            .putString("model", next.model)
            .putString("provider", next.provider.name)
            .putBoolean("enabled", next.enabled)
            .putStringSet("allowed_packages", next.allowedPackages)
            .putString("interests", next.interests)
            .putLong("revision", revision)
            .commit()
        require(committed) { "配置未能保存，请稍后重试。" }
        mutableState.value = next.copy(revision = revision)
    }

    @Synchronized
    fun clearKey() {
        val revision = mutableState.value.revision + 1
        val committed = preferences.edit().remove("key_iv").remove("key_ciphertext")
            .putBoolean("enabled", false).putLong("revision", revision).commit()
        require(committed) { "密钥未能删除，请稍后重试。" }
        mutableState.value = mutableState.value.copy(hasKey = false, enabled = false, revision = revision)
    }

    @Synchronized
    fun apiKey(): String {
        val encoded = preferences.getString("key_ciphertext", null) ?: return ""
        val encodedIv = preferences.getString("key_iv", null)
            ?: throw IllegalStateException("无法读取已保存的密钥，请重新填写。")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalStateException("无法读取已保存的密钥，请重新填写。")
        }
    }

    /** Atomic endpoint/key pairing prevents concurrent service changes from mixing credentials. */
    @Synchronized
    fun connectionSnapshot(): Pair<AiSettings, String> = mutableState.value to apiKey()

    private fun readSettings() = AiSettings(
        endpoint = preferences.getString("endpoint", "").orEmpty(),
        model = preferences.getString("model", "").orEmpty(),
        enabled = preferences.getBoolean("enabled", false),
        allowedPackages = preferences.getStringSet("allowed_packages", emptySet()).orEmpty().toSet(),
        interests = preferences.getString("interests", "").orEmpty(),
        hasKey = preferences.contains("key_ciphertext") && preferences.contains("key_iv"),
        revision = preferences.getLong("revision", 0),
        provider = AiProvider.entries.firstOrNull { it.name == preferences.getString("provider", null) }
            ?: AiProvider.CHAT_COMPLETIONS,
    )

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "qingxu.ai.api-key.v1"
    }
}

private fun endpointAuthority(endpoint: String): String = try {
    val uri = URI(endpoint)
    "${uri.host?.lowercase()}:${if (uri.port == -1) 443 else uri.port}"
} catch (_: Exception) {
    ""
}
