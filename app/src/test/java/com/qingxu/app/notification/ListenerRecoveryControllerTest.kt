package com.qingxu.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerRecoveryControllerTest {
    @Test fun recoveryNeverRequestsAccessOrClaimsConnectionWithoutPermission() {
        val fixture = Fixture().apply { permission = false }
        fixture.controller.request(force = true)
        fixture.advanceBy(120_000)
        assertEquals(0, fixture.rebinds)
        assertFalse(fixture.recovering)
        assertFalse(fixture.connected)
    }

    @Test fun duplicateTriggersShareOneFiniteRecoveryWindow() {
        val fixture = Fixture()
        repeat(5) { fixture.controller.request() }
        assertTrue(fixture.recovering)
        fixture.advanceBy(0)
        assertEquals(1, fixture.rebinds)
        assertFalse(fixture.connected)
        fixture.advanceBy(10_000)
        assertEquals(2, fixture.rebinds)
        fixture.controller.request(force = true)
        fixture.advanceBy(30_000)
        assertEquals(3, fixture.rebinds)
        assertTrue(fixture.recovering)
        fixture.advanceBy(10_000)
        assertFalse(fixture.recovering)
        fixture.advanceBy(600_000)
        assertEquals(3, fixture.rebinds)
    }

    @Test fun genuineConnectionCancelsPendingRetries() {
        val fixture = Fixture()
        fixture.controller.request()
        fixture.advanceBy(0)
        fixture.connected = true
        fixture.controller.onConnected()
        assertFalse(fixture.recovering)
        fixture.advanceBy(120_000)
        assertEquals(1, fixture.rebinds)
    }

    @Test fun permissionRevokedDuringRecoveryStopsFurtherRequests() {
        val fixture = Fixture()
        fixture.controller.request()
        fixture.advanceBy(0)
        fixture.permission = false
        fixture.advanceBy(10_000)
        assertEquals(1, fixture.rebinds)
        assertFalse(fixture.recovering)
        fixture.advanceBy(120_000)
        assertEquals(1, fixture.rebinds)
    }

    @Test fun rapidDisconnectAndManualRetryStillRespectMinimumRebindInterval() {
        val fixture = Fixture()
        fixture.controller.request()
        fixture.advanceBy(0)
        fixture.connected = true
        fixture.controller.onConnected()
        fixture.connected = false
        repeat(5) { fixture.controller.request(force = true) }
        fixture.advanceBy(9_999)
        assertEquals(1, fixture.rebinds)
        fixture.advanceBy(1)
        assertEquals(2, fixture.rebinds)
    }

    @Test fun automaticTriggersRespectCooldownAndManualRetryCanStartANewWindow() {
        val fixture = Fixture()
        fixture.controller.request()
        fixture.advanceBy(50_000)
        assertEquals(3, fixture.rebinds)
        assertFalse(fixture.recovering)
        fixture.controller.request()
        fixture.advanceBy(0)
        assertEquals(3, fixture.rebinds)
        fixture.controller.request(force = true)
        fixture.advanceBy(0)
        assertEquals(4, fixture.rebinds)
        assertTrue(fixture.recovering)
    }

    @Test fun reconnectCompletedInsideRebindDoesNotLeaveARecoveryScheduled() {
        val fixture = Fixture()
        fixture.onRebind = {
            fixture.connected = true
            fixture.controller.onConnected()
        }
        fixture.controller.request()
        fixture.advanceBy(120_000)
        assertEquals(1, fixture.rebinds)
        assertFalse(fixture.recovering)
    }

    @Test fun disconnectTriggeredInsideRebindCannotOpenASecondRecoveryWindow() {
        val fixture = Fixture()
        fixture.onRebind = { fixture.controller.request() }
        fixture.controller.request()
        fixture.advanceBy(120_000)
        assertEquals(3, fixture.rebinds)
        assertFalse(fixture.recovering)
    }

    private class Fixture {
        private var now = 0L
        var permission = true
        var connected = false
        var recovering = false
        var rebinds = 0
        var onRebind: () -> Unit = {}
        private data class Task(val at: Long, val callback: () -> Unit, var canceled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        val controller = ListenerRecoveryController(
            now = { now },
            hasPermission = { permission },
            isConnected = { connected },
            requestRebind = { rebinds += 1; onRebind() },
            schedule = { delay, callback ->
                val task = Task(now + delay, callback)
                tasks += task
                val cancel: () -> Unit = { task.canceled = true }
                cancel
            },
            onRecoveringChanged = { recovering = it },
        )

        fun advanceBy(duration: Long) {
            val target = now + duration
            while (true) {
                val task = tasks.filterNot { it.canceled }.filter { it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                now = task.at
                task.callback()
            }
            now = target
        }
    }
}
