package com.qingxu.app.data.local

import com.qingxu.app.core.privacy.AesGcmNotificationCipher
import com.qingxu.app.core.privacy.NotificationCipher
import javax.crypto.spec.SecretKeySpec

internal fun testNotificationCipher(): NotificationCipher =
    AesGcmNotificationCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
