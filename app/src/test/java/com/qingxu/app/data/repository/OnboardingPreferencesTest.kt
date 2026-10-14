package com.qingxu.app.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qingxu.app.data.local.UserLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OnboardingPreferencesTest {
    private lateinit var context: Context
    private lateinit var guide: OnboardingPreferences

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("qingxu_onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        guide = OnboardingPreferences(context)
    }

    @Test fun interruptedCorrectionResumesAndRestoresAutomaticPriority() {
        guide.classify(UserLabel.FILTERED)
        val reopened = OnboardingPreferences(context)
        assertEquals(3, reopened.state.value.step(true, true))
        reopened.restore()
        assertEquals(UserLabel.IMPORTANT_URGENT, reopened.state.value.label)
        assertEquals(4, reopened.state.value.step(true, true))
        assertFalse(reopened.state.value.completed)
        reopened.complete()
        assertTrue(OnboardingPreferences(context).state.value.completed)
    }

    @Test fun optionalActionsDoNotSkipCorrectionAndRepeatedTapDoesNotAdvance() {
        guide.star()
        guide.restore()
        assertEquals(0, guide.state.value.practice)
        assertTrue(guide.state.value.interested)
        guide.classify(UserLabel.IMPORTANT)
        assertEquals(0, guide.state.value.practice)
        guide.classify(UserLabel.FILTERED)
        guide.classify(UserLabel.FILTERED)
        assertEquals(1, guide.state.value.practice)
        guide.restore()
        guide.restore()
        assertEquals(2, guide.state.value.practice)
        assertEquals(UserLabel.IMPORTANT, guide.state.value.label)
    }

    @Test fun revokedPermissionAndRemovedSourceReturnToSetupWithoutLosingPractice() {
        guide.classify(UserLabel.FILTERED)
        assertEquals(1, guide.state.value.step(true, false))
        assertEquals(0, guide.state.value.step(false, true))
        assertEquals(3, guide.state.value.step(true, true))
    }

    @Test fun previousGuideProgressRestartsPracticeButRespectsCompletedChoice() {
        context.getSharedPreferences("qingxu_onboarding", Context.MODE_PRIVATE).edit()
            .putInt("practice", 3).putBoolean("completed", true).commit()
        val migrated = OnboardingPreferences(context)
        assertEquals(0, migrated.state.value.practice)
        assertTrue(migrated.state.value.completed)
    }

    @Test fun skipPersistsAndReplayPreservesSelectedApps() {
        val sources = SourcePreferences(context)
        sources.setSelected("example.app", true)
        guide.complete()
        guide.star()
        assertTrue(OnboardingPreferences(context).state.value.completed)
        guide.restart()
        assertEquals(OnboardingState(), OnboardingPreferences(context).state.value)
        assertTrue(SourcePreferences(context).accepts("example.app"))
    }
}
