import Foundation
import Testing
@testable import calorietracker

@MainActor
struct SiriWeightParsingTests {
    @Test func latinUnitWordsPickTheUnit() throws {
        let kilograms = try SiriLoggingService.parseWeight("75 kilograms")
        #expect(kilograms.kg == 75)
        #expect(kilograms.unitName == "kilograms")

        let pounds = try SiriLoggingService.parseWeight("165 lb")
        #expect(abs(pounds.kg - 165 / 2.20462) < 0.0001)
        #expect(pounds.unitName == "pounds")
    }

    @Test func cyrillicUnitWordsFromTheRussianAndUkrainianHintsAreRecognised() throws {
        #expect(try SiriLoggingService.parseWeight("75 кг").unitName == "kilograms")
        #expect(try SiriLoggingService.parseWeight("75 килограммов").unitName == "kilograms")
        #expect(try SiriLoggingService.parseWeight("75 кілограмів").unitName == "kilograms")

        let russianPounds = try SiriLoggingService.parseWeight("165 фунтов")
        #expect(russianPounds.unitName == "pounds")
        #expect(abs(russianPounds.kg - 165 / 2.20462) < 0.0001)
        #expect(try SiriLoggingService.parseWeight("165 фунтів").unitName == "pounds")
    }
}
