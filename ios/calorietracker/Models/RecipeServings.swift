import Foundation

/// The serving state a review or edit sheet adopts after the user says how many
/// servings a multi-ingredient recipe makes (#458).
struct RecipeServingSplit: Equatable {
    var servings: Int
    var options: [ServingUnitOption]
    var selectedUnitID: String
    var servingSizeGrams: Double
    var quantityText: String
}

/// "This recipe makes N servings": divides the whole meal into N equal portions
/// through a regular "serving" unit, so the sheet logs one serving and Saved
/// Meals keep the unit for logging 1 or 2 servings later.
enum RecipeServings {
    static let range = 1...50
    static let unit = "serving"

    static func clamped(_ servings: Int) -> Int {
        min(max(servings, range.lowerBound), range.upperBound)
    }

    /// The control needs a real ingredient breakdown and a known total mass.
    /// A Health-restored log with unknown mass uses the internal
    /// `ServingUnitOption.loggedServing` sentinel (a serving count, not grams),
    /// so it never qualifies and the two "serving" meanings never mix.
    static func isAvailable(ingredientCount: Int, totalGrams: Double, servingSizeIsKnown: Bool) -> Bool {
        servingSizeIsKnown && ingredientCount >= 2 && totalGrams.isFinite && totalGrams > 0
    }

    /// Replaces any existing "serving" unit with `totalGrams / servings` and
    /// selects one serving. The new unit goes first so it survives the
    /// four-option cap when the logged entry is reopened.
    static func split(options: [ServingUnitOption], totalGrams: Double, servings: Int) -> RecipeServingSplit {
        let count = clamped(servings)
        let gramsPerServing = totalGrams / Double(count)
        let serving = ServingUnitOption(unit: unit, gramsPerUnit: gramsPerServing)
        let otherOptions = options.filter { $0.normalizedUnit != serving.normalizedUnit }
        return RecipeServingSplit(
            servings: count,
            options: [serving] + otherOptions,
            selectedUnitID: serving.id,
            servingSizeGrams: gramsPerServing,
            quantityText: "1"
        )
    }
}
