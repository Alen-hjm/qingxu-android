package com.qingxu.app.core.privacy

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotificationCipherTest {
    private val cipher = AesGcmNotificationCipher { SecretKeySpec(ByteArray(32) { 11 }, "AES") }

    @Test fun authenticatedEncryptionRoundTripsWithoutPlaintext() {
        val stored = cipher.encrypt("通知标题：退款资料需要补充", "key:body")
        assertFalse(stored.contains("退款"))
        assertEquals("通知标题：退款资料需要补充", cipher.decrypt(stored, "key:body"))
        assertNotEquals(stored, cipher.encrypt("通知标题：退款资料需要补充", "key:body"))
    }

    @Test fun switchingFieldsOrNotificationKeysFailsAuthentication() {
        val stored = cipher.encrypt("秘密消息", "key-1:body")
        assertThrows(NotificationEncryptionException::class.java) { cipher.decrypt(stored, "key-1:title") }
        assertThrows(NotificationEncryptionException::class.java) { cipher.decrypt(stored, "key-2:body") }
    }

    @Test fun wrongKeyOrInvalidCiphertextCannotFallBackToPlaintext() {
        val other = AesGcmNotificationCipher { SecretKeySpec(ByteArray(32) { 12 }, "AES") }
        val stored = cipher.encrypt("秘密消息", "key:body")
        assertThrows(NotificationEncryptionException::class.java) { other.decrypt(stored, "key:body") }
        assertThrows(NotificationEncryptionException::class.java) { cipher.decrypt("明文", "key:body") }
        assertThrows(NotificationEncryptionException::class.java) { cipher.decrypt("qx1:invalid", "key:body") }
    }

    @Test fun unavailableKeyFailsClosedOnWrite() {
        val failing = AesGcmNotificationCipher { error("Keystore unavailable") }
        assertThrows(NotificationEncryptionException::class.java) { failing.encrypt("私密内容", "key:body") }
    }
}
