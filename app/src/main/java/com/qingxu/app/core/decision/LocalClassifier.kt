package com.qingxu.app.core.decision

import com.qingxu.app.core.privacy.CloudPrivacyGate
import com.qingxu.app.data.local.NotificationEntry

/** Explainable on-device rules, with a visible fallback when evidence is limited. No model runs here. */
object LocalClassifier {
    private val transactionProblem = Regex("退款失败|退款异常|支付失败|扣款失败|交易异常|交易失败|支付异常|订单异常|付款失败|需要补充.{0,8}资料|请补充.{0,8}资料|配送异常|派送失败")
    private val transactionState = Regex("退款|支付|扣款|交易|账单|付款|订单|发货|签收|快递|物流|包裹|配送|派送|取件|验证码")
    private val transactionUpdate = Regex("支付成功|付款成功|扣款成功|退款成功|退款到账|交易成功|账单已出|订单已完成|已收款")
    private val deliveryUpdate = Regex("已发货|已经发货|快递已到|包裹已到|正在派送|派送中|运输中|物流更新|已签收|配送中|待取件|取件码|请.{0,4}取件")
    private val promotion = Regex("限时.{0,12}(优惠|抢购|折扣|特惠)|优惠券.{0,10}(领取|待领取|到账|过期)|领.{0,6}优惠券|满\\d+减\\d+|专属.{0,6}(福利|优惠)|低至.{0,5}折|秒杀|大促|立减\\d+|促销活动")
    private val eventContent = Regex("会议|日程|预约|课程|面试|提醒事项")
    private val imminentEvent = Regex("即将开始|马上开始|现在开始|已开始|还有\\s*([0-9]|[12][0-9]|30)\\s*分钟|(?<![0-9一二三四五六七八九十百两])(?:[1-9]|[12][0-9]|30|[一二三四五六七八九两]|十[一二三四五六七八九]?|二十[一二三四五六七八九]?|三十)分钟后.{0,5}开始")
    private val urgentAction = Regex("急事|请.{0,6}(立即|马上|立刻).{0,10}(回复|回电|确认|处理|联系)|(?:立即|马上|立刻|尽快).{0,6}(回复|回电|接电话|处理)|(?:现在|马上).{0,5}(需要你|需要您)|快.{0,3}来.{0,4}帮|(?<!不)紧急.{0,6}(求助|联系)")
    private val notUrgent = Regex("不着急|不用急|不急|不紧急|别着急|无需.{0,6}(立即|马上)|不必.{0,6}(立即|马上)")
    private val securityAlert = Regex("异地登录|异常登录|账号被盗|账户被盗|盗刷|火灾警报|燃气泄漏|地震预警|紧急求助|救命|求救|有人.{0,4}(?:落水|被困)|(?:家里|这里|楼里|楼下).{0,5}(?:着火|失火)")
    private val medicalCrisis = Regex("喘不过气|呼吸困难|昏迷|昏倒|晕倒|意识不清|胸痛|大出血|流血不止|严重过敏|过敏性休克|心脏骤停")
    private val medicalSetting = Regex("(?:在|到了?|送到|进了?).{0,3}(?:急诊|抢救室|急救室)|正在.{0,3}(?:抢救|急救)")
    private val medicalSubject = Regex("(?:我|我们|他|她|孩子|宝宝|妈妈|爸爸|父亲|母亲|家人|老公|老婆|病人|患者).{0,8}$")
    private val medicalHelp = Regex("(?:快|赶快|马上|立即|现在|赶紧|请).{0,6}(?:来|过来|联系|回电|回复|拨打|叫|送|帮)|(?:你|您).{0,5}(?:来一下|过来|来一趟|帮忙)|(?:打|叫|拨打).{0,3}120|救命|求救|需要.{0,5}帮助")
    // These exclusions are deliberately narrow rules, not semantic understanding. Check the risk's
    // own sentence so a separate “不着急带东西” cannot negate an actual medical emergency.
    private val nonCurrentContext = Regex("如果|万一|假如|假设|防范|预防|科普|教程|攻略|培训|演练|模拟|测试警报|误报|警报.{0,4}(?:解除|取消)|预警.{0,4}(?:解除|取消)|转发|转述|引用|例句|示例|台词|文章|新闻|报道|听说|据说|电影|剧集|电视剧|小说|游戏|昨天|昨日|上周|去年|此前|之前|回忆")
    private val promotionalContext = Regex("优惠|折扣|促销|特惠|秒杀|抢购|购买.{0,5}(?:保险|服务)|保险.{0,5}(?:购买|保障)")
    private val negatedRiskPrefix = Regex("(?:没有|未发现|并未|并非|不是|不在|未在|没在|不需要|已排除|已解除|已经解除|未发生|没发生|无需|不用|不|未|没)[^，,。！!？?；;\\n]{0,8}$")
    private val negatedRiskSuffix = Regex("^[^，,。！!？?；;\\n]{0,6}(?:已解除|已经解除|已排除|未发生|没有发生|是误报|只是演练)")
    private val verification = Regex("验证码|校验码|动态密码|一次性密码|verification\\s*code|security\\s*code|one[- ]time|\\botp\\b", RegexOption.IGNORE_CASE)
    private val shoppingSource = Regex("taobao|tmall|jingdong|jd\\.|pinduoduo|suning|vipshop|meituan|eleme|alipay|amazon|ebay|淘宝|天猫|京东|拼多多|购物|商城|美团|饿了么|支付宝", RegexOption.IGNORE_CASE)
    private val promotionalChannel = Regex("marketing|promotion|促销|营销|优惠", RegexOption.IGNORE_CASE)
    private val messagingSource = Regex("com\\.tencent\\.(mm|mobileqq)|org\\.telegram|com\\.whatsapp|com\\.discord|com\\.slack|微信|QQ|钉钉|飞书", RegexOption.IGNORE_CASE)
    private val hiddenContent = Regex("内容已隐藏|已隐藏.{0,4}内容|解锁.{0,10}(查看|消息)", RegexOption.IGNORE_CASE)
    private val messagePlaceholder = Regex("^\\[?(?:(?:你有|您有|收到|有)?(?:\\s*\\d+|[一二三四五六七八九十]+)?\\s*条?新消息|通知)\\]?[。.!！]?$", RegexOption.IGNORE_CASE)

