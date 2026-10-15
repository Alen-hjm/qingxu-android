package com.qingxu.app.data.local

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * A notification title can scope a user's explicit preference, but cannot identify a real person.
 * Keep only a digest in preferences; titles and message text never become rule-table columns.
 */
object SenderPreferencePolicy {
    private val chatPackages = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.wework",
        "com.alibaba.android.rimet", "com.ss.android.lark", "com.larksuite.suite",
        "org.telegram.messenger", "org.thunderdog.challegram", "com.whatsapp", "com.whatsapp.w4b",
        "com.discord", "com.slack", "com.google.android.apps.messaging", "com.android.mms",
    )
    private val whitespace = Regex("\\s+")
    private val countSuffix = Regex("\\(\\s*[0-9]+\\s*\\)$|（\\s*[0-9]+\\s*）$|\\[\\s*[0-9]+\\s*\\]$")
    private val summary = Regex(
        "(?:[0-9一二三四五六七八九十百千两]+\\s*(?:条|个|位|则)\\s*(?:新?消息|通知|联系人|会话))|" +
            "(?:[0-9]+\\s*(?:new\\s+)?(?:messages?|notifications?|chats?))",
        RegexOption.IGNORE_CASE,
    )
    private val hidden = Regex(
        "内容.{0,4}(?:隐藏|不可见)|隐藏.{0,4}(?:内容|消息)|解锁.{0,12}(?:查看|消息)|" +
            "打开.{0,12}(?:查看|阅读)(?:消息|详情)|(?:message|notification)\\s+(?:content\\s+)?(?:hidden|private)|" +
            "unlock.{0,20}(?:message|view)",
        RegexOption.IGNORE_CASE,
    )
    private val placeholder = Regex(
        "^(?:(?:你|您)?(?:有|收到|收到了一?|收到了|收到一)?\\s*[0-9一二三四五六七八九十百两]*\\s*条?\\s*(?:新?消息|通知)|" +
            "(?:new\\s+)?(?:message|notification))$",
        RegexOption.IGNORE_CASE,
    )
    private val genericTitles = setOf(
        "微信", "qq", "tim", "企业微信", "钉钉", "飞书", "telegram", "whatsapp", "discord", "slack",
        "信息", "短信", "消息", "新消息", "通知", "聊天消息", "messages", "message", "notifications",
    )

    fun senderScope(entry: NotificationEntry): String? {
        if (entry.packageName.lowercase(Locale.ROOT) !in chatPackages) return null
        val title = normalized(entry.title)
        val body = normalized(entry.body)
        if (title.isEmpty() || title.length > 80 || entry.title.contains('\n') || entry.title.contains('\r')) return null
        if (title.lowercase(Locale.ROOT) in genericTitles || title.equals(normalized(entry.appLabel), ignoreCase = true)) return null
        if (title.removeSuffix("通知").removeSuffix("消息").equals(normalized(entry.appLabel), ignoreCase = true)) return null
        if (countSuffix.containsMatchIn(title)) return null
        if (title.none { it.isLetter() } || summary.containsMatchIn(title) || hidden.containsMatchIn(title)) return null
        if (body.isEmpty() || hidden.containsMatchIn(body) || isPlaceholder(body) || isPlaceholder(title)) return null
        val scopedTitle = "qingxu.conversation.v1\u0000${entry.packageName}\u0000$title"
        return MessageDigest.getInstance("SHA-256").digest(scopedTitle.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun normalized(value: String): String = whitespace.replace(
        Normalizer.normalize(value, Normalizer.Form.NFC).trim(), " ",
    )

    private fun isPlaceholder(value: String): Boolean = placeholder.matches(
        value.trim('[', ']', '【', '】', '。', '.', '!', '！', ' '),
    ) || summary.matches(value.trim('[', ']', '【', '】', '。', '.', '!', '！', ' '))
}
