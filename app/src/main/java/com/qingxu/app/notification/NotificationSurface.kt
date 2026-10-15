package com.qingxu.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.qingxu.app.MainActivity
import com.qingxu.app.R
import com.qingxu.app.core.decision.*
import com.qingxu.app.core.privacy.CloudPrivacyGate
import com.qingxu.app.data.local.NotificationDao
import com.qingxu.app.data.local.NotificationEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SurfaceSettings(val summary: Boolean = false, val removePromotions: Boolean = false)

class NotificationSurfacePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("notification_surface", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(SurfaceSettings(prefs.getBoolean("summary", false), prefs.getBoolean("remove_promotions", false)))
    val state = mutableState.asStateFlow()
    fun save(summary: Boolean, removePromotions: Boolean) {
        check(prefs.edit().putBoolean("summary", summary).putBoolean("remove_promotions", removePromotions).commit())
        mutableState.value = SurfaceSettings(summary, removePromotions)
    }
}

internal fun NotificationEntry.canRemovePromotion(): Boolean = handledAt == null && !hasCurrentCorrection() && !interested &&
    !CloudPrivacyGate.containsSensitiveSecret(title + " " + body) &&
    analysisStatus == "done" && analysisSource in setOf("local", "ai", "jev") &&
    CloudDecisionPolicy.protect(this, Decision(effectiveBucket(), machineKind, machineReason, machineConfidence, analysisSource)).isLongTermHideEligible()

/** Silent, count-only surface. Original notification delivery happens before listener callbacks. */
class NotificationSurface(
    context: Context, private val dao: NotificationDao, private val prefs: NotificationSurfacePreferences,
    private val gate: Mutex, private val selected: () -> Set<String>, private val ensureReady: suspend () -> Unit,
) {
    private val context = context.applicationContext
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        scope.launch {
            for (ignored in requests) try {
                ensureReady()
                gate.withLock {
                    val entries = dao.observeAll().first().filter { it.packageName in selected() }
                    val settings = prefs.state.value
                    withContext(Dispatchers.Main.immediate) {
                        updateSummary(if (settings.summary) entries.count { it.handledAt == null && it.displayBucket() == DecisionBucket.NOW } else 0)
                        if (settings.removePromotions && NotificationAccess.granted(context))
                            entries.filter { it.canRemovePromotion() && it.removedAt == null }.forEach { QingxuNotificationListener.removeConfirmedPromotion(it) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Surface failures must not stop capture or analysis. */ }
        }
    }
    fun refresh() { requests.trySend(Unit) }
    private fun updateSummary(count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (count == 0 || !manager.areNotificationsEnabled()) { manager.cancel(SUMMARY_ID); return }
        manager.createNotificationChannel(NotificationChannel(SUMMARY_CHANNEL, "优先消息摘要", NotificationManager.IMPORTANCE_LOW))
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(SUMMARY_ID, Notification.Builder(context, SUMMARY_CHANNEL)
            .setSmallIcon(R.drawable.qingxu_notification).setContentTitle("轻序 · 有 $count 条消息优先看")
            .setContentText("点开查看整理结果").setContentIntent(intent).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET).setAutoCancel(true).build())
    }
    companion object { const val SUMMARY_ID = 17002; const val SUMMARY_CHANNEL = "priority_summary" }
}
