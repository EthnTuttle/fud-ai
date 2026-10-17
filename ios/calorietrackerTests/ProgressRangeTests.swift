import Foundation
import Testing
@testable import calorietracker

struct ProgressRangeTests {
    @Test func dayRangeStepsBackWholeContiguousWindows() throws {
        let calendar = utcCalendar
        let today = try date(2026, 10, 17, hour: 15, calendar: calendar)

        let current = TimeRange.week.dayRange(offset: 0, endingAt: today, calendar: calendar)
        let previous = TimeRange.week.dayRange(offset: 1, endingAt: today, calendar: calendar)
        let twoBack = TimeRange.week.dayRange(offset: 2, endingAt: today, calendar: calendar)

        let expectedCurrent = try date(2026, 10, 11, calendar: calendar)...date(2026, 10, 17, calendar: calendar)
        let expectedPrevious = try date(2026, 10, 4, calendar: calendar)...date(2026, 10, 10, calendar: calendar)
        let expectedTwoBackEnd = try date(2026, 10, 3, calendar: calendar)
        #expect(current == expectedCurrent)
        #expect(previous == expectedPrevious)
        #expect(twoBack.upperBound == expectedTwoBackEnd)
    }

    @Test func everyRangeCoversItsDayCountAtAnyOffset() {
        let calendar = utcCalendar
        let today = Date(timeIntervalSince1970: 1_792_000_000)
        for range in TimeRange.allCases {
            for offset in 0...2 {
                let days = range.dayRange(offset: offset, endingAt: today, calendar: calendar)
                let span = calendar.dateComponents([.day], from: days.lowerBound, to: days.upperBound).day
                #expect(span == range.days - 1)
            }
        }
    }

    @Test func timestampRangeIncludesReadingsLoggedLaterOnTheLastDay() throws {
        let calendar = utcCalendar
        let now = try date(2026, 10, 17, hour: 8, calendar: calendar)
        let laterToday = try date(2026, 10, 17, hour: 23, minute: 30, calendar: calendar)
        let tomorrow = try date(2026, 10, 18, calendar: calendar)

        let range = TimeRange.week.timestampRange(endingAt: now, calendar: calendar)

        let expectedStart = try date(2026, 10, 11, calendar: calendar)
        #expect(range.contains(now))
        #expect(range.contains(laterToday))
        #expect(!range.contains(tomorrow))
        #expect(range.lowerBound == expectedStart)
    }

    @Test func periodNavigationIsOnlyUnavailableForAllTime() {
        #expect(TimeRange.allCases.filter { !$0.supportsPeriodNavigation } == [.allTime])
    }

    @Test func periodLabelShowsTheYearOnlyOutsideTheCurrentYear() throws {
        let calendar = utcCalendar
        let now = try date(2026, 6, 20, hour: 12, calendar: calendar)
        let thisYear = TimeRange.week.dayRange(endingAt: now, calendar: calendar)
        let lastYearEnd = try date(2025, 6, 20, hour: 12, calendar: calendar)
        let lastYear = TimeRange.week.dayRange(endingAt: lastYearEnd, calendar: calendar)

        #expect(!TimeRange.periodLabel(for: thisYear, now: now, calendar: calendar).contains("2026"))
        #expect(TimeRange.periodLabel(for: lastYear, now: now, calendar: calendar).contains("2025"))
    }

    @Test func foodStatsOnlyCountDaysInsideThePastWindow() throws {
        let calendar = utcCalendar
        let endDay = try date(2026, 10, 10, calendar: calendar)
        let entries = try [
            food(calories: 2_000, protein: 100, at: date(2026, 10, 4, hour: 9, calendar: calendar)),
            food(calories: 1_000, protein: 50, at: date(2026, 10, 10, hour: 21, calendar: calendar)),
            // Outside the window on either side.
            food(calories: 3_000, protein: 300, at: date(2026, 10, 3, hour: 12, calendar: calendar)),
            food(calories: 3_000, protein: 300, at: date(2026, 10, 11, hour: 1, calendar: calendar)),
        ]

        let stats = ProgressFoodRangeStats.compute(
            entries: entries,
            dayCount: 7,
            endDay: endDay,
            profile: .default,
            optionalGoals: OptionalNutrientGoals(),
            calendar: calendar
        )

        let expectedDays = try [date(2026, 10, 4, calendar: calendar), date(2026, 10, 10, calendar: calendar)]
        #expect(stats.dailyCalories.map(\.calories) == [2_000, 1_000])
        #expect(stats.dailyCalories.map(\.date) == expectedDays)
        #expect(stats.avgProtein == 75)
    }

    private var utcCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private func date(
        _ year: Int,
        _ month: Int,
        _ day: Int,
        hour: Int = 0,
        minute: Int = 0,
        calendar: Calendar
    ) throws -> Date {
        try #require(calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)))
    }

    private func food(calories: Int, protein: Double, at timestamp: Date) -> FoodEntry {
        FoodEntry(
            name: "Meal",
            calories: calories,
            protein: protein,
            carbs: 0,
            fat: 0,
            timestamp: timestamp,
            source: .manual
        )
    }
}
