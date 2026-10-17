package com.apoorvdarshan.calorietracker.models

/** The serving state a review or edit sheet adopts after "this recipe makes N servings" (#458). */
data class RecipeServingSplit(
    val servings: Int,
    val options: List<ServingUnitOption>,
    val selectedUnitId: String,
    val servingGrams: Double,
    val quantityText: String
)

/**
 * "This recipe makes N servings": divides the whole meal into N equal portions
 * through a regular "serving" unit, so the sheet logs one serving and Saved
 * Meals keep the unit for logging 1 or 2 servings later. Mirrors iOS RecipeServings.
 */
object RecipeServings {
    const val MIN = 1
    const val MAX = 50
    const val UNIT = "serving"

    fun clamped(servings: Int): Int = servings.coerceIn(MIN, MAX)

    /**
     * The control needs a real ingredient breakdown and a known total mass. A
     * Health-restored log with unknown mass uses the internal
     * [ServingUnitOption.loggedServing] sentinel (a serving count, not grams),
     * so it never qualifies and the two "serving" meanings never mix.
     */
    fun isAvailable(ingredientCount: Int, totalGrams: Double, servingSizeIsKnown: Boolean): Boolean =
        servingSizeIsKnown && ingredientCount >= 2 && totalGrams.isFinite() && totalGrams > 0

    /**
     * Replaces any existing "serving" unit with `totalGrams / servings` and selects
     * one serving. The new unit goes first so it survives the four-option cap when
     * the logged entry is reopened.
     */
    fun split(options: List<ServingUnitOption>, totalGrams: Double, servings: Int): RecipeServingSplit {
        val count = clamped(servings)
        val gramsPerServing = totalGrams / count
        val serving = ServingUnitOption(unit = UNIT, gramsPerUnit = gramsPerServing)
        val otherOptions = options.filter { it.normalizedUnit != serving.normalizedUnit }
        return RecipeServingSplit(
            servings = count,
            options = listOf(serving) + otherOptions,
            selectedUnitId = serving.id,
            servingGrams = gramsPerServing,
            quantityText = "1"
        )
    }
}
