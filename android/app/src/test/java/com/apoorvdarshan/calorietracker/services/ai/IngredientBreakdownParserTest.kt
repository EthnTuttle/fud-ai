package com.apoorvdarshan.calorietracker.services.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientBreakdownParserTest {
    @Test
    fun repairsIngredientWithoutGramsFromRemainingMealWeight() {
        val ingredients = FoodJsonParser.parseIngredients(
            """
            {"ingredients":[
              {"name":"Greek yogurt","grams":200,"calories":150,"protein":20,"carbs":8,"fat":2},
              {"name":"Berries","grams":100,"calories":50,"protein":1,"carbs":12,"fat":0},
              {"name":"Honey","grams":0,"calories":10,"protein":0,"carbs":3,"fat":0}
            ]}
            """.trimIndent(),
            mealCalories = 300,
            mealGrams = 350.0
        )

        assertEquals(listOf("Greek yogurt", "Berries", "Honey"), ingredients.map { it.name })
        assertEquals(200.0, ingredients.first().grams, 0.0)
        assertEquals(50.0, ingredients.last().grams, 0.0)
    }

    @Test
    fun screenshotOfSeveralDishesKeepsEveryDishAndTheMealTotal() {
        // #343: a meal-list screenshot shows calories per dish but no weight for some dishes.
        val analysis = mealAnalysis(
            calories = 700,
            protein = 40.0,
            carbs = 80.0,
            fat = 24.0,
            servingSizeGrams = 600.0,
            ingredientsJson = """
                {"name":"Dish 1","grams":150,"calories":156,"protein":10,"carbs":20,"fat":4},
                {"name":"Dish 2","grams":null,"calories":544,"protein":30,"carbs":60,"fat":20}
            """
        )

        assertEquals(listOf("Dish 1", "Dish 2"), analysis.ingredients.map { it.name })
        assertEquals(450.0, analysis.ingredients.last().grams, 0.0)
        assertEquals(700, analysis.calories)
        assertEquals(40.0, analysis.protein, 0.001)
    }

    @Test
    fun implausibleRemainingWeightFallsBackToMealEnergyDensity() {
        val ingredients = FoodJsonParser.parseIngredients(
            """
            {"ingredients":[
              {"name":"Salad","grams":390,"calories":100,"protein":5,"carbs":10,"fat":5},
              {"name":"Pasta","calories":500,"protein":25,"carbs":50,"fat":20}
            ]}
            """.trimIndent(),
            mealCalories = 600,
            mealGrams = 400.0
        )

        assertEquals(2, ingredients.size)
        assertEquals(333.3, ingredients.last().grams, 0.001)
    }

    @Test
    fun malformedIngredientDropsTheWholeBreakdownAndKeepsMealTotals() {
        val malformedItems = listOf(
            """{"name":"Rice","grams":150,"calories":200,"protein":4,"carbs":44}""",
            """{"name":"","grams":150,"calories":200,"protein":4,"carbs":44,"fat":1}""",
            """{"name":"Rice","grams":150,"calories":-5,"protein":4,"carbs":44,"fat":1}""",
            """{"name":"Water","grams":0,"calories":0,"protein":0,"carbs":0,"fat":0}""",
            "\"Rice\""
        )
        for (malformed in malformedItems) {
            val analysis = mealAnalysis(
                calories = 500,
                protein = 30.0,
                carbs = 60.0,
                fat = 12.0,
                servingSizeGrams = 400.0,
                ingredientsJson = """
                    {"name":"Chicken","grams":150,"calories":250,"protein":26,"carbs":0,"fat":11},
                    $malformed
                """
            )

            assertTrue(malformed, analysis.ingredients.isEmpty())
            assertEquals(500, analysis.calories)
            assertEquals(30.0, analysis.protein, 0.0)
            assertEquals(60.0, analysis.carbs, 0.0)
            assertEquals(12.0, analysis.fat, 0.0)
        }
    }

    @Test
    fun missingIngredientWeightWithoutMealWeightDropsTheBreakdown() {
        val ingredients = FoodJsonParser.parseIngredients(
            """
            {"ingredients":[
              {"name":"Dish 1","grams":150,"calories":156,"protein":10,"carbs":20,"fat":4},
              {"name":"Dish 2","calories":544,"protein":30,"carbs":60,"fat":20}
            ]}
            """.trimIndent(),
            mealCalories = 700,
            mealGrams = null
        )

        assertTrue(ingredients.isEmpty())
    }

    @Test
    fun moreThanTwentyIngredientsDropsTheBreakdownInsteadOfTruncating() {
        val items = (1..21).joinToString(",") {
            """{"name":"Item $it","grams":10,"calories":20,"protein":1,"carbs":2,"fat":1}"""
        }
        val analysis = mealAnalysis(
            calories = 420,
            protein = 21.0,
            carbs = 42.0,
            fat = 21.0,
            servingSizeGrams = 210.0,
            ingredientsJson = items
        )

        assertTrue(analysis.ingredients.isEmpty())
        assertEquals(420, analysis.calories)
    }

    @Test
    fun breakdownCoveringLessThanHalfTheMealCaloriesIsDropped() {
        val analysis = mealAnalysis(
            calories = 700,
            protein = 40.0,
            carbs = 80.0,
            fat = 24.0,
            servingSizeGrams = 600.0,
            ingredientsJson = """
                {"name":"Dish 1","grams":150,"calories":156,"protein":10,"carbs":20,"fat":4}
            """
        )

        assertTrue(analysis.ingredients.isEmpty())
        assertEquals(700, analysis.calories)
        assertEquals(40.0, analysis.protein, 0.0)
    }

    /** Mirrors parseFoodResponse: meal macros come from the header unless a breakdown replaces them. */
    private fun mealAnalysis(
        calories: Int,
        protein: Double,
        carbs: Double,
        fat: Double,
        servingSizeGrams: Double,
        ingredientsJson: String
    ): FoodAnalysis = FoodAnalysis(
        name = "Meal",
        calories = calories,
        protein = protein,
        carbs = carbs,
        fat = fat,
        servingSizeGrams = servingSizeGrams,
        ingredients = FoodJsonParser.parseIngredients(
            """{"ingredients":[$ingredientsJson]}""",
            mealCalories = calories,
            mealGrams = servingSizeGrams
        )
    ).withIngredientMacroTotals()
}
