package com.qingxu.app

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import android.app.KeyguardManager
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.qingxu.app.core.ui.QingxuTheme
import com.qingxu.app.core.ui.BrandLaunch
import com.qingxu.app.feature.QingxuViewModel
import com.qingxu.app.navigation.QingxuApp
import com.qingxu.app.notification.NotificationAccess
import com.qingxu.app.core.privacy.deviceIsPrivate

class MainActivity : FragmentActivity() {
    private val viewModel: QingxuViewModel by viewModels()
    private var afterNotificationPermission: (() -> Unit)? = null
    private var afterContentUnlock: (() -> Unit)? = null
    private var biometricPrompt: BiometricPrompt? = null
    private var authenticationGeneration = 0L
    private var credentialGeneration: Long? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterNotificationPermission
        afterNotificationPermission = null
        if (granted) action?.invoke()
        else Toast.makeText(this, "未开启轻序通知，仍可在应用内查看消息", Toast.LENGTH_LONG).show()
    }
    private val authenticate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val generation = credentialGeneration
        credentialGeneration = null
        if (generation != null && generation == authenticationGeneration) {
            if (result.resultCode == Activity.RESULT_OK) finishAuthentication(generation)
            else cancelAuthentication()
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                cancelAuthentication()
                viewModel.lockContent()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowPrivacy()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        enableEdgeToEdge()
        lifecycleScope.launch { viewModel.privacySettings.collect { applyWindowPrivacy() } }
        setContent {
            QingxuTheme {
              BrandLaunch {
                QingxuApp(viewModel, onUnlock = { requestContentUnlock() }, onPermissionSettings = {
                    try {
                        startActivity(NotificationAccess.settingsIntent())
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, "请在系统设置中搜索“通知使用权”并开启轻序", Toast.LENGTH_LONG).show()
                    }
                }, onNotificationPermission = { requiresUnlock, action -> requestNotificationPermission(requiresUnlock, action) },
                    onAuthenticate = { action -> requestContentUnlock(action) })
              }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        if (deviceIsPrivate(this)) viewModel.lockContent()
        viewModel.contentForeground()
    }
    override fun onResume() {
        super.onResume()
        if (deviceIsPrivate(this)) viewModel.lockContent()
        viewModel.refresh()
    }
    override fun onStop() {
        if (!isChangingConfigurations) viewModel.contentBackground()
        super.onStop()
    }
    override fun onDestroy() {
        cancelAuthentication()
        if (!isChangingConfigurations) viewModel.lockContent()
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    private fun applyWindowPrivacy() {
        val settings = viewModel.privacySettings.value
        if (settings.preventScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!settings.hideContent && !settings.preventScreenshots)
    }

    private fun cancelAuthentication() {
        authenticationGeneration++
        credentialGeneration = null
        afterContentUnlock = null
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
    }

    private fun finishAuthentication(generation: Long) {
        if (generation != authenticationGeneration) return
        if (deviceIsPrivate(this)) { cancelAuthentication(); viewModel.lockContent(); return }
        val action = afterContentUnlock
        afterContentUnlock = null
        credentialGeneration = null
        biometricPrompt = null
        authenticationGeneration++
        viewModel.unlockContent()
        action?.invoke()
    }

    private fun requestContentUnlock(afterUnlock: (() -> Unit)? = null) {
        cancelAuthentication()
        val generation = authenticationGeneration
        afterContentUnlock = afterUnlock
        val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (BiometricManager.from(this).canAuthenticate(strong) != BiometricManager.BIOMETRIC_SUCCESS) {
            requestDeviceCredential(generation)
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finishAuthentication(generation)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (generation != authenticationGeneration) return
                if (errorCode in setOf(BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT)) requestDeviceCredential(generation)
                else {
                    cancelAuthentication()
                    if (errorCode !in setOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED))
                        Toast.makeText(this@MainActivity, "验证未完成，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        })
        biometricPrompt = prompt
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("解锁轻序")
            .setSubtitle("使用系统生物识别或锁屏密码验证")
        if (Build.VERSION.SDK_INT >= 30) info.setAllowedAuthenticators(strong or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else info.setAllowedAuthenticators(strong).setNegativeButtonText("使用锁屏密码")
        try { prompt.authenticate(info.build()) }
        catch (_: RuntimeException) { requestDeviceCredential(generation) }
    }

    @Suppress("DEPRECATION")
    private fun requestDeviceCredential(generation: Long) {
        if (generation != authenticationGeneration) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        val intent = if (keyguard?.isDeviceSecure == true) keyguard.createConfirmDeviceCredentialIntent("解锁轻序", "使用手机锁屏密码验证") else null
        if (intent == null) {
            cancelAuthentication()
            Toast.makeText(this, "开启应用锁需要先设置手机锁屏密码", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: ActivityNotFoundException) { }
            return
        }
        credentialGeneration = generation
        try { authenticate.launch(intent) } catch (_: ActivityNotFoundException) {
            cancelAuthentication()
            Toast.makeText(this, "无法打开系统身份验证，请检查锁屏设置", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission(requiresUnlock: Boolean, action: () -> Unit) {
        val continueAction = {
            if (requiresUnlock && !viewModel.hasContentAccess())
                requestContentUnlock(action)
            else action()
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterNotificationPermission = continueAction
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else continueAction()
    }
}
