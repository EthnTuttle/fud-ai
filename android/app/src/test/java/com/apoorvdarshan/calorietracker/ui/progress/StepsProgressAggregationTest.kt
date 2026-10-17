package com.apoorvdarshan.calorietracker.ui.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class StepsProgressAggregationTest {
    @Test
    fun summaryAveragesOnlyDaysWithStepsAndPicksTheBestDay() {
        val days = listOf(
            LocalDate.parse("2026-10-12") to 6_000,
            LocalDate.parse("2026-10-14") to 12_500,
            LocalDate.parse("2026-10-15") to 8_001
        )

        val summary = stepsSummary(days)

        assertEquals(26_501, summary.total)
        assertEquals(8_834, summary.average)
        assertEquals(LocalDate.parse("2026-10-14") to 12_500, summary.best)
    }

    @Test
    fun emptySummaryIsZero() {
        val summary = stepsSummary(emptyList())
        assertEquals(0, summary.total)
        assertEquals(0, summary.average)
        assertNull(summary.best)
    }

    @Test
    fun weeklyBarsAverageEachCalendarWeek() {
        val days = listOf(
            // Week of Mon 5 Oct 2026.
            LocalDate.parse("2026-10-05") to 4_000,
            LocalDate.parse("2026-10-11") to 6_000,
            // Week of Mon 12 Oct 2026.
            LocalDate.parse("2026-10-13") to 9_000
        )

        assertEquals(days, stepChartBars(days.reversed(), weekly = false, firstDayOfWeek = DayOfWeek.MONDAY))
        assertEquals(
            listOf(LocalDate.parse("2026-10-05") to 5_000, LocalDate.parse("2026-10-12") to 9_000),
            stepChartBars(days, weekly = true, firstDayOfWeek = DayOfWeek.MONDAY)
        )
        // Sunday-first locales bucket the Sunday with the following week.
        assertEquals(
            listOf(LocalDate.parse("2026-10-04") to 4_000, LocalDate.parse("2026-10-11") to 7_500),
            stepChartBars(days, weekly = true, firstDayOfWeek = DayOfWeek.SUNDAY)
        )
    }

    @Test
    fun onlyRangesLongerThanThreeMonthsUseWeeklyBars() {
        val today = LocalDate.parse("2026-10-17")
        val (threeMonthStart, threeMonthEnd) = TimeRange.THREE_MONTHS.dateRange(today)
        val (sixMonthStart, sixMonthEnd) = TimeRange.SIX_MONTHS.dateRange(today)

        assertFalse(showsWeeklyStepBars(threeMonthStart, threeMonthEnd))
        assertTrue(showsWeeklyStepBars(sixMonthStart, sixMonthEnd))
    }

    @Test
    fun historyNoteOnlyShowsForOldWindowsWithoutTheHistoryGrant() {
        val today = LocalDate.parse("2026-10-17")

        assertFalse(stepsHistoryMayBeMissing(today.minusDays(29), today, hasHistoryAccess = false))
        assertTrue(stepsHistoryMayBeMissing(today.minusDays(89), today, hasHistoryAccess = false))
        assertFalse(stepsHistoryMayBeMissing(today.minusDays(89), today, hasHistoryAccess = true))
    }
}
