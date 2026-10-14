package com.qingxu.app.core.privacy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encryption must fail closed: implementations never return plaintext after a crypto failure. */
interface NotificationCipher {
    fun encrypt(plaintext: String, associatedData: String): String
    fun decrypt(ciphertext: String, associatedData: String): String
}

class NotificationEncryptionException : IllegalStateException("无法安全读取或保存通知，请检查设备安全设置。")

/** A key provider permits real AES-GCM tests without emulating the device's hardware keystore. */
class AesGcmNotificationCipher(private val keyProvider: () -> SecretKey) : NotificationCipher {
    private val key: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { keyProvider() }

    override fun encrypt(plaintext: String, associatedData: String): String = guarded {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val bytes = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        "qx1:" + Base64.getEncoder().encodeToString(bytes)
    }

    override fun decrypt(ciphertext: String, associatedData: String): String = guarded {
        require(ciphertext.startsWith("qx1:"))
        val bytes = Base64.getDecoder().decode(ciphertext.removePrefix("qx1:"))
        require(bytes.size >= IV_BYTES + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private inline fun guarded(block: () -> String): String = try { block() } catch (_: Exception) {
        // Avoid leaking plaintext, provider errors, keystore aliases or ciphertext in logs/UI.
        throw NotificationEncryptionException()
    }

    private companion object { const val IV_BYTES = 12 }
}

/** No application-owned raw key is persisted; Android Keystore controls the production key. */
fun androidNotificationCipher(): NotificationCipher = AesGcmNotificationCipher(::notificationKey)

@Synchronized
private fun notificationKey(): SecretKey {
    val alias = "qingxu.notifications.at-rest.v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build())
    }.generateKey()
}
