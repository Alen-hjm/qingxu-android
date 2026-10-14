package com.qingxu.app.core.decision

import com.qingxu.app.data.local.NotificationEntry

/** Small, explainable rules. Unknown text remains visible and may be sent to an opted-in model. */
object LocalClassifier {
    private val transactionProblem = Regex("退款失败|退款异常|支付失败|扣款失败|交易异常|交易失败|支付异常|订单异常|付款失败|需要补充.{0,8}资料|请补充.{0,8}资料|配送异常|派送失败")
    private val transactionState = Regex("退款|支付|扣款|交易|账单|付款|订单|发货|签收|快递|物流|包裹|配送|派送|取件|验证码")
    private val deliveryUpdate = Regex("已发货|已经发货|快递已到|包裹已到|正在派送|派送中|运输中|物流更新|已签收|配送中")
    private val promotion = Regex("限时.{0,12}(优惠|抢购|折扣|特惠)|优惠券.{0,10}(领取|待领取|到账|过期)|领.{0,6}优惠券|满\\d+减\\d+|专属.{0,6}(福利|优惠)|低至.{0,5}折|秒杀|大促|立减\\d+|促销活动")
    private val eventContent = Regex("会议|日程|预约|课程|面试|提醒事项")
    private val imminentEvent = Regex("即将开始|马上开始|现在开始|已开始|还有\\s*([0-9]|[12][0-9]|30)\\s*分钟|(?<![0-9一二三四五六七八九十百两])(?:[1-9]|[12][0-9]|30|[一二三四五六七八九两]|十[一二三四五六七八九]?|二十[一二三四五六七八九]?|三十)分钟后.{0,5}开始")
    private val shoppingSource = Regex("taobao|tmall|jingdong|jd\\.|pinduoduo|suning|vipshop|meituan|eleme|alipay|amazon|ebay|淘宝|天猫|京东|拼多多|购物|商城|美团|饿了么|支付宝", RegexOption.IGNORE_CASE)
    private val promotionalChannel = Regex("marketing|promotion|促销|营销|优惠", RegexOption.IGNORE_CASE)
    private val messagingSource = Regex("com\\.tencent\\.(mm|mobileqq)|org\\.telegram|com\\.whatsapp|com\\.discord|com\\.Slack|微信|QQ|钉钉|飞书", RegexOption.IGNORE_CASE)

    fun classify(entry: NotificationEntry, now: Long = System.currentTimeMillis()): Decision? {
        val text = "${entry.title}\n${entry.body}"
        val source = "${entry.packageName} ${entry.appLabel}"
        // A person talking about an offer or a payment is not a promotional system notification.
        if (messagingSource.containsMatchIn(source)) return null
        val promotional = promotion.containsMatchIn(text)
        // Mixed marketing/transaction text needs context. Never hide it because one word matched.
        if (promotional && transactionState.containsMatchIn(text)) return null
        if (transactionProblem.containsMatchIn(text)) return Decision(
            DecisionBucket.NOW, "transaction", "检测到交易或履约异常，可能需要你处理", 0.96,
        )
        if (eventContent.containsMatchIn(text) && imminentEvent.containsMatchIn(text)) {
            // Reconnecting the listener may replay hours-old “starts in 10 minutes” notifications.
            val age = now - entry.postedAt
            return if (age > 30 * 60_000L || age < -5 * 60_000L) Decision(
                DecisionBucket.REVIEW, "event", "这条日程提醒的时间已不确定，请查看原通知", 0.0,
            ) else Decision(DecisionBucket.NOW, "event", "通知明确提示日程即将开始", 0.95)
        }
        if (promotional && (shoppingSource.containsMatchIn(source) ||
                promotionalChannel.containsMatchIn(entry.channelId.orEmpty()))) return Decision(
            DecisionBucket.HIDDEN, "marketing", "识别到促销邀请，已安静收起，可随时恢复", 0.95,
        )
        if (deliveryUpdate.containsMatchIn(text)) return Decision(
            DecisionBucket.LATER, "delivery", "普通物流进度更新，暂未发现需要立即处理的内容", 0.93,
        )
        return null
    }
}
