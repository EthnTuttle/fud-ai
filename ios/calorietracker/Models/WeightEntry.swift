import Foundation

struct WeightEntry: Identifiable, Codable {
    let id: UUID
    let date: Date
    var weightKg: Double

    init(id: UUID = UUID(), date: Date = .now, weightKg: Double) {
        self.id = id
        self.date = date
        self.weightKg = weightKg
    }

    var weightLbs: Double {
        weightKg * 2.20462
    }
}

/// One body-mass reading read from Apple Health.
struct WeightHealthSample: Equatable {
    let weightKg: Double
    let date: Date
    /// The `fudai_weight_id` metadata Fud AI tags its own samples with; nil for
    /// readings from other sources (scales, Apple Watch, the Health app).
    let fudaiID: UUID?
}

/// A weight entry the user deleted in Fud AI. Remembered so a reading that
/// still exists in Apple Health (another source's sample, or one of ours whose
/// Health delete failed) is not imported back into the history.
struct WeightDeletionTombstone: Codable, Equatable {
    let entryID: UUID
    let date: Date
    let weightKg: Double

    func matches(_ sample: WeightHealthSample) -> Bool {
        if let fudaiID = sample.fudaiID, fudaiID == entryID { return true }
        return abs(sample.date.timeIntervalSince(date)) < 1 && abs(sample.weightKg - weightKg) < 0.01
    }
}
