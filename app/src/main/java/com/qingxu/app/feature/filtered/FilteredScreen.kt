package com.qingxu.app.feature.filtered

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qingxu.app.core.ui.NotificationCard
import com.qingxu.app.core.ui.QuietState
import com.qingxu.app.core.decision.DecisionBucket
import com.qingxu.app.core.decision.effectiveBucket
import com.qingxu.app.data.local.NotificationEntry

@Composable
fun FilteredScreen(
    entries: List<NotificationEntry>,
    onRestore: (NotificationEntry) -> Unit,
    onInterest: (NotificationEntry) -> Unit,
    tutorial: (@Composable () -> Unit)? = null,
    onHideSimilar: (NotificationEntry) -> Unit = {},
    concealContent: Boolean = false,
    onUnlock: () -> Unit = {},
) {
    val filtered = entries.filter { it.effectiveBucket() == DecisionBucket.HIDDEN }.sortedByDescending { it.postedAt }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tutorial != null) item(key = "tutorial") { tutorial() }
        if (filtered.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("安静收好，随时找回", style = MaterialTheme.typography.headlineSmall)
                if (filtered.isNotEmpty()) Text("${filtered.size} 条已收起", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (filtered.isEmpty() && tutorial == null) item { QuietState("这里暂时很安静", "收起的消息不会被删除。") }
        items(filtered, key = { it.notificationKey }) { entry ->
            Box(Modifier.animateItem()) {
                NotificationCard(entry, onClassify = {}, onInterest = { onInterest(entry) },
                    onRestore = { onRestore(entry) }, onOpen = {}, onHideSimilar = { onHideSimilar(entry) },
                    concealContent = concealContent, onUnlock = onUnlock)
            }
        }
    }
}
