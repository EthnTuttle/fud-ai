import Foundation
import Testing
@testable import calorietracker

/// Weight history regressions: readings missing from the Progress chart (#519).
@MainActor
struct WeightHistoryTests {
    // MARK: - Chart range

    @Test func progressRangesIncludeReadingsLoggedToday() throws {
        try withStore { store, _ in
            let calendar = Calendar.current
            let today = Date()
            let threeDaysAgo = try #require(calendar.date(byAdding: .day, value: -3, to: today))
            let lastMonth = try #require(calendar.date(byAdding: .day, value: -30, to: today))
            store.replaceAllEntries([
                WeightEntry(date: lastMonth, weightKg: 82),
                WeightEntry(date: threeDaysAgo, weightKg: 81),
                WeightEntry(date: today, weightKg: 80),
            ])

            let week = store.entries(in: TimeRange.week.dateRange())
            #expect(week.map(\.weightKg) == [81, 80])
        }
    }

    @Test func allTimeRangeIncludesBackfilledOlderReadings() throws {
        try withStore { store, _ in
            let calendar = Calendar.current
            let today = Date()
            let twoYearsAgo = try #require(calendar.date(byAdding: .year, value: -2, to: today))
            store.replaceAllEntries([
                WeightEntry(date: today, weightKg: 80),
                WeightEntry(date: twoYearsAgo, weightKg: 95),
            ])

            let all = store.entries(in: TimeRange.allTime.dateRange())
            #expect(all.map(\.weightKg) == [95, 80])
        }
    }

    @Test func dayRangeContainsWholeLastDay() throws {
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: Date(timeIntervalSince1970: 1_790_000_000))
        let end = try #require(calendar.date(byAdding: .day, value: 6, to: start))
        let range = start...end
        let lateOnLastDay = try #require(calendar.date(byAdding: .hour, value: 23, to: end))
        let nextDay = try #require(calendar.date(byAdding: .day, value: 1, to: end))

        #expect(range.containsDay(of: start))
        #expect(range.containsDay(of: lateOnLastDay))
        #expect(!range.containsDay(of: nextDay))
        #expect(!range.containsDay(of: start.addingTimeInterval(-1)))
    }

    private func withStore(_ body: (WeightStore, UserDefaults) throws -> Void) throws {
        let suite = "WeightHistoryTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let backupDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("WeightHistoryTests-\(UUID().uuidString)", isDirectory: true)
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: backupDir)
        }
        let store = WeightStore(observesExternalChanges: false, defaults: defaults, corruptBackupDirectory: backupDir)
        try body(store, defaults)
    }
}
