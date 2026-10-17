import Foundation
import Testing
@testable import calorietracker

/// "This recipe makes N servings" in the review and edit sheets (#458).
struct RecipeServingsTests {

    @Test func splitDividesTheWholeRecipeIntoOneSelectedServing() {
        let split = RecipeServings.split(options: [], totalGrams: 2000, servings: 5)

        #expect(split.servings == 5)
        #expect(split.options.count == 1)
        #expect(split.options.first?.unit == "serving")
        #expect(split.options.first?.gramsPerUnit == 400)
        #expect(split.selectedUnitID == "serving")
        #expect(split.servingSizeGrams == 400)
        #expect(split.quantityText == "1")
    }

    @Test func splitReplacesAnExistingServingUnitAndKeepsTheOthersAfterIt() {
        let options = [
            ServingUnitOption(unit: "cup", gramsPerUnit: 240),
            ServingUnitOption(unit: "Serving", gramsPerUnit: 250, quantity: 8)
        ]

        let split = RecipeServings.split(options: options, totalGrams: 2000, servings: 4)

        #expect(split.options.map(\.normalizedUnit) == ["serving", "cup"])
        #expect(split.options[0].gramsPerUnit == 500)
        #expect(split.options[1] == ServingUnitOption(unit: "cup", gramsPerUnit: 240))
    }

    @Test func splitClampsServingsToOneThroughFifty() {
        #expect(RecipeServings.split(options: [], totalGrams: 1000, servings: 0).servings == 1)
        #expect(RecipeServings.split(options: [], totalGrams: 1000, servings: 0).servingSizeGrams == 1000)
        #expect(RecipeServings.split(options: [], totalGrams: 1000, servings: 80).servings == 50)
        #expect(RecipeServings.split(options: [], totalGrams: 1000, servings: 80).servingSizeGrams == 20)
    }

    @Test func controlNeedsTwoIngredientsAndAKnownTotalWeight() {
        #expect(RecipeServings.isAvailable(ingredientCount: 2, totalGrams: 900, servingSizeIsKnown: true))
        #expect(!RecipeServings.isAvailable(ingredientCount: 1, totalGrams: 900, servingSizeIsKnown: true))
        #expect(!RecipeServings.isAvailable(ingredientCount: 3, totalGrams: 0, servingSizeIsKnown: true))
        #expect(!RecipeServings.isAvailable(ingredientCount: 3, totalGrams: .nan, servingSizeIsKnown: true))
        // A Health-restored log with unknown mass keeps its internal serving sentinel.
        #expect(!RecipeServings.isAvailable(ingredientCount: 3, totalGrams: 1, servingSizeIsKnown: false))
    }

    @Test func oneServingScalesNutritionAndIngredientsToOneNth() {
        let ingredients = [
            MealIngredient(name: "Rice", grams: 1200, calories: 1560, protein: 32, carbs: 340, fat: 4),
            MealIngredient(name: "Chicken", grams: 800, calories: 1320, protein: 248, carbs: 0, fat: 29)
        ]
        let total = ingredients.ingredientTotals
        let split = RecipeServings.split(options: [], totalGrams: total.grams, servings: 5)
        // The sheets scale everything by selected grams / whole-recipe grams.
        let scale = split.servingSizeGrams / total.grams
        let serving = ingredients.map { $0.scaled(by: scale) }

        #expect(abs(scale - 0.2) < 0.000_001)
        #expect(abs(serving[0].grams - 240) < 0.000_001)
        #expect(abs(serving[1].grams - 160) < 0.000_001)
        #expect(serving.ingredientTotals.calories == 576)
        #expect(abs(serving.ingredientTotals.protein - 56) < 0.000_001)
    }

    @Test func savedMealKeepsTheServingUnitSoTwoServingsCanBeLoggedLater() throws {
        let split = RecipeServings.split(options: [], totalGrams: 2000, servings: 5)
        let logged = FoodEntry(
            name: "Chili",
            calories: 500,
            protein: 30,
            carbs: 40,
            fat: 20,
            source: .manual,
            servingSizeGrams: split.servingSizeGrams,
            servingUnitOptions: split.options,
            selectedServingUnit: "serving",
            selectedServingQuantity: 1,
            ingredients: [
                MealIngredient(name: "Beans", grams: 250, calories: 300, protein: 20, carbs: 35, fat: 2),
                MealIngredient(name: "Beef", grams: 150, calories: 200, protein: 10, carbs: 5, fat: 18)
            ]
        )

        let decoded = try JSONDecoder().decode(FoodEntry.self, from: JSONEncoder().encode(logged))
        let reopened = ServingUnitOption.normalizedOptions(
            decoded.reviewServingUnitOptions,
            totalGrams: decoded.reviewServingReference
        )
        let unitID = ServingUnitOption.initialUnitID(preferredUnit: decoded.reviewSelectedServingUnit, options: reopened)
        let servingOption = ServingUnitOption.option(matching: unitID, in: reopened)

        #expect(decoded.hasKnownServingSize)
        #expect(unitID == "serving")
        #expect(servingOption.gramsPerUnit == 400)
        #expect(2 * servingOption.gramsPerUnit / decoded.reviewServingReference == 2)
    }
}
