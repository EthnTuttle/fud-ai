import Foundation
import Testing
@testable import calorietracker

/// Fixtures match Android `IngredientLabelParserTest`; keep both in sync.
struct IngredientLabelParserTests {
    @Test
    func splitsTopLevelEntriesAndTrimsTheFinalPeriod() {
        let items = IngredientLabelParser.items(
            from: "Sugar, palm oil, hazelnuts 13%, skimmed milk powder 8.7%, emulsifier: lecithins (soya), vanillin."
        )
        #expect(items.map(\.text) == [
            "Sugar", "palm oil", "hazelnuts 13%", "skimmed milk powder 8.7%",
            "emulsifier: lecithins (soya)", "vanillin"
        ])
    }

    @Test
    func keepsBracketedSubIngredientsTogether() {
        #expect(texts("Vegetable oil (palm, sunflower), salt") == ["Vegetable oil (palm, sunflower)", "salt"])
        #expect(texts("Chocolate (sugar, cocoa butter (from beans), milk), salt") == [
            "Chocolate (sugar, cocoa butter (from beans), milk)", "salt"
        ])
        #expect(texts("Water; sugar [cane, beet]; acid: citric acid") == [
            "Water", "sugar [cane, beet]", "acid: citric acid"
        ])
        #expect(texts("小麦粉（国内製造、米国産）、砂糖、食塩") == ["小麦粉（国内製造、米国産）", "砂糖", "食塩"])
    }

    @Test
    func keepsDecimalCommasInsidePercentages() {
        #expect(texts("Zucker, Magermilchpulver 8,7%, Salz") == ["Zucker", "Magermilchpulver 8,7%", "Salz"])
    }

    @Test
    func stripsIngredientHeadingsButNotOtherLeadingColons() {
        #expect(texts("Ingredients: oats, salt.") == ["oats", "salt"])
        #expect(texts("Zutaten: Zucker, Salz") == ["Zucker", "Salz"])
        #expect(texts("原材料名：小麦粉、砂糖") == ["小麦粉", "砂糖"])
        #expect(texts("İçindekiler: şeker, tuz") == ["şeker", "tuz"])
        #expect(texts("Składniki: cukier, sól") == ["cukier", "sól"])
        #expect(texts("Emulsifier: soy lecithin, sugar") == ["Emulsifier: soy lecithin", "sugar"])
    }

    @Test
    func collapsesWhitespaceAndDropsEmptyEntries() {
        #expect(texts("Sugar,\n  cocoa   butter ,, salt ;") == ["Sugar", "cocoa butter", "salt"])
        #expect(texts("Sugar,\u{00A0}\u{00A0}cocoa\u{00A0} butter, salt") == ["Sugar", "cocoa butter", "salt"])
    }

    @Test
    func labelsThatDoNotFormAListFallBackToAParagraph() {
        #expect(IngredientLabelParser.items(from: "Whole milk").isEmpty)
        #expect(IngredientLabelParser.items(from: "").isEmpty)
        #expect(IngredientLabelParser.items(from: "   ").isEmpty)

        let sixty = (1...60).map { "item \($0)" }.joined(separator: ", ")
        let sixtyOne = (1...61).map { "item \($0)" }.joined(separator: ", ")
        #expect(IngredientLabelParser.items(from: sixty).count == 60)
        #expect(IngredientLabelParser.items(from: sixtyOne).isEmpty)
    }

    @Test
    func openFoodFactsAllergenMarkersBecomeEmphasizedSegments() {
        #expect(IngredientLabelParser.segments(in: "skimmed _milk_ powder") == [
            .init(text: "skimmed ", isAllergen: false),
            .init(text: "milk", isAllergen: true),
            .init(text: " powder", isAllergen: false)
        ])
        #expect(IngredientLabelParser.segments(in: "sugar_ syrup") == [.init(text: "sugar syrup", isAllergen: false)])

        let items = IngredientLabelParser.items(from: "Ingrédients : farine de _blé_, sel")
        #expect(items.map(\.text) == ["farine de blé", "sel"])
        #expect(items.first?.segments == [
            .init(text: "farine de ", isAllergen: false),
            .init(text: "blé", isAllergen: true)
        ])
    }

    @Test
    func entriesMatchingSensitivitiesAreFlagged() {
        let items = IngredientLabelParser.items(
            from: "Sugar, skimmed _milk_ powder, whey, hazelnuts",
            sensitivities: ["milk", "nuts"]
        )
        #expect(items.map(\.matchesSensitivity) == [false, true, true, true])
        #expect(IngredientLabelParser.items(from: "Sugar, whey").map(\.matchesSensitivity) == [false, false])
    }

    private func texts(_ label: String) -> [String] {
        IngredientLabelParser.items(from: label).map(\.text)
    }
}
