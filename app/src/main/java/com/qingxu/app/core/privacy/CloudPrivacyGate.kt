package com.qingxu.app.core.privacy

import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.data.local.NotificationEntry

enum class CloudPermission { ALLOWED, DISABLED, SOURCE_NOT_ALLOWED, LOCAL_ONLY, LOCKED, SENSITIVE }

/** Runs before constructing any network request. A model never decides its own upload permission. */
object CloudPrivacyGate {
    fun permission(entry: NotificationEntry, ai: AiSettings, privacy: PrivacySettings, deviceLocked: Boolean): CloudPermission = when {
        entry.packageName in privacy.localOnlyPackages -> CloudPermission.LOCAL_ONLY
        containsSensitiveSecret(entry.title + " " + entry.body) -> CloudPermission.SENSITIVE
        !ai.enabled -> CloudPermission.DISABLED
        entry.packageName !in ai.allowedPackages -> CloudPermission.SOURCE_NOT_ALLOWED
        privacy.blockCloudWhenLocked && deviceLocked -> CloudPermission.LOCKED
        else -> CloudPermission.ALLOWED
    }

    /** Limited pattern redaction, never a guarantee of anonymity. Relationships/events can remain private. */
    fun prepare(entry: NotificationEntry): NotificationEntry = entry.copy(
        notificationKey = "cloud-input", packageName = "", appLabel = redact(entry.appLabel),
        title = redact(entry.title), body = redact(entry.body), channelId = null,
        contentHash = "", machineReason = "", previousLabel = com.qingxu.app.data.local.UserLabel.UNSORTED,
    )

    fun redact(text: String): String = text
        .replace(Regex("https?://[^\\s，。；、<>]+", RegexOption.IGNORE_CASE), "[链接]")
        .replace(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE), "[邮箱]")
        .replace(Regex("(?<![A-Za-z0-9])(?:\\+?\\d[\\d ()-]{6,}\\d)(?![A-Za-z0-9])"), "[号码]")

    fun containsSensitiveSecret(text: String): Boolean = Regex(
        "验证码|校验码|动态密码|一次性密码|银行卡密码|身份证|verification\\s*code|security\\s*code|one[- ]time|\\botp\\b|\\bcode\\b.{0,16}\\b\\d{4,8}\\b|sk-[A-Za-z0-9_-]{12,}",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(text)
}
