import Foundation

/// One entry of a scanned ingredient label, for display only (#428).
nonisolated struct IngredientLabelItem: Equatable, Sendable {
    nonisolated struct Segment: Equatable, Sendable {
        let text: String
        /// Open Food Facts marks declared allergens with underscores, e.g. `_milk_`.
        let isAllergen: Bool
    }

    let segments: [Segment]
    /// True when the entry mentions one of the user's allergen sensitivities.
    let matchesSensitivity: Bool

    var text: String { segments.map(\.text).joined() }
}

/// Turns an Open Food Facts `ingredients_text` into a readable list (#428).
/// Display only: the stored label and the allergen check keep using the raw
/// text, and label entries never become macro-bearing meal ingredients.
/// Mirrors Android `IngredientLabelParser`; keep both in sync.
enum IngredientLabelParser {
    /// Longer labels stay a paragraph so nothing is cut off.
    static let maxItems = 60

    /// The label split into entries. Empty when it does not make a useful list
    /// (a single entry, or more than `maxItems`); show `segments(in:)` instead.
    static func items(from text: String, sensitivities: [String] = []) -> [IngredientLabelItem] {
        let parts = splitTopLevel(strippingHeading(from: text))
        guard parts.count > 1, parts.count <= maxItems else { return [] }
        return parts.map { part in
            let segments = segments(in: part)
            let plain = segments.map(\.text).joined()
            return IngredientLabelItem(
                segments: segments,
                matchesSensitivity: AllergenAnalysis.mentionsAny(of: sensitivities, in: plain)
            )
        }
    }

    /// Splits `_allergen_` markers into emphasized segments and drops stray underscores.
    static func segments(in text: String) -> [IngredientLabelItem.Segment] {
        var result: [IngredientLabelItem.Segment] = []
        var plain = ""
        var remaining = Substring(text)
        while let open = remaining.firstIndex(of: "_") {
            let afterOpen = remaining.index(after: open)
            guard let close = remaining[afterOpen...].firstIndex(of: "_") else { break }
            plain += remaining[..<open]
            let marked = String(remaining[afterOpen..<close])
            if marked.trimmingCharacters(in: .whitespaces).isEmpty {
                plain += marked
            } else {
                if !plain.isEmpty {
                    result.append(.init(text: plain, isAllergen: false))
                    plain = ""
                }
                result.append(.init(text: marked, isAllergen: true))
            }
            remaining = remaining[remaining.index(after: close)...]
        }
        plain += remaining.replacingOccurrences(of: "_", with: "")
        if !plain.isEmpty {
            result.append(.init(text: plain, isAllergen: false))
        }
        return result
    }

    /// Removes a leading "Ingredients:" style heading in the app's label languages.
    static func strippingHeading(from text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let colon = trimmed.firstIndex(where: { $0 == ":" || $0 == "：" }) else { return trimmed }
        let heading = trimmed[..<colon].trimmingCharacters(in: .whitespaces)
        guard !heading.isEmpty,
              heading.count <= 30,
              !heading.contains(where: { separators.contains($0) || openers.contains($0) })
        else { return trimmed }
        let lowered = heading.lowercased()
        let folded = lowered.folding(options: .diacriticInsensitive, locale: Locale(identifier: "en_US_POSIX"))
        guard headingStems.contains(where: { lowered.contains($0) || folded.contains($0) }) else { return trimmed }
        return String(trimmed[trimmed.index(after: colon)...]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Splits on commas and semicolons outside brackets, keeping decimal commas ("8,7%").
    static func splitTopLevel(_ text: String) -> [String] {
        let characters = Array(text)
        var parts: [String] = []
        var current = ""
        var depth = 0
        for (index, character) in characters.enumerated() {
            if openers.contains(character) {
                depth += 1
            } else if closers.contains(character) {
                depth = max(0, depth - 1)
            } else if depth == 0,
                      separators.contains(character),
                      !isDecimalComma(characters, at: index) {
                parts.append(current)
                current = ""
                continue
            }
            current.append(character)
        }
        parts.append(current)
        return parts.map(clean).filter { !$0.isEmpty }
    }

    private static let separators: Set<Character> = [",", ";", "，", "；", "、"]
    private static let openers: Set<Character> = ["(", "[", "{", "（", "［", "【"]
    private static let closers: Set<Character> = [")", "]", "}", "）", "］", "】"]
    private static let headingStems = [
        "ingred", "zutaten", "składnik", "skladnik", "slozeni", "icindekiler", "tərkib", "terkib",
        "состав", "склад", "配料", "原材料", "원재료", "المكونات", "सामग्री"
    ]

    private static func isDecimalComma(_ characters: [Character], at index: Int) -> Bool {
        guard characters[index] == ",", index > 0, index + 1 < characters.count else { return false }
        return isASCIIDigit(characters[index - 1]) && isASCIIDigit(characters[index + 1])
    }

    private static func isASCIIDigit(_ character: Character) -> Bool {
        character.isASCII && character.isWholeNumber
    }

    private static func clean(_ part: String) -> String {
        var value = part
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        while let last = value.last, last == "." || last == "。" || last == " " {
            value.removeLast()
        }
        return value
    }
}
