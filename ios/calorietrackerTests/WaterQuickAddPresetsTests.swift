import Foundation
import Testing
@testable import calorietracker

struct WaterQuickAddPresetsTests {
    @Test(arguments: [nil, "", "garbage", "250,500", "250,500,750,1000", "0,500,750", "250,500,5001", "250,abc,750"])
    func invalidStorageFallsBackToDefaults(raw: String?) {
        #expect(WaterQuickAddPresets.decode(raw) == [250, 500, 750])
    }

    @Test func customPresetsRoundTripSmallestFirst() {
        let encoded = WaterQuickAddPresets.encode([750, 200, 500])
        #expect(encoded == "200,500,750")
        #expect(WaterQuickAddPresets.decode(encoded) == [200, 500, 750])
        #expect(WaterQuickAddPresets.decode(" 330 , 200,200 ") == [200, 200, 330])
    }

    @Test func editingTextUsesPlainDigitsInTheUsersUnit() {
        #expect(WaterQuickAddPresets.editingText(forMilliliters: 1_000, unit: .milliliters) == "1000")
        #expect(WaterQuickAddPresets.editingText(forMilliliters: 237, unit: .fluidOunces) == "8")
        #expect(WaterQuickAddPresets.editingText(forMilliliters: 250, unit: .fluidOunces) == "8.5")
    }

    @Test func editedAmountsConvertToMillilitersWithinTheAllowedRange() {
        #expect(WaterQuickAddPresets.milliliters(fromEditingText: "200", unit: .milliliters) == 200)
        #expect(WaterQuickAddPresets.milliliters(fromEditingText: "8", unit: .fluidOunces) == 237)
        #expect(WaterQuickAddPresets.milliliters(fromEditingText: "8,5", unit: .fluidOunces) == 251)
        #expect(WaterQuickAddPresets.milliliters(fromEditingText: "", unit: .milliliters) == nil)
        #expect(WaterQuickAddPresets.milliliters(fromEditingText: "0", unit: .milliliters) == nil)
        #expect(WaterQuickAddPresets.milliliters(fromEditingText: "5001", unit: .milliliters) == nil)
        #expect(WaterQuickAddPresets.milliliters(fromEditingText: "170", unit: .fluidOunces) == nil)
    }

    @Test func inputFilterKeepsDigitsAndOneDecimalForFluidOunces() {
        #expect(WaterQuickAddPresets.filteredInput("2a5.0", unit: .milliliters) == "250")
        #expect(WaterQuickAddPresets.filteredInput("8,57", unit: .fluidOunces) == "8.5")
    }

    @Test func summaryListsAmountsInTheUsersUnit() {
        #expect(WaterQuickAddPresets.summary([200, 400, 600], unit: .milliliters) == "200 · 400 · 600 ml")
    }
}
