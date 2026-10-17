import Foundation
import Testing
@testable import calorietracker

/// Checks that notification, error, extension and Siri text ships in every app
/// language, and that counted strings follow each language's plural rules.
@MainActor
struct LocalizationCatalogTests {
    static let appLanguages = [
        "ar", "az", "cs", "de", "es", "fr", "hi", "it", "ja", "ko",
        "nl", "pl", "pt-BR", "ro", "ru", "tr", "uk", "zh-Hans",
    ]

    /// Every notification title and body NotificationManager can schedule.
    static let notificationKeys = [
        "Breakfast Time", "Don't forget to log your breakfast!",
        "Lunch Time", "Snap a photo to keep tracking!",
        "Dinner Time", "Log your dinner to stay on track!",
        "Time to Hydrate", "Have some water and log it in Fud AI.",
        "Fasting Goal Reached", "You reached your %@ fasting goal. End the fast when you're ready.",
        "Don't Break Your %lld-Day Streak!", "Log something before the day ends.",
        "Daily Summary", "You ate %lld of %lld kcal today. %lld remaining!",
        "Time to Weigh In", "Log today's weight to keep your progress chart accurate.",
        "Body Fat Check-In", "Log today's body fat % to track your composition trend.",
        "Update Available", "Fud AI %@ is ready. Tap to update.",
        "Fud AI is live on Product Hunt 🚀", "We just launched! Tap to vote and help more people find Fud AI.",
    ]

    // MARK: - Compiled bundles

    @Test func notificationTextIsTranslatedInEveryLanguage() throws {
        for language in Self.appLanguages {
            let bundle = try Self.languageBundle(language, in: .main)
            for key in Self.notificationKeys {
                #expect(Self.hasTranslation(key, in: bundle), "\(language) is missing \(key)")
            }
        }
    }

    @Test func streakReminderFollowsPluralRules() throws {
        let key = "Don't Break Your %lld-Day Streak!"
        #expect(try Self.format(key, "ru", 1) == "Не прерывайте серию: 1 день подряд!")
        #expect(try Self.format(key, "ru", 3) == "Не прерывайте серию: 3 дня подряд!")
        #expect(try Self.format(key, "ru", 5) == "Не прерывайте серию: 5 дней подряд!")
        #expect(try Self.format(key, "pl", 22) == "Nie przerywaj serii – 22 dni z rzędu!")
        #expect(try Self.format(key, "es", 1) == "¡No pierdas tu racha de 1 día!")
        #expect(try Self.format(key, "es", 7) == "¡No pierdas tu racha de 7 días!")
    }

    @Test func dailySummaryAgreesWithTheRemainingCount() throws {
        let key = "You ate %lld of %lld kcal today. %lld remaining!"
        #expect(try Self.format(key, "ru", 900, 1_000, 21).hasSuffix("Осталась 21 ккал!"))
        #expect(try Self.format(key, "ru", 900, 1_000, 100).hasSuffix("Осталось 100 ккал!"))
        #expect(try Self.format(key, "cs", 900, 1_000, 3).hasSuffix("Zbývají 3 kcal!"))
        #expect(try Self.format(key, "es", 900, 1_000, 1).hasSuffix("¡Te queda 1!"))
        #expect(try Self.format(key, "es", 900, 1_000, 100).hasSuffix("¡Te quedan 100!"))
        #expect(try Self.format(key, "de", 900, 1_000, 100) == "Du hast heute 900 von 1.000 kcal gegessen. Noch 100 übrig!")
    }

    @Test func shareExtensionAlertIsTranslatedInEveryLanguage() throws {
        let share = try Self.embeddedExtension("calorietrackerShare")
        for language in Self.appLanguages {
            let bundle = try Self.languageBundle(language, in: share)
            for key in ["Error", "Unable to process the shared image.", "OK"] {
                #expect(Self.hasTranslation(key, in: bundle), "\(language) is missing \(key)")
            }
        }
    }

    // MARK: - Error descriptions

    @Test func errorDescriptionsKeepTheirDetails() {
        #expect(DiaryImportError.invalidDate("2026-13-40").errorDescription?.contains("2026-13-40") == true)
        #expect(DiaryImportError.invalidMeal("brunch").errorDescription?.contains("brunch") == true)
        #expect(DiaryImportError.invalidEntry("Rice").errorDescription?.contains("Rice") == true)
        #expect(ChatService.ChatError.apiError("quota hit").errorDescription?.contains("quota hit") == true)
        #expect(SpeechService.SpeechError.apiError("bad audio").errorDescription?.contains("bad audio") == true)
        #expect(HostedAIServiceError.serverError("Plan expired").errorDescription == "Plan expired")
        #expect(LiveSocketError.closed(code: 1000, reason: "bye").errorDescription == "bye")
        // A close code is an identifier: it must not pick up digit grouping like "4,402".
        #expect(LiveSocketError.closed(code: 4402, reason: nil).errorDescription?.contains("4402") == true)
        #expect(LiveSocketError.refused(statusCode: 402).errorDescription?.contains("402") == true)
    }

    // MARK: - Source catalogs

