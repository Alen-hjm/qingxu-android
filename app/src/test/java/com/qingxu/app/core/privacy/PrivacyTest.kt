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
    @Test fun preferencesDefaultToPrivateAndPersistExplicitChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("privacy", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = PrivacyPreferences(context)
        assertTrue(preferences.state.value.hideContent)
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
    }
    @Test fun viewingSessionExpiresAndDoesNotSurviveNewInstance() {
        var time = 1L
        val session = ContentSession { time }
        assertFalse(session.isUnlocked())
        session.unlock()
        assertTrue(session.isUnlocked())
        time += 120_000
        assertFalse(session.isUnlocked())
        session.unlock(); session.lock()
        assertFalse(session.isUnlocked())
        assertFalse(ContentSession { time }.isUnlocked())
    }
}
