package com.qingxu.app.core.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.qingxu.app.R
import kotlinx.coroutines.launch

@Composable
fun BrandFace(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.qingxu_face), contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(16.dp)))
}

@Composable
fun ListeningMascot(modifier: Modifier = Modifier) {
    val tilt = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Image(painterResource(if (isSystemInDarkTheme()) R.drawable.qingxu_face else R.drawable.qingxu_mascot), contentDescription = "小耳正在倾听，点击打个招呼",
        modifier = modifier.clip(RoundedCornerShape(28.dp)).graphicsLayer { rotationZ = tilt.value }
            .clickable(onClickLabel = "和小耳打招呼") {
                if (ValueAnimator.areAnimatorsEnabled() && !tilt.isRunning) scope.launch {
                    tilt.animateTo(-5f, tween(130))
                    tilt.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                }
            })
}

/** A short, interruptible cold-launch greeting. No delay is inserted into data loading. */
@Composable
fun BrandLaunch(content: @Composable () -> Unit) {
    var finished by rememberSaveable { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    val reveal = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (!finished) {
            reveal.animateTo(1f, tween(480))
            fade.animateTo(0f, tween(220))
            finished = true
        }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        if (!finished) Surface(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }
            .clickable(onClickLabel = "跳过启动动画") { finished = true }, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                BrandFace(Modifier.size(116.dp).graphicsLayer {
                    alpha = reveal.value
                    scaleX = .86f + reveal.value * .14f
                    scaleY = scaleX
                    translationY = (1f - reveal.value) * 32f
                })
                Spacer(Modifier.height(28.dp))
                Text("轻序", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("让你听到真正想听的", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun QuietState(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListeningMascot(Modifier.size(172.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun FocusHeader(urgent: Int, total: Int) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.surface)))
        .padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("留一点安静给自己", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(if (urgent > 0) "$urgent 条需要先看" else "重要的，慢慢看清", style = MaterialTheme.typography.headlineSmall)
            Text(if (total > 0) "已收纳 $total 条 · 轻重缓急由你决定" else "把零散的通知，整理成自己的节奏。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        BrandFace(Modifier.size(64.dp))
    }
}
