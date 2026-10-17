import CoreGraphics
import Foundation
import Testing
@testable import calorietracker

struct NutritionLabelTextTests {
    /// A fragment placed with a top-left origin, converted to Vision's
    /// lower-left normalized coordinates.
    private func cell(
        _ text: String,
        x: CGFloat,
        top: CGFloat,
        width: CGFloat = 0.2,
        height: CGFloat = 0.03
    ) -> NutritionLabelText.Fragment {
        NutritionLabelText.Fragment(
            text: text,
            box: CGRect(x: x, y: 1 - top - height, width: width, height: height)
        )
    }

    private var usLabel: [NutritionLabelText.Fragment] {
        [
            cell("Nutrition Facts", x: 0.1, top: 0.05, width: 0.5, height: 0.05),
            cell("Serving size", x: 0.1, top: 0.12),
            cell("2/3 cup (55g)", x: 0.6, top: 0.121),
            cell("Calories", x: 0.1, top: 0.18, height: 0.04),
            cell("230", x: 0.75, top: 0.182, width: 0.1, height: 0.04),
            cell("  Total   Fat 8g ", x: 0.1, top: 0.25),
            cell("10%", x: 0.8, top: 0.252, width: 0.1),
            cell("Sodium", x: 0.1, top: 0.30),
            cell("160", x: 0.45, top: 0.30, width: 0.08),
            // Vision sometimes reads the unit as its own fragment.
            cell("mg", x: 0.55, top: 0.301, width: 0.05),
            cell("7%", x: 0.8, top: 0.30, width: 0.1),
            cell(" ", x: 0.5, top: 0.33),
            cell("Protein 3g", x: 0.1, top: 0.35),
        ]
    }

