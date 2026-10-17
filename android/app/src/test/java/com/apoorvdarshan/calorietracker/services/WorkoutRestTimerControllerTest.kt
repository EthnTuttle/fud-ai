package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.models.WorkoutRestTimer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutRestTimerControllerTest {
    private val base = Instant.parse("2026-10-18T10:00:00Z")
    private val exerciseId = UUID.randomUUID()
    private val setId = UUID.randomUUID()

    private class FakeAlarms : WorkoutRestAlarms {
        val scheduled = mutableListOf<Instant>()
        var cancels = 0
        override fun schedule(timer: WorkoutRestTimer) {
            scheduled += timer.endsAt
        }

        override fun cancel() {
            cancels += 1
        }
    }

    private fun TestScope.controller(alarms: FakeAlarms) = WorkoutRestTimerController(
        scope = backgroundScope,
        alarms = alarms,
        clock = { base.plusMillis(testScheduler.currentTime) }
    )

    @Test
    fun startingArmsTheBackgroundAlertAndSkippingCancelsIt() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)

        rest.start(exerciseId, setId, "Bench Press", 90)
        assertEquals(base.plusSeconds(90), rest.timer.value?.endsAt)
        assertEquals(listOf(base.plusSeconds(90)), alarms.scheduled)

        rest.skip()
        assertNull(rest.timer.value)
        assertEquals(1, alarms.cancels)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, alarms.scheduled.size)
    }

    @Test
    fun anOffRestTimerStartsNothing() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        rest.start(exerciseId, setId, "Bench Press", 0)
        assertNull(rest.timer.value)
        assertTrue(alarms.scheduled.isEmpty())
    }

    @Test
    fun addingTimeReschedulesTheAlertAndDelaysTheEnd() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        rest.setAppInForeground(true)
        rest.start(exerciseId, setId, "Bench Press", 30)

        advanceTimeBy(20_000)
        rest.extend()
        assertEquals(listOf(base.plusSeconds(30), base.plusSeconds(45)), alarms.scheduled)

        advanceTimeBy(15_000)
        runCurrent()
        // The original end has passed, but the extended rest is still running.
        assertEquals(base.plusSeconds(45), rest.timer.value?.endsAt)
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(rest.timer.value)
    }

    @Test
    fun aRestEndingInTheForegroundAlertsInAppInsteadOfByNotification() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        val completions = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rest.completions.toList(completions) }
        rest.setAppInForeground(true)

        rest.start(exerciseId, setId, "Bench Press", 60)
        advanceTimeBy(60_001)
        runCurrent()

        assertNull(rest.timer.value)
        assertEquals(1, completions.size)
        assertEquals(1, alarms.cancels)
    }

    @Test
    fun aRestEndingInTheBackgroundLeavesTheNotificationToTheAlarm() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        val completions = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rest.completions.toList(completions) }
        rest.setAppInForeground(false)

        rest.start(exerciseId, setId, "Bench Press", 60)
        advanceTimeBy(60_001)
        runCurrent()

        assertNull(rest.timer.value)
        assertTrue(completions.isEmpty())
        assertEquals(0, alarms.cancels)
    }

    @Test
    fun restartingForAnotherSetReplacesTheCountdown() = runTest {
        val alarms = FakeAlarms()
        val rest = controller(alarms)
        val nextSet = UUID.randomUUID()
        rest.start(exerciseId, setId, "Bench Press", 90)
        advanceTimeBy(40_000)
        rest.start(exerciseId, nextSet, "Bench Press", 90)

        assertEquals(nextSet, rest.timer.value?.setId)
        assertEquals(base.plusSeconds(130), rest.timer.value?.endsAt)
        advanceTimeBy(60_000)
        runCurrent()
        // The first set's end no longer clears the second set's rest.
        assertEquals(nextSet, rest.timer.value?.setId)
    }
}
