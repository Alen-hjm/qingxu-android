package com.qingxu.app.core.decision

import com.qingxu.app.data.local.NotificationEntry

/** Model evidence is untrusted input. This policy owns visibility and never executes model text. */
object CloudDecisionPolicy {
    fun decide(
        kind: String,
        important: Boolean,
        urgent: Boolean,
        actionRequired: Boolean,
        confidence: Double,
        source: String = "ai",
    ): Decision {
        val bucket = when {
            !confidence.isFinite() || confidence < 0.8 || kind == "unknown" -> DecisionBucket.REVIEW
            kind == "marketing" && confidence < 0.95 -> DecisionBucket.REVIEW
            kind == "marketing" && !important && !urgent && !actionRequired -> DecisionBucket.HIDDEN
            kind != "marketing" && important && urgent && actionRequired -> DecisionBucket.NOW
            else -> DecisionBucket.LATER
        }
        return Decision(bucket, kind, template(bucket), if (confidence.isFinite()) confidence.coerceIn(0.0, 1.0) else 0.0, source)
    }

    fun protect(entry: NotificationEntry, decision: Decision, now: Long = System.currentTimeMillis()): Decision {
        val text = "${entry.title}\n${entry.body}"
        if (text.isBlank() || instructionLike.containsMatchIn("${entry.appLabel}\n$text")) return review(decision,
            "内容不足或包含影响判断的指令，已保留可见")
        // Capture already caps these fields; equality can therefore mean text was truncated upstream.
        if (decision.bucket == DecisionBucket.HIDDEN && (entry.title.length >= 300 || entry.body.length >= 4000)) {
            return review(decision, "内容过长，不能仅凭部分内容收起通知")
        }
        if (decision.bucket == DecisionBucket.NOW && decision.kind == "marketing") return review(decision,
            "促销倒计时不作为紧急依据，已保留可见")
        if (decision.bucket == DecisionBucket.NOW && decision.kind == "event" &&
            (now - entry.postedAt > 30 * 60_000L || entry.postedAt - now > 5 * 60_000L)) {
            return review(decision, "日程时间无法确认，请查看原通知")
        }
        if (decision.bucket != DecisionBucket.HIDDEN) return decision
        val source = "${entry.packageName} ${entry.appLabel}"
        if (decision.kind != "marketing" || decision.confidence < 0.95 ||
            messagingSource.containsMatchIn(source) || sensitiveOrTransactional.containsMatchIn(text) ||
            !promotion.containsMatchIn(text) ||
            !(shoppingSource.containsMatchIn(source) || promotionalChannel.containsMatchIn(entry.channelId.orEmpty()))) {
            return review(decision, "不能确认是单纯促销，已保留可见")
        }
        return decision
    }

    private fun review(decision: Decision, reason: String) = decision.copy(bucket = DecisionBucket.REVIEW, reason = reason)
    private fun template(bucket: DecisionBucket) = when (bucket) {
        DecisionBucket.NOW -> "判断为需要及时处理的消息，请查看原通知"
        DecisionBucket.LATER -> "暂未发现必须立即处理的事项，已保留在稍后看"
        DecisionBucket.HIDDEN -> "判断为单纯促销，已安静收起，可随时恢复"
        DecisionBucket.REVIEW -> "判断依据不足，已保留可见"
    }
    private val instructionLike = Regex("忽略.{0,20}(指令|规则|系统)|无视.{0,12}(指令|规则)|system\\s*:|assistant\\s*:|<\\|(?:system|assistant)|将.{0,16}(分类|标记|判断)为|输出.{0,12}(JSON|HIDDEN|marketing)|ignore.{0,30}(instructions|rules)|\\\"(?:kind|bucket|important|actionRequired)\\\"\\s*:", RegexOption.IGNORE_CASE)
    private val sensitiveOrTransactional = Regex("退款|支付|扣款|交易|账单|付款|订单|发货|签收|快递|物流|包裹|配送|派送|取件|验证码|校验码|密码|口令|医疗|医院|诊断|复诊|过敏|急救|安全|异地登录|异常登录|燃气|火灾|盗刷")
    private val promotion = Regex("优惠|折扣|促销|秒杀|大促|满\\d+减\\d+|立减|低至.{0,5}折|抢购|特惠")
    private val shoppingSource = Regex("taobao|tmall|jingdong|jd\\.|pinduoduo|suning|vipshop|meituan|eleme|amazon|ebay|淘宝|天猫|京东|拼多多|购物|商城|美团|饿了么", RegexOption.IGNORE_CASE)
    private val promotionalChannel = Regex("marketing|promotion|促销|营销|优惠", RegexOption.IGNORE_CASE)
    private val messagingSource = Regex("com\\.tencent\\.(mm|mobileqq)|org\\.telegram|com\\.whatsapp|com\\.discord|com\\.slack|微信|QQ|钉钉|飞书", RegexOption.IGNORE_CASE)
}