    fun classify(entry: NotificationEntry, now: Long = System.currentTimeMillis()): Decision? {
        val text = "${entry.title}\n${entry.body}"
        val source = "${entry.packageName} ${entry.appLabel}"
        val chat = messagingSource.containsMatchIn(source)
        // Secrets never need a server to decide visibility; codes are useful immediately, old codes can wait.
        if (verification.containsMatchIn(text)) return if (isRecent(entry, now)) Decision(
            DecisionBucket.NOW, "verification", "验证码仅在本机整理，方便你及时查看", 0.97,
        ) else Decision(DecisionBucket.LATER, "verification", "较早的验证码仅保留在本机", 0.9)
        // A chat app is a source, not evidence that its message is routine. Real safety and medical
        // signals must be checked before the ordinary-chat fallback and never auto-hidden as ads.
        val risk = immediateRisk(text)
        if (risk != null) return if (isRecent(entry, now)) Decision(
            DecisionBucket.NOW, risk, if (risk == "security") "本机规则发现明确的安全风险或求救信号，请及时查看"
                else "本机规则发现急诊求助或身体危险信号，请及时查看", 0.94,
        ) else Decision(DecisionBucket.LATER, risk, "较早的风险提醒已保留，请核实当前情况", 0.85)
        if (CloudPrivacyGate.containsSensitiveSecret(text)) return null
        if (chat) {
            // A person's message quoting a promotion or transaction is never auto-hidden.
            return if (affirmedSentences(text, urgentAction).any { !notUrgent.containsMatchIn(it) } && isRecent(entry, now))
                Decision(DecisionBucket.NOW, "message", "消息明确请求及时回复或处理", 0.9)
            else null
        }
        if (transactionProblem.containsMatchIn(text)) return Decision(
            DecisionBucket.NOW, "transaction", "检测到交易或履约异常，可能需要你处理", 0.96,
        )
        if (eventContent.containsMatchIn(text)) {
            if (imminentEvent.containsMatchIn(text)) {
                // Reconnecting the listener may replay hours-old “starts in 10 minutes” notifications.
                return if (!isRecent(entry, now)) Decision(
                    DecisionBucket.REVIEW, "event", "这条日程提醒的时间已不确定，请查看原通知", 0.0,
                ) else Decision(DecisionBucket.NOW, "event", "通知明确提示日程即将开始", 0.95)
            }
            return Decision(DecisionBucket.LATER, "event", "日程已留在稍后看，通知未提示即将开始", 0.85)
        }
        if (deliveryUpdate.containsMatchIn(text)) return Decision(
            DecisionBucket.LATER, "delivery", "普通物流进度或取件提醒，暂未发现需要立即处理的内容", 0.93,
        )
        if (transactionUpdate.containsMatchIn(text)) return Decision(
            DecisionBucket.LATER, "transaction", "交易状态更新，暂未发现需要立即处理的内容", 0.92,
        )
        val promotional = promotion.containsMatchIn(text)
        // Mixed marketing/transaction text is kept visible until its meaning is clearer.
        if (promotional && transactionState.containsMatchIn(text)) return null
        if (promotional && (shoppingSource.containsMatchIn(source) ||
                promotionalChannel.containsMatchIn(entry.channelId.orEmpty()))) return Decision(
            DecisionBucket.HIDDEN, "marketing", "识别到促销邀请，已安静收起，可随时恢复", 0.95,
        )
        return null
    }

