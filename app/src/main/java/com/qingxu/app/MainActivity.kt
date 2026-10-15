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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.qingxu.app.core.ui.QingxuTheme
import com.qingxu.app.core.ui.BrandLaunch
import com.qingxu.app.feature.QingxuViewModel
import com.qingxu.app.navigation.QingxuApp
import com.qingxu.app.notification.NotificationAccess
import com.qingxu.app.core.privacy.deviceIsPrivate

class MainActivity : ComponentActivity() {
    private val viewModel: QingxuViewModel by viewModels()
    private var afterNotificationPermission: (() -> Unit)? = null
    private var afterContentUnlock: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterNotificationPermission
        afterNotificationPermission = null
        if (granted) action?.invoke()
        else Toast.makeText(this, "未开启轻序通知，仍可在应用内查看消息", Toast.LENGTH_LONG).show()
    }
    private val authenticate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = afterContentUnlock
        afterContentUnlock = null
        if (result.resultCode == Activity.RESULT_OK && !deviceIsPrivate(this)) {
            viewModel.unlockContent()
            action?.invoke()
        } else viewModel.lockContent()
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) viewModel.lockContent()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Covers recent-task thumbnails and screenshots, including before the first Compose frame.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        enableEdgeToEdge()
        lifecycleScope.launch { while (true) { delay(1_000); viewModel.expireContentSession() } }
        setContent {
            QingxuTheme {
              BrandLaunch {
                QingxuApp(viewModel, onUnlock = { requestContentUnlock() }, onPermissionSettings = {
                    try {
                        startActivity(NotificationAccess.settingsIntent())
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, "请在系统设置中搜索“通知使用权”并开启轻序", Toast.LENGTH_LONG).show()
                    }
                }, onNotificationPermission = { requiresUnlock, action -> requestNotificationPermission(requiresUnlock, action) })
              }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (deviceIsPrivate(this)) viewModel.lockContent()
        viewModel.refresh()
    }
    override fun onPause() { viewModel.lockContent(); super.onPause() }
    override fun onDestroy() { unregisterReceiver(screenReceiver); super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun requestContentUnlock(afterUnlock: (() -> Unit)? = null) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val intent = if (keyguard?.isDeviceSecure == true) keyguard.createConfirmDeviceCredentialIntent("查看轻序内容", "验证设备凭据后显示通知正文与私密设置") else null
        if (intent == null) {
            afterContentUnlock = null
            Toast.makeText(this, "请先在系统设置中设置锁屏密码，再解锁查看", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: ActivityNotFoundException) { }
            return
        }
        afterContentUnlock = afterUnlock
        try { authenticate.launch(intent) } catch (_: ActivityNotFoundException) {
            afterContentUnlock = null
            Toast.makeText(this, "无法打开系统身份验证，请检查锁屏设置", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission(requiresUnlock: Boolean, action: () -> Unit) {
        val continueAction = {
            if (requiresUnlock && viewModel.privacySettings.value.hideContent && !viewModel.contentUnlocked.value)
                requestContentUnlock(action)
            else action()
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterNotificationPermission = continueAction
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else continueAction()
    }
}
