package com.qingxu.app.core.processing

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object AnalysisScheduler {
    internal const val INCOMING_WORK = "qingxu-analysis-incoming"
    internal const val RETRY_WORK = "qingxu-analysis-retries"
    internal const val RETRY_ONLY = "retry_only"

    fun enqueue(context: Context) {
        // Provider failures use a separate retry chain so new arrivals can still be analyzed.
        WorkManager.getInstance(context).enqueueUniqueWork(INCOMING_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AnalysisWorker>().build())
    }

    fun enqueueRetry(context: Context) {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setInputData(workDataOf(RETRY_ONLY to true))
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Append also preserves a wakeup arriving while the last retry worker is finishing.
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Pending lives in Room. This repair pass closes process-death windows around enqueue. */
    fun installRepair(context: Context) {
        val manager = WorkManager.getInstance(context)
        // Upgrade from the old queue where cloud retries blocked every later notification.
        manager.cancelUniqueWork("qingxu-analysis")
        val request = PeriodicWorkRequestBuilder<AnalysisWorker>(15, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork("qingxu-analysis-repair", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
