package com.qingxu.app.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.ui.BrandFace
import com.qingxu.app.data.local.UserLabel
import com.qingxu.app.data.repository.OnboardingState
import kotlinx.coroutines.delay

/** Instructions sit beside the actual control, rather than in a separate slideshow. */
@Composable
fun CoachHint(title: String, instruction: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .12f))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            BrandFace(Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(instruction, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun GuideDock(step: Int, page: Int, onNavigate: (Int) -> Unit, onFinish: () -> Unit) {
    val complete = step >= 4
    val instruction = when {
        step < 2 && page == 2 -> "点「整理哪些应用」，选一个常用应用。"
        step < 2 && page != 5 -> "先选应用，再连接通知访问。"
        step >= 2 && page != 0 -> "回到收件箱，试试消息自动整理。"
        else -> null
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 154.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (instruction != null) CoachHint("跟着做一次", instruction)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (complete) "已体验自动整理" else "使用指引 · ${if (step < 2) step + 1 else 3}/3",
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (instruction != null) TextButton(onClick = { onNavigate(if (step < 2) 5 else 0) }) { Text("带我过去") }
            TextButton(onClick = onFinish) { Text(if (complete) "开始使用" else "稍后再学") }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun OnboardingExample(state: OnboardingState, onClassify: (UserLabel) -> Unit,
    onStar: () -> Unit, onRestore: () -> Unit, onDemo: () -> Unit = {}) {
    var phase by rememberSaveable { mutableIntStateOf(if (state.practice >= 2) 2 else 0) }
    val demoComplete by rememberUpdatedState(onDemo)
    LaunchedEffect(phase) {
        if (phase == 1) {
            delay(900)
            phase = 2
            demoComplete()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("试用自动整理", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("虚构示例 · 不保存", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedContent(targetState = phase, transitionSpec = {
            (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 12 }).togetherWith(fadeOut(tween(120)))
        }, label = "automatic-demo") { shownPhase ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (shownPhase) {
                    0 -> {
                        CoachHint("让轻序先判断", "用 3 条示例，看看哪些消息值得先看。")
                        Button(onClick = { phase = 1 }, modifier = Modifier.fillMaxWidth()) { Text("试试自动整理") }
                    }
                    1 -> {
                        Text("收到 3 条示例，正在整理…", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        DemoMessage("新消息", "退款失败，请补充收款信息")
                        DemoMessage("新消息", "小林：周末一起吃饭吗？")
                        DemoMessage("新消息", "限时促销，今晚最后两小时")
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text("已自动排好顺序", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        DemoMessage("现在看", "退款失败，请补充收款信息", prominent = true)
                        DemoMessage("稍后看", "小林：周末一起吃饭吗？")
                        DemoMessage("已收起", "限时促销，今晚最后两小时", quiet = true)
                        Text("真实消息：点卡片查看，处理后点「已处理」。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("示例不代表通知已接通；用「测试接收」验证。", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { phase = 1 }) { Text("再试一次") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoMessage(group: String, title: String, prominent: Boolean = false, quiet: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp),
        color = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(group, style = MaterialTheme.typography.labelSmall,
                color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = if (quiet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
    }
}
