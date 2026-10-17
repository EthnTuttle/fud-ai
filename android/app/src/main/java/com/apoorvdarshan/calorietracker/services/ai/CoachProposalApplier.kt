package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.data.ExerciseItem
import com.apoorvdarshan.calorietracker.data.WorkoutRepository
import com.apoorvdarshan.calorietracker.models.CoachExerciseProposal
import com.apoorvdarshan.calorietracker.models.ExerciseTimer
import com.apoorvdarshan.calorietracker.models.PlannedExercise
import com.apoorvdarshan.calorietracker.models.PlannedSet
import com.apoorvdarshan.calorietracker.models.UserExerciseDraft
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import java.util.UUID

/**
 * Performs a confirmed Coach proposal through the workout repository. Both writes use ids fixed
 * when the proposal was made, so repeating an apply leaves the diary and library unchanged.
 * Mirrors iOS CoachExerciseProposalApplier.
 */
object CoachProposalApplier {
    enum class Outcome { APPLIED, EXERCISE_MISSING, SAVE_FAILED }

    /** [library] is the merged catalog and custom exercises an add-to-day proposal is looked up in. */
    suspend fun apply(
        proposal: CoachExerciseProposal,
        workouts: WorkoutRepository,
        imageStore: FoodImageStore,
        library: List<ExerciseItem>
    ): Outcome = when (proposal.kind) {
        CoachExerciseProposal.Kind.CUSTOM_EXERCISE -> {
            if (workouts.snapshot().userExercises.any { it.itemId == proposal.itemId }) {
                Outcome.APPLIED
            } else {
                val draft = UserExerciseDraft(
                    name = proposal.name,
                    instructions = proposal.instructions.joinToString("\n"),
                    category = proposal.category ?: "Strength",
                    equipment = proposal.equipment ?: "Unspecified",
                    primaryMuscles = proposal.primaryMuscles,
                    secondaryMuscles = proposal.secondaryMuscles
                )
                if (workouts.saveUserExercise(draft, imageStore, proposal.itemId) != null) Outcome.APPLIED else Outcome.SAVE_FAILED
            }
        }
        CoachExerciseProposal.Kind.ADD_TO_DAY -> {
            val item = library.firstOrNull { it.id == proposal.itemId }
            val dateKey = proposal.dateKey
            val entryId = proposal.entryId
            when {
                item == null -> Outcome.EXERCISE_MISSING
                dateKey == null || entryId == null -> Outcome.SAVE_FAILED
                else -> {
                    workouts.addPlannedExercise(plannedExercise(proposal, item, entryId), dateKey)
                    Outcome.APPLIED
                }
            }
        }
    }

    /**
     * The diary entry an add-to-day proposal creates: its sets (each with the proposed reps) and,
     * for timed work, a saved timer like a quick cardio log.
     */
    fun plannedExercise(proposal: CoachExerciseProposal, item: ExerciseItem, entryId: UUID): PlannedExercise {
        val reps = proposal.reps?.toString().orEmpty()
        val sets = when {
            proposal.sets != null -> List(proposal.sets) { PlannedSet(reps = reps) }
            proposal.reps != null -> listOf(PlannedSet(reps = reps))
            proposal.durationMinutes != null -> emptyList()
            else -> listOf(PlannedSet())
        }
        val timer = proposal.durationMinutes?.let { minutes ->
            val seconds = minutes * 60.0
            ExerciseTimer(accumulatedSeconds = seconds, savedDurationSeconds = seconds)
        }
        return PlannedExercise.from(item).copy(id = entryId, sets = sets, timer = timer)
    }
}
