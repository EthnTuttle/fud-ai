package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "This recipe makes N servings" in the review and edit sheets (#458). */
class RecipeServingsTest {
    @Test
    fun splitDividesTheWholeRecipeIntoOneSelectedServing() {
        val split = RecipeServings.split(emptyList(), totalGrams = 2000.0, servings = 5)

        assertEquals(5, split.servings)
        assertEquals(listOf(ServingUnitOption(unit = "serving", gramsPerUnit = 400.0)), split.options)
        assertEquals("serving", split.selectedUnitId)
        assertEquals(400.0, split.servingGrams, 0.0)
        assertEquals("1", split.quantityText)
    }

    @Test
    fun splitReplacesAnExistingServingUnitAndKeepsTheOthersAfterIt() {
        val options = listOf(
            ServingUnitOption(unit = "cup", gramsPerUnit = 240.0),
            ServingUnitOption(unit = "Serving", gramsPerUnit = 250.0, quantity = 8.0)
        )

        val split = RecipeServings.split(options, totalGrams = 2000.0, servings = 4)

        assertEquals(listOf("serving", "cup"), split.options.map { it.normalizedUnit })
        assertEquals(500.0, split.options[0].gramsPerUnit, 0.0)
        assertEquals(ServingUnitOption(unit = "cup", gramsPerUnit = 240.0), split.options[1])
    }

    @Test
    fun splitClampsServingsToOneThroughFifty() {
        val tooFew = RecipeServings.split(emptyList(), totalGrams = 1000.0, servings = 0)
        val tooMany = RecipeServings.split(emptyList(), totalGrams = 1000.0, servings = 80)

        assertEquals(1, tooFew.servings)
        assertEquals(1000.0, tooFew.servingGrams, 0.0)
        assertEquals(50, tooMany.servings)
        assertEquals(20.0, tooMany.servingGrams, 0.0)
    }

    @Test
    fun controlNeedsTwoIngredientsAndAKnownTotalWeight() {
        assertTrue(RecipeServings.isAvailable(ingredientCount = 2, totalGrams = 900.0, servingSizeIsKnown = true))
        assertFalse(RecipeServings.isAvailable(ingredientCount = 1, totalGrams = 900.0, servingSizeIsKnown = true))
        assertFalse(RecipeServings.isAvailable(ingredientCount = 3, totalGrams = 0.0, servingSizeIsKnown = true))
        assertFalse(RecipeServings.isAvailable(ingredientCount = 3, totalGrams = Double.NaN, servingSizeIsKnown = true))
        // A Health-restored log with unknown mass keeps its internal serving sentinel.
        assertFalse(RecipeServings.isAvailable(ingredientCount = 3, totalGrams = 1.0, servingSizeIsKnown = false))
    }

    @Test
    fun oneServingScalesNutritionAndIngredientsToOneNth() {
        val ingredients = listOf(
            MealIngredient("Rice", 1200.0, 1560, 32.0, 340.0, 4.0),
            MealIngredient("Chicken", 800.0, 1320, 248.0, 0.0, 29.0)
        )
        val total = ingredients.totals()
        val split = RecipeServings.split(emptyList(), total.grams, servings = 5)
        // The sheets scale everything by selected grams / whole-recipe grams.
        val scale = split.servingGrams / total.grams
        val serving = ingredients.map { it.scaled(scale) }

        assertEquals(0.2, scale, 0.000_001)
        assertEquals(240.0, serving[0].grams, 0.000_001)
        assertEquals(160.0, serving[1].grams, 0.000_001)
        assertEquals(576, serving.totals().calories)
        assertEquals(56.0, serving.totals().protein, 0.000_001)
    }

    @Test
    fun savedMealKeepsTheServingUnitSoTwoServingsCanBeLoggedLater() {
        val split = RecipeServings.split(emptyList(), totalGrams = 2000.0, servings = 5)
        val logged = FoodEntry(
            name = "Chili",
            calories = 500,
            protein = 30.0,
            carbs = 40.0,
            fat = 20.0,
            source = FoodSource.MANUAL,
            servingSizeGrams = split.servingGrams,
            servingUnitOptions = split.options,
            selectedServingUnit = "serving",
            selectedServingQuantity = 1.0,
            ingredients = listOf(
                MealIngredient("Beans", 250.0, 300, 20.0, 35.0, 2.0),
                MealIngredient("Beef", 150.0, 200, 10.0, 5.0, 18.0)
            )
        )

        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(FoodEntry.serializer(), json.encodeToString(FoodEntry.serializer(), logged))
        val reopened = ServingUnitOption.normalizedOptions(decoded.reviewServingUnitOptions, decoded.reviewServingReference)
        val unitId = ServingUnitOption.initialUnitId(decoded.reviewSelectedServingUnit, reopened)
        val servingOption = ServingUnitOption.optionMatching(unitId, reopened)

        assertTrue(decoded.hasKnownServingSize)
        assertEquals("serving", unitId)
        assertEquals(400.0, servingOption.gramsPerUnit, 0.0)
        assertEquals(2.0, 2 * servingOption.gramsPerUnit / decoded.reviewServingReference, 0.0)
    }
}
