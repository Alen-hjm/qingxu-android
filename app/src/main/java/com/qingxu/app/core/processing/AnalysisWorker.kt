package com.qingxu.app.core.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qingxu.app.QingxuApplication
import kotlinx.coroutines.CancellationException

class AnalysisWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val container = (applicationContext as QingxuApplication).container
        val processor = container.processor
        val retryOnly = inputData.getBoolean(AnalysisScheduler.RETRY_ONLY, false)
        val retriesRemain = processor.processBatch(runAttemptCount, includeRetry = retryOnly, onlyRetry = retryOnly)
        container.surface.refresh()
        if (retryOnly && retriesRemain) Result.retry() else {
            if (retriesRemain) AnalysisScheduler.enqueueRetry(applicationContext)
            Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Unexpected storage/runtime failures can retry; new arrivals have their own independent chain.
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