    @Test
    func assemblesRowsTopToBottomWithColumnsInReadingOrder() {
        // Vision's result order is not reading order.
        let lines = NutritionLabelText.lines(from: usLabel.reversed())
        #expect(lines == [
            "Nutrition Facts",
            "Serving size | 2/3 cup (55g)",
            "Calories | 230",
            "Total Fat 8g | 10%",
            "Sodium | 160 mg | 7%",
            "Protein 3g",
        ])
    }

    @Test
    func keepsPerServingAndPer100gColumnsApart() {
        let lines = NutritionLabelText.lines(from: [
            cell("Typical values", x: 0.05, top: 0.12),
            cell("per 100 g", x: 0.5, top: 0.12),
            cell("per serving (30 g)", x: 0.75, top: 0.12),
            cell("Energy", x: 0.05, top: 0.18),
            cell("1046 kJ / 250 kcal", x: 0.5, top: 0.18),
            cell("314 kJ / 75 kcal", x: 0.75, top: 0.18),
            cell("Fat", x: 0.05, top: 0.24),
            cell("9,5 g", x: 0.5, top: 0.24),
            cell("2,9 g", x: 0.75, top: 0.24),
        ])
        #expect(lines == [
            "Typical values | per 100 g | per serving (30 g)",
            "Energy | 1046 kJ / 250 kcal | 314 kJ / 75 kcal",
            "Fat | 9,5 g | 2,9 g",
        ])
    }

    @Test
    func followsASlightTiltAcrossColumnsWithoutMergingNeighbouringRows() {
        // Each column sits a little higher than the one to its left, and no
        // fragment reports a slope.
        let lines = NutritionLabelText.lines(from: [
            cell("Energy", x: 0.05, top: 0.40),
            cell("1046 kJ", x: 0.4, top: 0.388),
            cell("314 kJ", x: 0.7, top: 0.376),
            cell("Fat", x: 0.05, top: 0.445),
            cell("9,5 g", x: 0.4, top: 0.433),
            cell("2,9 g", x: 0.7, top: 0.421),
        ])
        #expect(lines == ["Energy | 1046 kJ | 314 kJ", "Fat | 9,5 g | 2,9 g"])
    }

    @Test
    func levelsAPhotoTiltedAcrossTheWholeLabel() {
        // The right column sits almost a whole row higher than its nutrient,
        // so without leveling "Calories" would pair with "10%".
        let slope: CGFloat = 0.06
        func tilted(_ text: String, x: CGFloat, rowTop: CGFloat, width: CGFloat) -> NutritionLabelText.Fragment {
            cell(text, x: x, top: rowTop - slope * (x + width / 2), width: width)
        }
        let fragments = [
            tilted("Calories", x: 0.05, rowTop: 0.10, width: 0.15),
            tilted("230", x: 0.8, rowTop: 0.10, width: 0.08),
            tilted("Total Fat 8g", x: 0.05, rowTop: 0.145, width: 0.25),
            tilted("10%", x: 0.8, rowTop: 0.145, width: 0.08),
            tilted("Sodium 160mg", x: 0.05, rowTop: 0.19, width: 0.25),
            tilted("7%", x: 0.8, rowTop: 0.19, width: 0.08),
            tilted("Total Carbohydrate 37g", x: 0.05, rowTop: 0.235, width: 0.4),
            tilted("13%", x: 0.8, rowTop: 0.235, width: 0.08),
        ]
        let expected = ["Calories | 230", "Total Fat 8g | 10%", "Sodium 160mg | 7%", "Total Carbohydrate 37g | 13%"]
        #expect(NutritionLabelText.lines(from: fragments) == expected)

        // Flipped upside down, the same rows tilt the other way and read in reverse.
        let mirrored = fragments.map { fragment -> NutritionLabelText.Fragment in
            var fragment = fragment
            fragment.box.origin.y = 1 - fragment.box.maxY
            return fragment
        }
        #expect(NutritionLabelText.lines(from: mirrored.reversed()) == expected.reversed())
    }

    @Test
    func readsArabicRowsRightToLeftButBilingualRowsLeftToRight() {
        let arabic = NutritionLabelText.lines(from: [
            cell("250 kcal", x: 0.1, top: 0.2),
            cell("الطاقة", x: 0.7, top: 0.2),
        ])
        #expect(arabic == ["الطاقة | 250 kcal"])

        let bilingual = NutritionLabelText.lines(from: [
            cell("الطاقة", x: 0.75, top: 0.2),
            cell("Energy", x: 0.05, top: 0.2),
            cell("250 kcal", x: 0.4, top: 0.2),
        ])
        #expect(bilingual == ["Energy | 250 kcal | الطاقة"])
    }

    @Test
    func recognizesNutritionLabelsInSeveralScripts() {
        #expect(NutritionLabelText.isLikelyNutritionLabel(NutritionLabelText.lines(from: usLabel)))
        #expect(NutritionLabelText.isLikelyNutritionLabel([
            "Nährwerte | pro 100 g", "Energie | 1046kJ/250kcal", "Fett | 9,5 g", "Eiweiß | 3 g",
        ]))
        #expect(NutritionLabelText.isLikelyNutritionLabel([
            "Пищевая ценность", "Энергетическая ценность 250 ккал", "Белки 3 г", "Жиры 8 г",
        ]))
        #expect(NutritionLabelText.isLikelyNutritionLabel([
            "营养成分表", "能量 | 1046千焦 | 12%", "蛋白质 | 3.0克 | 5%", "脂肪 | 8.0克 | 13%",
        ]))
    }

    @Test
    func ignoresPhotosWithoutANutritionPanel() {
        #expect(!NutritionLabelText.isLikelyNutritionLabel([]))
        // A kitchen scale and a receipt have numbers but no energy amount.
        #expect(!NutritionLabelText.isLikelyNutritionLabel(["Tare", "0 g", "235 g", "Batt 80%"]))
        #expect(!NutritionLabelText.isLikelyNutritionLabel(["Total 12.50", "Tax 1.00", "Cash 20.00"]))
        // "cal" inside "Calcium" is not an energy unit.
        #expect(!NutritionLabelText.isLikelyNutritionLabel(["Calcium 20mg", "Iron 2mg", "Potassium 240mg"]))
        // An energy amount alone is a front-of-pack claim, not a panel.
        #expect(!NutritionLabelText.isLikelyNutritionLabel(["Only 99 kcal", "Crunchy oat bar"]))
    }

    @Test
    func promptSectionListsEachLabelPhotoWithTheReadingInstruction() throws {
        #expect(NutritionLabelText.promptSection(for: []) == nil)
        #expect(NutritionLabelText.promptSection(for: [NutritionLabelPhotoText(photoNumber: 1, lines: [])]) == nil)

        #expect(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 1, lines: ["Nutrition Facts", "Ingredients: oats, honey"]),
        ]) == nil)

        let section = try #require(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 2, lines: [
                "Nutrition Facts", "Calories | 230", "Ingredients: oats, honey", "Total Fat 8g | 10%",
            ]),
        ]))
        #expect(section.hasPrefix("NUTRITION LABEL TEXT\n"))
        // Rows without a number stay out of the small on-device context.
        #expect(section.contains("Photo 2:\nCalories | 230\nTotal Fat 8g | 10%\n"))
        #expect(!section.contains("Nutrition Facts"))
        #expect(!section.contains("Ingredients"))
        #expect(section.contains("Prefer these recognized numbers over digits read from the image."))
        #expect(section.contains("which column is per serving and which is per 100 g"))
    }

    @Test
    func promptSectionStaysWithinTheLineAndCharacterBudget() throws {
        let manyRows = (1...100).map { "Row \($0) | 1 g" }
        let rowSection = try #require(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 1, lines: manyRows),
        ]))
        #expect(rowSection.contains("\nRow \(NutritionLabelText.maxLinesPerPhoto) | 1 g\n"))
        #expect(!rowSection.contains("Row \(NutritionLabelText.maxLinesPerPhoto + 1) |"))

        let longRow = String(repeating: "x", count: 99) + "1"
        let longSection = try #require(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 1, lines: Array(repeating: longRow, count: 30)),
            NutritionLabelPhotoText(photoNumber: 2, lines: ["Calories | 230"]),
        ]))
        let keptRows = longSection.components(separatedBy: "\n").filter { $0 == longRow }
        #expect(!keptRows.isEmpty)
        #expect(keptRows.count * (longRow.count + 1) <= NutritionLabelText.maxCharacters)
        #expect(keptRows.count < 30)
        // The budget is spent, so later photos add nothing.
        #expect(!longSection.contains("Photo 2:"))
    }

    @Test
    func onlyOnDeviceModelsGetTheLabelText() throws {
        let base = GeminiService.multiPhotoAnalysisPrompt(progressiveMeal: false, description: "half the bag")
        let section = try #require(NutritionLabelText.promptSection(for: [
            NutritionLabelPhotoText(photoNumber: 1, lines: ["Calories | 230", "Protein 3g"]),
        ]))
        let onDevice = NutritionLabelText.prompt(base, adding: section)
        #expect(onDevice == base + "\n\n" + section)
        #expect(NutritionLabelText.prompt(base, adding: nil) == base)

        #expect(AIProvider.appleIntelligence.runsOnDevice)
        #expect(AIProvider.gemma4Local.runsOnDevice)
        #expect(GeminiService.requestPrompt(base, onDevicePrompt: onDevice, for: .appleIntelligence) == onDevice)
        #expect(GeminiService.requestPrompt(base, onDevicePrompt: onDevice, for: .gemma4Local) == onDevice)
        #expect(GeminiService.requestPrompt(base, onDevicePrompt: nil, for: .gemma4Local) == base)
        for provider in AIProvider.allCases where provider != .appleIntelligence && provider != .gemma4Local {
            #expect(!provider.runsOnDevice, "\(provider)")
            #expect(GeminiService.requestPrompt(base, onDevicePrompt: onDevice, for: provider) == base, "\(provider)")
        }
    }
}
