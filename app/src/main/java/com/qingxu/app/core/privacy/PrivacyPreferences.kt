package com.qingxu.app.core.privacy

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

val DEFAULT_LOCAL_ONLY_PACKAGES = setOf(
    "com.tencent.mm", "com.tencent.mobileqq", "com.android.mms",
    "com.google.android.apps.messaging", "com.samsung.android.messaging",
)

data class PrivacySettings(
    val hideContent: Boolean = false,
    val blockCloudWhenLocked: Boolean = true,
    val localOnlyPackages: Set<String> = DEFAULT_LOCAL_ONLY_PACKAGES,
    // An upgrade must never silently delete existing history.
    val retentionDays: Int = 0,
    val revision: Long = 0,
    val deletedBefore: Long = 0,
    val preventScreenshots: Boolean = false,
)

class PrivacyPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("privacy", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(PrivacySettings(
        hideContent = prefs.getBoolean("hide_content", false),
        blockCloudWhenLocked = prefs.getBoolean("block_cloud_locked", true),
        localOnlyPackages = prefs.getStringSet("local_only", DEFAULT_LOCAL_ONLY_PACKAGES).orEmpty().toSet(),
        retentionDays = prefs.getInt("retention_days", 0).takeIf { it in setOf(0, 7, 30) } ?: 0,
        revision = prefs.getLong("revision", 0),
        deletedBefore = prefs.getLong("deleted_before", 0),
        preventScreenshots = prefs.getBoolean("prevent_screenshots", false),
    ))
    val state = mutableState.asStateFlow()

    @Synchronized
    fun save(hideContent: Boolean, blockCloudWhenLocked: Boolean, localOnlyPackages: Set<String>, retentionDays: Int,
        preventScreenshots: Boolean = mutableState.value.preventScreenshots) {
        require(retentionDays in setOf(0, 7, 30)) { "请选择支持的保留期限" }
        val next = mutableState.value.copy(hideContent = hideContent, blockCloudWhenLocked = blockCloudWhenLocked,
            localOnlyPackages = localOnlyPackages.toSet(), retentionDays = retentionDays,
            preventScreenshots = preventScreenshots)
        if (next == mutableState.value) return
        val revision = next.revision + 1
        check(prefs.edit().putBoolean("hide_content", hideContent).putBoolean("block_cloud_locked", blockCloudWhenLocked)
            .putStringSet("local_only", localOnlyPackages.toSet()).putInt("retention_days", retentionDays)
            .putBoolean("prevent_screenshots", preventScreenshots)
            .putLong("revision", revision).commit())
        mutableState.value = next.copy(revision = revision)
    }

    /** Prevent a listener reconnect from importing the same deleted notifications again. */
    @Synchronized
    fun markDeleted(at: Long) {
        val next = mutableState.value.copy(deletedBefore = maxOf(at, mutableState.value.deletedBefore), revision = mutableState.value.revision + 1)
        check(prefs.edit().putLong("deleted_before", next.deletedBefore).putLong("revision", next.revision).commit())
        mutableState.value = next
    }
}

fun deviceIsPrivate(context: Context): Boolean = try {
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    keyguard == null || power == null || keyguard.isDeviceLocked || keyguard.isKeyguardLocked || !power.isInteractive
} catch (_: Exception) { true }
