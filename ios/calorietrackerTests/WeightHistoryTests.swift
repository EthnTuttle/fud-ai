import Foundation
import Testing
@testable import calorietracker

/// Weight history regressions: deleted readings coming back from Apple Health
/// (#518) and readings missing from the Progress chart (#519).
@MainActor
struct WeightHistoryTests {
    // MARK: - Deletion stays deleted

    @Test func deletedExternalHealthReadingIsNotImportedAgain() throws {
        try withStore { store, _ in
            let scale = WeightHealthSample(weightKg: 81.4, date: Date(timeIntervalSince1970: 1_790_000_000), fudaiID: nil)
            store.importHealthSamples([scale], restoreOwnSamplesIfEmpty: false)
            let imported = try #require(store.entries.first)

            store.deleteEntry(imported)
            #expect(store.entries.isEmpty)
            #expect(store.isDeletedHealthSample(scale))

            // The scale's sample is still in Apple Health; the next observer
            // fire or full-history import must not resurrect it.
            store.importHealthSamples([scale], restoreOwnSamplesIfEmpty: true)
            #expect(store.entries.isEmpty)

            // A genuinely new reading from the same source still imports.
            let nextDay = WeightHealthSample(weightKg: 81.0, date: scale.date.addingTimeInterval(86_400), fudaiID: nil)
            store.importHealthSamples([nextDay], restoreOwnSamplesIfEmpty: false)
            #expect(store.entries.map(\.weightKg) == [81.0])
        }
    }

    @Test func deletionTombstonesPersistAcrossLaunches() throws {
        try withStore { store, defaults in
            let entry = WeightEntry(date: Date(timeIntervalSince1970: 1_790_000_000), weightKg: 70)
            store.replaceAllEntries([entry])
            store.deleteEntry(entry)

            let relaunched = WeightStore(observesExternalChanges: false, defaults: defaults)
            #expect(relaunched.deletionTombstones == [
                WeightDeletionTombstone(entryID: entry.id, date: entry.date, weightKg: 70)
            ])
            #expect(relaunched.isDeletedHealthSample(
                WeightHealthSample(weightKg: 70, date: entry.date, fudaiID: nil)
            ))
        }
    }

    @Test func deletedOwnSampleIsNotRestoredAfterReinstall() throws {
        try withStore { store, _ in
            let deleted = WeightEntry(date: Date(timeIntervalSince1970: 1_790_000_000), weightKg: 72)
            let kept = WeightEntry(date: Date(timeIntervalSince1970: 1_790_100_000), weightKg: 71)
            store.replaceAllEntries([deleted])
            // Our Health delete can fail (or lag); the tagged sample survives.
            store.deleteEntry(deleted)

            store.importHealthSamples([
                WeightHealthSample(weightKg: 72, date: deleted.date, fudaiID: deleted.id),
                WeightHealthSample(weightKg: 71, date: kept.date, fudaiID: kept.id),
            ], restoreOwnSamplesIfEmpty: true)

            #expect(store.entries.map(\.id) == [kept.id])
        }
    }

    @Test func healthImportDedupesAndOnlyRestoresOwnSamplesFromAnEmptyHistory() {
        let day = Date(timeIntervalSince1970: 1_790_000_000)
        let existing = [WeightEntry(date: day, weightKg: 80)]
        let samples = [
            WeightHealthSample(weightKg: 80.004, date: day.addingTimeInterval(60), fudaiID: nil), // same day + value
            WeightHealthSample(weightKg: 79.5, date: day.addingTimeInterval(-86_400 * 400), fudaiID: nil), // old backfill
            WeightHealthSample(weightKg: 79.5, date: day.addingTimeInterval(-86_400 * 400 + 30), fudaiID: nil), // batch dup
            WeightHealthSample(weightKg: 78, date: day.addingTimeInterval(86_400), fudaiID: UUID()), // own sample
        ]

        let fresh = WeightStore.healthImportCandidates(
            samples, existing: existing, tombstones: [], restoringOwnHistory: false
        )
        #expect(fresh.map(\.weightKg) == [79.5])

        let ownID = UUID()
        let restored = WeightStore.healthImportCandidates(
            [WeightHealthSample(weightKg: 78, date: day, fudaiID: ownID)],
            existing: [], tombstones: [], restoringOwnHistory: true
        )
        #expect(restored.map(\.id) == [ownID])
    }

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
