package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.models.WorkoutRestTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The background alert for a rest that ends while Fud AI is not in front of the user. */
interface WorkoutRestAlarms {
    fun schedule(timer: WorkoutRestTimer)
    fun cancel()
}

/**
 * Owns the one rest countdown that runs after a logged strength set (iOS
 * `WorkoutRestTimerController`). It is app-scoped, so switching tabs or days keeps it.
 *
 * While the app is in the foreground the end is announced through [completions] (the UI turns
 * that into a haptic); a background alarm posts a notification for a rest that ends while the
 * app is not visible, and is cancelled when the rest is skipped or ends in the foreground.
 */
class WorkoutRestTimerController(
    private val scope: CoroutineScope,
    private val alarms: WorkoutRestAlarms,
    private val clock: () -> Instant = Instant::now
) {
    private val _timer = MutableStateFlow<WorkoutRestTimer?>(null)
    val timer: StateFlow<WorkoutRestTimer?> = _timer.asStateFlow()

    private val _completions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val completions: SharedFlow<Unit> = _completions.asSharedFlow()

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    private var completionJob: Job? = null

    fun setAppInForeground(foreground: Boolean) {
        isAppInForeground = foreground
    }

    fun start(exerciseId: UUID, setId: UUID, exerciseName: String, durationSeconds: Int) {
        val next = WorkoutRestTimer.start(exerciseId, setId, exerciseName, durationSeconds, clock()) ?: return
        replace(next)
    }

    /** +15 s. */
    fun extend() {
        val current = _timer.value ?: return
        replace(current.extended(at = clock()))
    }

    fun skip() {
        completionJob?.cancel()
        completionJob = null
        _timer.value = null
        alarms.cancel()
    }

    private fun replace(next: WorkoutRestTimer) {
        _timer.value = next
        alarms.schedule(next)
        completionJob?.cancel()
        completionJob = scope.launch {
            val waitMillis = Duration.between(clock(), next.endsAt).toMillis()
            if (waitMillis > 0) delay(waitMillis)
            finish(next)
        }
    }

    /** Runs when the countdown reaches zero, or as soon as a frozen process resumes after it did. */
    private fun finish(expected: WorkoutRestTimer) {
        if (_timer.value != expected) return
        val now = clock()
        if (!expected.isFinished(now)) return
        if (isAppInForeground) {
            // Ended in front of the user: the haptic is the alert, so no notification follows.
            alarms.cancel()
            if (expected.shouldAlertInApp(now)) _completions.tryEmit(Unit)
        }
        _timer.value = null
        completionJob = null
    }
}
