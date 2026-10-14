package com.qingxu.app.feature.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.ui.BrandFace
import com.qingxu.app.core.ui.NotificationCard
import com.qingxu.app.data.local.NotificationEntry
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.data.repository.OnboardingState

/** An instruction lives with its actual control, instead of occupying a separate tutorial page. */
@Composable
fun CoachHint(title: String, instruction: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .16f))) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            BrandFace(Modifier.size(38.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(instruction, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun GuideDock(step: Int, page: Int, onNavigate: (Int) -> Unit, onFinish: () -> Unit) {
    val instruction = when {
        step < 2 && page == 2 -> "点「整理哪些应用」，选择想整理的应用。"
        step < 2 && page != 5 -> "先选想整理的应用，再开启通知访问。"
        step == 3 && page != 1 -> "你刚收起了示例。点下方「已收起」，试着把它恢复回来。"
        step == 2 && page != 0 -> "点下方「收件箱」，看看轻序如何自动整理消息。"
        step == 4 && page != 0 -> "恢复成功。点下方「收件箱」，看看它回到了哪里。"
        else -> null
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        if (instruction != null) CoachHint("小耳带你用轻序", instruction)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (step == 4) "已体验自动整理" else "使用指引 · ${step + 1}/4",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (instruction != null) TextButton(onClick = { onNavigate(when {
                step < 2 -> 5; step == 3 -> 1; else -> 0
            }) }) { Text("带我过去") }
            TextButton(onClick = onFinish) { Text(if (step == 4) "开始使用" else "稍后再学") }
        }
    }
}

@Composable
fun OnboardingExample(state: OnboardingState, onClassify: (UserLabel) -> Unit,
    onStar: () -> Unit, onRestore: () -> Unit) {
    val timestamp = remember { System.currentTimeMillis() }
    val manual = state.practice > 0 || state.label != UserLabel.UNSORTED
    val entry = NotificationEntry(
        notificationKey = "qingxu-tutorial-example", packageName = "", appLabel = "购物应用 · 练习示例",
        title = "退款失败，请补充收款信息", body = "你的退款需要补充资料后才能继续处理。",
        channelId = null, postedAt = timestamp, updatedAt = timestamp, contentHash = "example",
        userLabel = if (state.practice == 1) UserLabel.FILTERED else state.label,
        labelSource = if (manual) "manual" else "unclassified", manualVersion = if (manual) 1 else 0,
        interested = state.interested,
        machineBucket = "NOW", machineKind = "transaction",
        machineReason = "退款出现异常，需要你补充资料，优先保留可见。",
        machineConfidence = .96, analysisStatus = "done", analysisSource = "local",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("离线练习 · 不保存、不发送到云端", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        if (state.practice == 0) {
            CoachHint("促销收起，异常先看", "假设下面的退款已处理，试着点「这条收起来」。")
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("已收起 · 营销促销", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("限时优惠，今晚最后两小时", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("现在看 · 需要处理", style = MaterialTheme.typography.titleSmall)
        } else if (state.practice == 1) {
            CoachHint("找到了，试着恢复", "点「恢复到收件箱」，只调整这一条。")
        } else {
            CoachHint("恢复好了", "点整理来源查看原因，点星星收藏。真实消息的更多调整在「···」里。")
        }
        NotificationCard(entry, onClassify, onStar, onRestore, onOpen = {}, showOriginalAction = false,
            guideTarget = when (state.practice) { 0 -> "filter"; 1 -> "restore"; else -> null })
    }
}
