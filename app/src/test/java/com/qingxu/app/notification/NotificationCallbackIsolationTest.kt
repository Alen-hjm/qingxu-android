package com.qingxu.app.notification

import android.app.Application
import android.os.BadParcelableException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationCallbackIsolationTest {
    @Test fun malformedPayloadDoesNotPreventLaterNotificationsBeingCaptured() {
        val captured = mutableListOf<Int>()
        listOf(1, 2, 3).forEach { notification ->
            handleNotificationSafely {
                if (notification == 2) throw BadParcelableException("unreadable extras")
                captured += notification
            }
        }
        assertEquals(listOf(1, 3), captured)
    }

    @Test fun securityFailureInOneNotificationDoesNotInterruptOtherCallbacks() {
        val captured = mutableListOf<Int>()
        handleNotificationSafely { throw SecurityException("notification content unavailable") }
        handleNotificationSafely { captured += 2 }
        assertEquals(listOf(2), captured)
    }
}