    /** A provisional visible location; this is not a claim that an unknown message is unimportant. */
    @Suppress("UNUSED_PARAMETER")
    fun fallback(entry: NotificationEntry, now: Long = System.currentTimeMillis()): Decision {
        val text = "${entry.title}\n${entry.body}".trim()
        val content = entry.body.ifBlank { entry.title }.trim()
        if (content.length < 4 || hiddenContent.containsMatchIn(text) || messagePlaceholder.matches(content)) return Decision(
            DecisionBucket.REVIEW, "unknown", "通知未提供足够正文，保留在其他消息中", 0.0,
        )
        val chat = messagingSource.containsMatchIn("${entry.packageName} ${entry.appLabel}")
        return Decision(DecisionBucket.LATER, if (chat) "message" else "unknown",
            if (CloudPrivacyGate.containsSensitiveSecret(text)) "敏感内容仅在本机整理，已保留可见"
            else if (chat) "普通消息已保留在稍后看，暂未检测到明确急事"
            else "已保留在稍后看，暂未检测到明确急事", 0.5)
    }

    private fun isRecent(entry: NotificationEntry, now: Long) = now - entry.postedAt <= 30 * 60_000L &&
        entry.postedAt - now <= 5 * 60_000L

    private fun immediateRisk(text: String): String? {
        if (affirmedSentences(text, securityAlert).isNotEmpty()) return "security"
        if (affirmedSentences(text, medicalSetting).any { medicalHelp.containsMatchIn(text) }) return "medical"
        if (affirmedSentences(text, medicalCrisis).any { sentence ->
                medicalHelp.containsMatchIn(sentence) || medicalCrisis.findAll(sentence).any {
                    medicalSubject.containsMatchIn(sentence.substring(0, it.range.first))
                }
            }) return "medical"
        return null
    }

    private fun affirmedSentences(text: String, signal: Regex): List<String> =
        text.split(Regex("[。！!？?；;\\n]+"))
            .filter { sentence ->
                !nonCurrentContext.containsMatchIn(sentence) && !promotionalContext.containsMatchIn(sentence) &&
                    signal.findAll(sentence).any { match ->
                        !negatedRiskPrefix.containsMatchIn(sentence.substring(0, match.range.first).takeLast(24)) &&
                            !negatedRiskSuffix.containsMatchIn(sentence.substring(match.range.last + 1).take(24))
                    }
            }
}
