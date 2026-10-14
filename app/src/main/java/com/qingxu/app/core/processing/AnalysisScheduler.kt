package com.qingxu.app.core.processing

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object AnalysisScheduler {
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("qingxu-analysis", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Pending lives in Room. This repair pass closes process-death windows around enqueue. */
    fun installRepair(context: Context) {
        val request = PeriodicWorkRequestBuilder<AnalysisWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("qingxu-analysis-repair", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
