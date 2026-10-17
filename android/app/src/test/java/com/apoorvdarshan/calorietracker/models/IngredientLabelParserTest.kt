package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures match iOS IngredientLabelParserTests; keep both in sync. */
class IngredientLabelParserTest {
    @Test
    fun splitsTopLevelEntriesAndTrimsTheFinalPeriod() {
        assertEquals(
            listOf(
                "Sugar", "palm oil", "hazelnuts 13%", "skimmed milk powder 8.7%",
                "emulsifier: lecithins (soya)", "vanillin"
            ),
            texts("Sugar, palm oil, hazelnuts 13%, skimmed milk powder 8.7%, emulsifier: lecithins (soya), vanillin.")
        )
    }

    @Test
    fun keepsBracketedSubIngredientsTogether() {
        assertEquals(listOf("Vegetable oil (palm, sunflower)", "salt"), texts("Vegetable oil (palm, sunflower), salt"))
        assertEquals(
            listOf("Chocolate (sugar, cocoa butter (from beans), milk)", "salt"),
            texts("Chocolate (sugar, cocoa butter (from beans), milk), salt")
        )
        assertEquals(
            listOf("Water", "sugar [cane, beet]", "acid: citric acid"),
            texts("Water; sugar [cane, beet]; acid: citric acid")
        )
        assertEquals(listOf("小麦粉（国内製造、米国産）", "砂糖", "食塩"), texts("小麦粉（国内製造、米国産）、砂糖、食塩"))
    }

    @Test
    fun keepsDecimalCommasInsidePercentages() {
        assertEquals(listOf("Zucker", "Magermilchpulver 8,7%", "Salz"), texts("Zucker, Magermilchpulver 8,7%, Salz"))
    }

    @Test
    fun stripsIngredientHeadingsButNotOtherLeadingColons() {
        assertEquals(listOf("oats", "salt"), texts("Ingredients: oats, salt."))
        assertEquals(listOf("Zucker", "Salz"), texts("Zutaten: Zucker, Salz"))
        assertEquals(listOf("小麦粉", "砂糖"), texts("原材料名：小麦粉、砂糖"))
        assertEquals(listOf("şeker", "tuz"), texts("İçindekiler: şeker, tuz"))
        assertEquals(listOf("cukier", "sól"), texts("Składniki: cukier, sól"))
        assertEquals(listOf("Emulsifier: soy lecithin", "sugar"), texts("Emulsifier: soy lecithin, sugar"))
    }

    @Test
    fun collapsesWhitespaceAndDropsEmptyEntries() {
        assertEquals(listOf("Sugar", "cocoa butter", "salt"), texts("Sugar,\n  cocoa   butter ,, salt ;"))
        assertEquals(listOf("Sugar", "cocoa butter", "salt"), texts("Sugar,\u00A0\u00A0cocoa\u00A0 butter, salt"))
    }

    @Test
    fun labelsThatDoNotFormAListFallBackToAParagraph() {
        assertTrue(IngredientLabelParser.items("Whole milk").isEmpty())
        assertTrue(IngredientLabelParser.items("").isEmpty())
        assertTrue(IngredientLabelParser.items("   ").isEmpty())

        val sixty = (1..60).joinToString(", ") { "item $it" }
        val sixtyOne = (1..61).joinToString(", ") { "item $it" }
        assertEquals(60, IngredientLabelParser.items(sixty).size)
        assertTrue(IngredientLabelParser.items(sixtyOne).isEmpty())
    }

    @Test
    fun openFoodFactsAllergenMarkersBecomeEmphasizedSegments() {
        assertEquals(
            listOf(
                IngredientLabelItem.Segment("skimmed ", isAllergen = false),
                IngredientLabelItem.Segment("milk", isAllergen = true),
                IngredientLabelItem.Segment(" powder", isAllergen = false)
            ),
            IngredientLabelParser.segments("skimmed _milk_ powder")
        )
        assertEquals(
            listOf(IngredientLabelItem.Segment("sugar syrup", isAllergen = false)),
            IngredientLabelParser.segments("sugar_ syrup")
        )

        val items = IngredientLabelParser.items("Ingrédients : farine de _blé_, sel")
        assertEquals(listOf("farine de blé", "sel"), items.map { it.text })
        assertEquals(
            listOf(
                IngredientLabelItem.Segment("farine de ", isAllergen = false),
                IngredientLabelItem.Segment("blé", isAllergen = true)
            ),
            items.first().segments
        )
    }

    @Test
    fun entriesMatchingSensitivitiesAreFlagged() {
        val items = IngredientLabelParser.items(
            "Sugar, skimmed _milk_ powder, whey, hazelnuts",
            sensitivities = listOf("milk", "nuts")
        )
        assertEquals(listOf(false, true, true, true), items.map { it.matchesSensitivity })
        assertEquals(listOf(false, false), IngredientLabelParser.items("Sugar, whey").map { it.matchesSensitivity })
    }

    private fun texts(label: String): List<String> = IngredientLabelParser.items(label).map { it.text }
}
