package com.qingxu.app.core.privacy

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qingxu.app.core.ai.AiSettings
import com.qingxu.app.data.local.NotificationEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PrivacyTest {
    private val entry = NotificationEntry("secret-key", "com.tencent.mm", "微信", "小王", "明天开会", null, 1L, 2L, "hash")
    private val ai = AiSettings(enabled = true, allowedPackages = setOf("com.tencent.mm"))

    @Test fun localOnlyWinsOverCloudAuthorizationAndProviderChoice() {
        assertEquals(CloudPermission.LOCAL_ONLY, CloudPrivacyGate.permission(entry, ai, PrivacySettings(), false))
        assertEquals(CloudPermission.ALLOWED, CloudPrivacyGate.permission(entry, ai, PrivacySettings(localOnlyPackages = emptySet()), false))
        assertEquals(CloudPermission.LOCKED, CloudPrivacyGate.permission(entry, ai, PrivacySettings(localOnlyPackages = emptySet()), true))
    }
    @Test fun sensitiveContentCannotBeAuthorizedByCloudSourceToggle() {
        assertEquals(CloudPermission.SENSITIVE, CloudPrivacyGate.permission(entry.copy(body = "验证码 123456"), ai,
            PrivacySettings(localOnlyPackages = emptySet()), false))
    }
    @Test fun payloadRedactionRemovesCommonIdentifiersButKeepsMeaning() {
        val prepared = CloudPrivacyGate.prepare(entry.copy(body = "联系13800138000或person@example.com，退款失败；https://example.com/private?q=secret"))
        assertFalse(prepared.body.contains("13800138000"))
        assertFalse(prepared.body.contains("person@example.com"))
        assertFalse(prepared.body.contains("q=secret"))
        assertTrue(prepared.body.contains("退款失败"))
        assertEquals("", prepared.packageName)
        assertEquals("cloud-input", prepared.notificationKey)
    }
    @Test fun preferencesDefaultToDirectViewingAndScreenshotsWithCloudPrivacyPreserved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("privacy", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = PrivacyPreferences(context)
        assertFalse(preferences.state.value.hideContent)
        assertFalse(preferences.state.value.preventScreenshots)
        assertTrue(preferences.state.value.blockCloudWhenLocked)
        assertTrue("com.tencent.mm" in preferences.state.value.localOnlyPackages)
        assertEquals(0, preferences.state.value.retentionDays)
        preferences.save(true, true, setOf("shop"), 7)
        preferences.markDeleted(100L)
        preferences.markDeleted(50L)
        val reopened = PrivacyPreferences(context).state.value
        assertEquals(setOf("shop"), reopened.localOnlyPackages)
        assertEquals(7, reopened.retentionDays)
        assertEquals(100L, reopened.deletedBefore)
        assertTrue(reopened.hideContent)
    }

    @Test fun storedLockChoiceSurvivesUpgradeAndScreenshotPreferenceIsIndependent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stored = context.getSharedPreferences("privacy", Context.MODE_PRIVATE)
        listOf(true, false).forEach { enabled ->
            stored.edit().clear().putBoolean("hide_content", enabled).commit()
            val preferences = PrivacyPreferences(context)
            assertEquals(enabled, preferences.state.value.hideContent)
            assertFalse(preferences.state.value.preventScreenshots)
            preferences.save(enabled, true, setOf("shop"), 7, preventScreenshots = true)
            assertTrue(PrivacyPreferences(context).state.value.preventScreenshots)
            assertEquals(enabled, PrivacyPreferences(context).state.value.hideContent)
            // Existing call sites that omit the new option must preserve the user's choice.
            preferences.save(!enabled, true, setOf("shop"), 30)
            assertTrue(preferences.state.value.preventScreenshots)
            preferences.save(!enabled, true, setOf("shop"), 30, preventScreenshots = false)
            assertFalse(PrivacyPreferences(context).state.value.preventScreenshots)
        }
    }

    @Test fun foregroundViewingDoesNotExpireAndNewInstancesStillStartLocked() {
        var time = 1L
        val session = ContentSession { time }
        assertFalse(session.isUnlocked())
        session.unlock()
        assertTrue(session.isUnlocked())
        time += 24 * 60 * 60_000L
        assertTrue(session.isUnlocked())
        assertTrue(session.unlocked.value)
        session.unlock(); session.lock()
        assertFalse(session.isUnlocked())
        assertFalse(ContentSession { time }.isUnlocked())
    }

    @Test fun shortBackgroundSwitchReturnsToAnUnexpiredForegroundSession() {
        var time = 10L
        val session = ContentSession { time }
        session.unlock()
        session.enterBackground()
        time += 59_999L
        assertTrue(session.isUnlocked())
        session.enterForeground()
        time += 24 * 60 * 60_000L
        assertTrue(session.isUnlocked())
        // Each real departure starts a new grace period, regardless of time spent in foreground.
        session.enterBackground()
        time += 59_999L
        assertTrue(session.isUnlocked())
        time++
        assertFalse(session.isUnlocked())
        assertFalse(session.unlocked.value)
    }

    @Test fun backgroundChecksAndDuplicateCallbacksNeverRefreshTheDeadline() {
        var time = 0L
        val session = ContentSession { time }
        session.unlock(); session.enterBackground()
        repeat(5) {
            time += 10_000L
            assertTrue(session.isUnlocked())
            session.enterBackground()
        }
        time = 60_000L
        assertFalse(session.isUnlocked())
        session.enterForeground()
        assertFalse(session.isUnlocked())
    }

    @Test fun foregroundReturnChecksExpiryEvenWhenNoBackgroundTimerRan() {
        var time = 0L
        val session = ContentSession { time }
        session.unlock(); session.enterBackground()
        time = 60_000L
        session.enterForeground()
        assertFalse(session.isUnlocked())
        assertFalse(session.unlocked.value)
    }

    @Test fun explicitUnlockWhileBackgroundStillHasABoundedGracePeriod() {
        var time = 0L
        val session = ContentSession { time }
        session.enterBackground()
        time = 120_000L
        session.unlock()
        time += 59_999L
        assertTrue(session.isUnlocked())
        time++
        assertFalse(session.isUnlocked())
    }

    @Test fun explicitScreenLockRevokesImmediatelyInForegroundAndDuringGrace() {
        var time = 0L
        val session = ContentSession { time }
        session.unlock(); session.lock()
        assertFalse(session.isUnlocked())
        session.unlock(); session.enterBackground()
        time = 1L
        session.lock(); session.enterForeground()
        assertFalse(session.isUnlocked())
        assertFalse(session.unlocked.value)
    }
}