    @Test(.enabled(if: LocalizationCatalogTests.sourceCatalogsAvailable))
    func extensionCatalogsCoverEveryAppLanguage() throws {
        let catalogs = [
            "FudAIWidgets/Localizable.xcstrings",
            "FudAIWatchApp/Localizable.xcstrings",
            "FudAIWatchWidgets/Localizable.xcstrings",
            "calorietrackerShare/Localizable.xcstrings",
        ]
        for catalog in catalogs {
            let strings = try Self.sourceStrings(catalog)
            #expect(!strings.isEmpty, "\(catalog) has no strings")
            for (key, entry) in strings {
                let localizations = entry["localizations"] as? [String: Any] ?? [:]
                for language in Self.appLanguages {
                    let forms = Self.translatedForms(localizations[language])
                    #expect(!forms.isEmpty, "\(catalog): \(language) is missing \(key)")
                    for form in forms {
                        #expect(
                            Self.formatArguments(form) == Self.formatArguments(key),
                            "\(catalog): \(language) \(form) does not match the arguments of \(key)"
                        )
                    }
                }
            }
        }
    }

    @Test(.enabled(if: LocalizationCatalogTests.sourceCatalogsAvailable))
    func appShortcutPhrasesNameTheAppInEveryLanguage() throws {
        let strings = try Self.sourceStrings("calorietracker/AppShortcuts.xcstrings")
        #expect(strings.count >= 7)
        for language in Self.appLanguages + ["en"] {
            var seen = Set<String>()
            for (key, entry) in strings {
                let localizations = entry["localizations"] as? [String: Any] ?? [:]
                let set = (localizations[language] as? [String: Any])?["stringSet"] as? [String: Any]
                let phrases = set?["values"] as? [String] ?? []
                #expect(!phrases.isEmpty, "\(language) has no phrases for \(key)")
                for phrase in phrases {
                    // Siri rejects a phrase that doesn't name the app.
                    #expect(phrase.components(separatedBy: "${applicationName}").count == 2, "\(language): \(phrase)")
                    #expect(seen.insert(phrase).inserted, "\(language) repeats \(phrase)")
                }
            }
        }
    }

    // MARK: - Helpers

    nonisolated static let iosSourceRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // calorietrackerTests
        .deletingLastPathComponent() // ios

    /// The source catalogs exist only when the tests run next to the checkout.
    nonisolated static var sourceCatalogsAvailable: Bool {
        FileManager.default.fileExists(atPath: iosSourceRoot.appendingPathComponent("FudAIWidgets/Localizable.xcstrings").path)
    }

    static func sourceStrings(_ relativePath: String) throws -> [String: [String: Any]] {
        let data = try Data(contentsOf: iosSourceRoot.appendingPathComponent(relativePath))
        let catalog = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try #require(catalog["strings"] as? [String: [String: Any]])
    }

    /// The translated text of one localization: a single value, or every plural form.
    static func translatedForms(_ localization: Any?) -> [String] {
        guard let localization = localization as? [String: Any] else { return [] }
        if let unit = localization["stringUnit"] as? [String: Any] {
            guard unit["state"] as? String == "translated", let value = unit["value"] as? String else { return [] }
            return [value]
        }
        let plural = (localization["variations"] as? [String: Any])?["plural"] as? [String: Any] ?? [:]
        return plural.values.flatMap { translatedForms($0) }
    }

    /// The format arguments in a string by position, ignoring how they are numbered.
    static func formatArguments(_ text: String) -> [Int: String] {
        let regex = try! NSRegularExpression(pattern: #"%(?:(\d+)\$)?(ll|l)?([@d])"#)
        var arguments: [Int: String] = [:]
        var next = 1
        for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            let position = Range(match.range(at: 1), in: text).flatMap { Int(text[$0]) } ?? next
            let length = Range(match.range(at: 2), in: text).map { String(text[$0]) } ?? ""
            let conversion = Range(match.range(at: 3), in: text).map { String(text[$0]) } ?? ""
            arguments[position] = length + conversion
            next += 1
        }
        return arguments
    }

    static func embeddedExtension(_ name: String) throws -> Bundle {
        let plugIns = try #require(Bundle.main.builtInPlugInsURL)
        return try #require(Bundle(url: plugIns.appendingPathComponent("\(name).appex")))
    }

    static func languageBundle(_ language: String, in bundle: Bundle) throws -> Bundle {
        let path = try #require(bundle.path(forResource: language, ofType: "lproj"), "no \(language).lproj in \(bundle.bundlePath)")
        return try #require(Bundle(path: path))
    }

    static func hasTranslation(_ key: String, in bundle: Bundle) -> Bool {
        let missing = "\u{0}missing"
        return bundle.localizedString(forKey: key, value: missing, table: nil) != missing
    }

    /// Formats a key with integers the way String(localized:) does for that language.
    static func format(_ key: String, _ language: String, _ arguments: Int...) throws -> String {
        let bundle = try languageBundle(language, in: .main)
        let format = bundle.localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: Locale(identifier: language), arguments: arguments.map { Int64($0) as CVarArg })
    }
}
