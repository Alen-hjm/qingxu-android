package com.qingxu.app.core.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qingxu.app.QingxuApplication
import kotlinx.coroutines.CancellationException

class AnalysisWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val processor = (applicationContext as QingxuApplication).container.processor
        if (processor.processBatch(runAttemptCount)) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
