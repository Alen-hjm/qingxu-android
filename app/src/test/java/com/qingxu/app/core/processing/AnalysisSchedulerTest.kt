package com.qingxu.app.core.processing

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AnalysisSchedulerTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                object : Worker(appContext, workerParameters) {
                    override fun doWork(): Result = if (inputData.getBoolean(AnalysisScheduler.RETRY_ONLY, false))
                        Result.retry() else Result.success()
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        manager = WorkManager.getInstance(context)
    }

    @After fun close() { WorkManagerTestInitHelper.closeWorkDatabase() }

    @Test fun incomingWorkRunsWhileCloudRetriesAreInBackoff() {
        AnalysisScheduler.enqueueRetry(context)
        val retry = work(AnalysisScheduler.RETRY_WORK).single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(retry.id)
        val backingOff = manager.getWorkInfoById(retry.id).get(10, TimeUnit.SECONDS)!!
        assertEquals(WorkInfo.State.ENQUEUED, backingOff.state)
        assertEquals(1, backingOff.runAttemptCount)

        AnalysisScheduler.enqueue(context)
        assertEquals(WorkInfo.State.SUCCEEDED, work(AnalysisScheduler.INCOMING_WORK).single().state)
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(retry.id).get(10, TimeUnit.SECONDS)!!.state)
    }

    @Test fun retryWakeupIsAppendedRatherThanDiscardedWhenAnotherRetryExists() {
        AnalysisScheduler.enqueueRetry(context)
        AnalysisScheduler.enqueueRetry(context)
        val requests = work(AnalysisScheduler.RETRY_WORK)
        assertEquals(2, requests.size)
        assertEquals(1, requests.count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(1, requests.count { it.state == WorkInfo.State.BLOCKED })
    }

    private fun work(name: String): List<WorkInfo> = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
}
