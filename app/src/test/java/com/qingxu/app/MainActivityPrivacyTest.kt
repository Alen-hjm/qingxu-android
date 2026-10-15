package com.qingxu.app

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.qingxu.app.core.privacy.AesGcmNotificationCipher
import com.qingxu.app.data.local.QingxuDatabase
import com.qingxu.app.feature.QingxuViewModel
import com.qingxu.app.notification.NotificationSurface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

/** Exercises real Activity window/lifecycle wiring. No fingerprint hardware or success is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityPrivacyTest {
    private lateinit var application: Application
    private lateinit var container: AppContainer
    private lateinit var viewModel: QingxuViewModel
    private var controller: ActivityController<MainActivity>? = null

    @Before fun setup() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("privacy", Context.MODE_PRIVATE).edit().clear().commit()
        container = AppContainer(application)
        container.dao.configureCipher(AesGcmNotificationCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        container.ensureStorageReady()
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker =
                object : Worker(context, parameters) {
                    override fun doWork(): Result = Result.success()
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(application, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).setWorkerFactory(workerFactory).build())
        viewModel = QingxuViewModel(application, container)
        val keyguard = application.getSystemService(KeyguardManager::class.java)
        shadowOf(keyguard).setIsDeviceLocked(false)
        shadowOf(keyguard).setKeyguardLocked(false)
        shadowOf(application.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        WorkManagerTestInitHelper.closeWorkDatabase()
        val databaseField = AppContainer::class.java.getDeclaredField("database").apply { isAccessible = true }
        (databaseField.get(container) as QingxuDatabase).close()
    }

    private fun launch(): MainActivity {
        check(controller == null)
        val activityController = Robolectric.buildActivity(MainActivity::class.java)
        controller = activityController
        // MainActivity's normal factory requires QingxuApplication. Seed the same ViewModelStore
        // key with the explicitly injected container before onCreate, retaining the real Activity.
        activityController.get().viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:${QingxuViewModel::class.java.canonicalName}", viewModel)
        activityController.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(viewModel, ViewModelProvider(activityController.get())[QingxuViewModel::class.java])
        return activityController.get()
    }

    private fun secure(activity: MainActivity): Boolean =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun saveProtection(appLock: Boolean, screenshots: Boolean) {
        val current = container.privacy.state.value
        container.privacy.save(appLock, current.blockCloudWhenLocked, current.localOnlyPackages,
            current.retentionDays, screenshots)
    }

    @Test fun newInstallDoesNotForceScreenshotProtectionOrApplicationLock() {
        val activity = launch()
        assertFalse(container.privacy.state.value.hideContent)
        assertFalse(container.privacy.state.value.preventScreenshots)
        assertFalse(secure(activity))
    }

    @Test fun savedScreenshotProtectionAppliesDuringActivityCreation() {
        saveProtection(appLock = false, screenshots = true)
        val activity = launch()
        assertTrue(secure(activity))
        assertFalse(container.privacy.state.value.hideContent)
    }

    @Test fun runningActivityCanEnableAndClearScreenshotProtection() {
        val activity = launch()
        saveProtection(appLock = false, screenshots = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(secure(activity))
        saveProtection(appLock = false, screenshots = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(secure(activity))
    }

    @Test fun enablingApplicationLockDoesNotEnableScreenshotBlocking() {
        val activity = launch()
        saveProtection(appLock = true, screenshots = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(container.privacy.state.value.hideContent)
        assertFalse(secure(activity))
        viewModel.unlockContent()
        assertTrue(viewModel.hasContentAccess())
        viewModel.lockContent()
        assertFalse(viewModel.hasContentAccess())
        assertFalse(secure(activity))
    }

    @Test fun screenOffLocksImmediatelyAndInvalidatesAnOlderAuthenticationCallback() {
        saveProtection(appLock = true, screenshots = false)
        val activity = launch()
        viewModel.unlockContent()
        assertTrue(container.contentSession.isUnlocked())
        val generationField = MainActivity::class.java.getDeclaredField("authenticationGeneration").apply { isAccessible = true }
        val actionField = MainActivity::class.java.getDeclaredField("afterContentUnlock").apply { isAccessible = true }
        val credentialField = MainActivity::class.java.getDeclaredField("credentialGeneration").apply { isAccessible = true }
        val oldGeneration = generationField.getLong(activity)
        var continuationCalls = 0
        actionField.set(activity, { continuationCalls++ })
        credentialField.set(activity, oldGeneration)

        // Keep the keyguard shadow unlocked: the stale callback must fail because its token was
        // revoked by the real broadcast receiver, rather than only because the device is locked.
        application.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(container.contentSession.isUnlocked())
        assertTrue(generationField.getLong(activity) > oldGeneration)
        assertNull(actionField.get(activity))
        assertNull(credentialField.get(activity))

        MainActivity::class.java.getDeclaredMethod("finishAuthentication", java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(activity, oldGeneration)
        assertFalse(container.contentSession.isUnlocked())
        assertEquals(0, continuationCalls)
    }

    @Test fun summaryNotificationReusesTheSingleMainActivityTask() {
        val activity = launch()
        val activityInfo = application.packageManager.getActivityInfo(ComponentName(application, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activityInfo.launchMode)
        val manager = activity.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        // Exercise the actual summary builder without waiting on unrelated Room/analysis work.
        NotificationSurface::class.java.getDeclaredMethod("updateSummary", Integer.TYPE)
            .apply { isAccessible = true }.invoke(container.surface, 1)
        val notification = shadowOf(manager).getNotification(NotificationSurface.SUMMARY_ID)
        assertNotNull(notification)
        val pending = shadowOf(notification.contentIntent)
        assertTrue(pending.isActivity)
        assertTrue(pending.isImmutable)
        val intent = pending.savedIntent
        assertEquals(ComponentName(application, MainActivity::class.java), intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        manager.cancel(NotificationSurface.SUMMARY_ID)
    }

    @Test fun notificationReentryWithinGraceKeepsTheSameSessionAndCancelsTheOldBackgroundDeadline() {
        saveProtection(appLock = true, screenshots = false)
        val activity = launch()
        viewModel.unlockContent()
        val running = checkNotNull(controller)
        running.pause().stop()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        assertTrue(container.contentSession.isUnlocked())

        // Robolectric does not emulate Android's task manager. The preceding test verifies the
        // singleTask/notification contract; this delivers its reuse lifecycle to the existing instance.
        running.newIntent(Intent(application, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            .restart().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(activity, running.get())
        assertEquals(viewModel, ViewModelProvider(activity)[QingxuViewModel::class.java])
        assertTrue(viewModel.hasContentAccess())

        // Pass the original background deadline and another full grace period while foregrounded.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(container.contentSession.isUnlocked())
        assertTrue(viewModel.hasContentAccess())
    }

    @Test @Config(sdk = [26])
    fun android26ActivityAndAppCompatDialogThemeInitializeWithoutHardwareAuthentication() {
        val activity = launch()
        assertFalse(secure(activity))
        val styled = activity.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.windowActionBar))
        try { assertTrue("Legacy fingerprint dialog needs an AppCompat theme", styled.hasValue(0)) }
        finally { styled.recycle() }
        val dialog = AlertDialog.Builder(activity).setTitle("应用锁").setMessage("主题兼容性检查")
            .setNegativeButton("取消", null).create()
        try {
            dialog.show()
            assertNotNull(dialog.window)
            assertTrue(dialog.isShowing)
        } finally { dialog.dismiss() }
    }
}
