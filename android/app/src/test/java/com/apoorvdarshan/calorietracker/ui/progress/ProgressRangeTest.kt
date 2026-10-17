package com.apoorvdarshan.calorietracker.ui.progress

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ProgressRangeTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-17")

    @Test
    fun dateRangeStepsBackWholeContiguousWindows() {
        assertEquals(
            LocalDate.parse("2026-10-11") to today,
            TimeRange.WEEK.dateRange(today, offset = 0)
        )
        assertEquals(
            LocalDate.parse("2026-10-04") to LocalDate.parse("2026-10-10"),
            TimeRange.WEEK.dateRange(today, offset = 1)
        )
        assertEquals(
            LocalDate.parse("2026-09-27") to LocalDate.parse("2026-10-03"),
            TimeRange.WEEK.dateRange(today, offset = 2)
        )
        assertEquals(
            LocalDate.parse("2026-04-21") to LocalDate.parse("2026-07-19"),
            TimeRange.THREE_MONTHS.dateRange(today, offset = 1)
        )
    }

    @Test
    fun everyRangeCoversItsDayCountAtAnyOffset() {
        TimeRange.entries.forEach { range ->
            (0..2).forEach { offset ->
                val (start, end) = range.dateRange(today, offset)
                assertEquals(range.days.toLong() - 1, end.toEpochDay() - start.toEpochDay())
            }
        }
    }

    @Test
    fun periodNavigationIsOnlyUnavailableForAllTime() {
        assertEquals(
            listOf(TimeRange.ALL_TIME),
            TimeRange.entries.filterNot { it.supportsPeriodNavigation }
        )
    }

    @Test
    fun foodStatsOnlyCountDaysInsideThePastWindow() {
        val foods = listOf(
            food(calories = 2_000, protein = 100.0, at = "2026-10-04T09:00"),
            food(calories = 1_000, protein = 50.0, at = "2026-10-10T21:00"),
            // Outside the window on either side.
            food(calories = 3_000, protein = 300.0, at = "2026-10-03T12:00"),
            food(calories = 3_000, protein = 300.0, at = "2026-10-11T01:00")
        )

        val stats = computeProgressFoodRangeStats(
            foods = foods,
            dayCount = 7,
            endDay = LocalDate.parse("2026-10-10"),
            zone = zone,
            profile = null,
            optionalGoals = OptionalNutrientGoals.Default
        )

        assertEquals(
            listOf(LocalDate.parse("2026-10-04") to 2_000, LocalDate.parse("2026-10-10") to 1_000),
            stats.dailyCalories
        )
        assertEquals(75.0, stats.avgProtein, 0.0001)
    }

    @Test
    fun calorieBarHitTestPicksTheNearestBarAndClampsToTheEnds() {
        // Bars 20px wide with 4px gaps starting at x = 10: centres at 20, 44, 68.
        assertEquals(0, calorieBarIndexAt(x = 0f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertEquals(0, calorieBarIndexAt(x = 31f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertEquals(1, calorieBarIndexAt(x = 33f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertEquals(2, calorieBarIndexAt(x = 68f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertEquals(2, calorieBarIndexAt(x = 500f, startX = 10f, barWidth = 20f, gap = 4f, count = 3))
        assertNull(calorieBarIndexAt(x = 20f, startX = 10f, barWidth = 20f, gap = 4f, count = 0))
    }

    private fun food(calories: Int, protein: Double, at: String) = FoodEntry(
        name = "Meal",
        calories = calories,
        protein = protein,
        carbs = 0.0,
        fat = 0.0,
        timestamp = LocalDateTime.parse(at).toInstant(zone),
        source = FoodSource.MANUAL
    )
}
