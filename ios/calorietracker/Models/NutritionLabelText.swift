import CoreGraphics
import Foundation

/// Text that Vision read from one photo, as label rows top to bottom.
nonisolated struct NutritionLabelPhotoText: Equatable, Sendable {
    /// 1-based position of the photo in the request.
    let photoNumber: Int
    let lines: [String]
}

/// Turns on-device text recognition of a nutrition label into table rows that
/// go into the photo prompt for the on-device models. #486 reports that they
/// miss, misread or misassign label values, so they also get the numbers
/// Vision read. Pure, so the row assembly is unit tested;
/// `NutritionLabelTextRecognizer` runs Vision.
/// iOS only: Android has no on-device text recognizer without adding ML Kit
/// text recognition.
nonisolated enum NutritionLabelText {
    /// One line of text from Vision. `box` uses Vision's normalized
    /// coordinates: origin at the lower left, y grows upward.
    nonisolated struct Fragment: Equatable, Sendable {
        var text: String
        var box: CGRect
    }

    static let columnSeparator = " | "
    /// Photos read per request; later photos are sent without label text.
    static let maxPhotos = 4
    static let maxLinesPerPhoto = 60
    /// Keeps the label text well inside the 4,096-token context of the
    /// on-device models, next to the photo prompt, the photos and the reply,
    /// even for scripts that spend a token per character.
    static let maxCharacters = 1_200
    /// A label has several numeric rows; a scale reading or a price tag does not.
    static let minimumNumericLines = 3

    /// Groups fragments into rows from top to bottom. Each row's cells run in
    /// reading order and are joined with `columnSeparator`, so a nutrient name
    /// stays on the same line as its values. A unit that Vision read as its own
    /// fragment ("8" then "g") is joined to its number.
    static func lines(from fragments: [Fragment]) -> [String] {
        let kept = fragments.compactMap { fragment -> Fragment? in
            var fragment = fragment
            fragment.text = collapsingWhitespace(fragment.text)
            guard !fragment.text.isEmpty, fragment.box.height > 0 else { return nil }
            return fragment
        }
        // A tilted photo slants every row the same way. Leveling the rows keeps
        // a value at the far right on its nutrient's row.
        let skew = estimatedSkew(of: kept)
        let cells = kept.map { Cell(fragment: $0, level: level(of: $0, skew: skew)) }
        // Vision's y grows upward, so the top of the label has the largest level.
        let ordered = cells.sorted {
            $0.level != $1.level ? $0.level > $1.level : $0.fragment.box.minX < $1.fragment.box.minX
        }
        var rows: [Row] = []
        for cell in ordered {
            let match = rows.indices
                .compactMap { index in rows[index].distance(to: cell).map { (index: index, distance: $0) } }
                .min { $0.distance < $1.distance }
            if let match {
                rows[match.index].cells.append(cell)
            } else {
                rows.append(Row(cells: [cell]))
            }
        }
        return rows.map(\.line)
    }

    /// True when the rows read like a nutrition panel: an energy amount
    /// (kcal, kJ or calories) and several numeric rows.
    static func isLikelyNutritionLabel(_ lines: [String]) -> Bool {
        guard lines.filter(hasNumber).count >= minimumNumericLines else { return false }
        return lines.contains { line in
            line.range(of: energyMarkerPattern, options: [.regularExpression, .caseInsensitive]) != nil
                || cjkEnergyUnits.contains { line.contains($0) }
        }
    }

    /// The prompt section for the on-device models, or nil when no photo has
    /// label text. Only rows with a number are sent: the row assembly keeps
    /// each nutrient's name on its value's row, and titles, ingredient lists
    /// and marketing copy would only spend the small context. Keeps at most
    /// `maxLinesPerPhoto` rows of a photo and stops at the first row past
    /// `maxCharacters`, so the text never skips a row.
    static func promptSection(for photos: [NutritionLabelPhotoText]) -> String? {
        var remaining = maxCharacters
        var blocks: [String] = []
        var budgetSpent = false
        for photo in photos where !budgetSpent {
            var kept: [String] = []
            for line in photo.lines.filter(hasNumber).prefix(maxLinesPerPhoto) {
                guard line.count < remaining else {
                    budgetSpent = true
                    break
                }
                kept.append(line)
                remaining -= line.count + 1
            }
            guard !kept.isEmpty else { continue }
            blocks.append("Photo \(photo.photoNumber):\n" + kept.joined(separator: "\n"))
        }
        guard !blocks.isEmpty else { return nil }
        return """
        NUTRITION LABEL TEXT
        On-device text recognition read these lines from the nutrition label, top to bottom. "|" separates the label's columns.
        \(blocks.joined(separator: "\n"))
        Prefer these recognized numbers over digits read from the image. Give each value to the nutrient named on the same line. Use the image to tell which column is per serving and which is per 100 g or 100 ml, and to correct clear recognition mistakes, such as "g" read as "9" or "O" read as "0". When the label lists a serving size and the user's note gives no other amount, analyze one serving.
        """
    }

    /// `prompt` followed by the label section, or `prompt` unchanged without one.
    static func prompt(_ prompt: String, adding section: String?) -> String {
        guard let section else { return prompt }
        return prompt + "\n\n" + section
    }

    // MARK: - Rows

    nonisolated private struct Cell {
        let fragment: Fragment
        /// Vertical center after leveling the photo's tilt.
        let level: CGFloat
    }

    nonisolated private struct Row {
        var cells: [Cell]

        /// How far `cell` sits from the row's horizontally nearest cell, or nil
        /// when that is more than half the smaller text height. Comparing with
        /// the nearest cell lets a row follow tilt that leveling left over.
        func distance(to cell: Cell) -> CGFloat? {
            let midX = cell.fragment.box.midX
            guard let nearest = cells.min(by: {
                abs($0.fragment.box.midX - midX) < abs($1.fragment.box.midX - midX)
            }) else { return nil }
            let distance = abs(nearest.level - cell.level)
            return distance <= min(nearest.fragment.box.height, cell.fragment.box.height) / 2 ? distance : nil
        }

        var line: String {
            let fragments = cells.map(\.fragment)
            let ordered = NutritionLabelText.isRightToLeft(fragments.map(\.text).joined())
                ? fragments.sorted { $0.box.maxX > $1.box.maxX }
                : fragments.sorted { $0.box.minX < $1.box.minX }
            var parts: [String] = []
            for fragment in ordered {
                if let last = parts.last, last.last?.isNumber == true, NutritionLabelText.isBareUnit(fragment.text) {
                    parts[parts.count - 1] = last + " " + fragment.text
                } else {
                    parts.append(fragment.text)
                }
            }
            return parts.joined(separator: NutritionLabelText.columnSeparator)
        }
    }

    // MARK: - Tilt

    /// Steepest tilt tried, as rise per unit of x; about 15 degrees on a portrait photo.
    private static let maxSkew: CGFloat = 0.2
    private static let skewStep: CGFloat = 0.0025

    /// Vertical center after removing `skew`.
    private static func level(of fragment: Fragment, skew: CGFloat) -> CGFloat {
        fragment.box.midY - skew * fragment.box.midX
    }

    /// The tilt that best lines up side-by-side fragments into rows. Vision's
    /// own line angles are unreliable (many lines come back level), so this
    /// searches the photo's geometry instead. Each pair scores 1 when its
    /// leveled centers match, falling to 0 at half the smaller text height.
    /// Ties keep the smaller tilt, and a label with one column stays level.
    private static func estimatedSkew(of fragments: [Fragment]) -> CGFloat {
        // Fragments stacked in the same column never share a row, and pairs
        // too far apart vertically cannot line up at any tilt in range.
        var pairs: [(dx: CGFloat, dy: CGFloat, tolerance: CGFloat)] = []
        for (index, first) in fragments.enumerated() {
            for second in fragments[(index + 1)...] {
                guard first.box.maxX <= second.box.minX || second.box.maxX <= first.box.minX else { continue }
                let dx = second.box.midX - first.box.midX
                let dy = second.box.midY - first.box.midY
                let tolerance = min(first.box.height, second.box.height) / 2
                if abs(dy) < maxSkew * abs(dx) + tolerance { pairs.append((dx, dy, tolerance)) }
            }
        }
        func score(_ skew: CGFloat) -> CGFloat {
            pairs.reduce(0) { total, pair in
                let gap = abs(pair.dy - skew * pair.dx)
                return gap < pair.tolerance ? total + 1 - gap / pair.tolerance : total
            }
        }
        var best = (skew: CGFloat(0), score: score(0))
        for step in 1...Int((maxSkew / skewStep).rounded()) {
            for skew in [CGFloat(step) * skewStep, -CGFloat(step) * skewStep] {
                let value = score(skew)
                if value > best.score + 1e-9 { best = (skew, value) }
            }
        }
        return best.skew
    }

    // MARK: - Helpers

    private static let energyMarkerPattern = #"(?<!\p{L})(kcal|kj|cal|calories?|ккал|кдж)(?!\p{L})"#
    private static let cjkEnergyUnits = ["千卡", "千焦", "大卡"]
    private static let bareUnits: Set<String> = ["g", "mg", "mcg", "µg", "μg", "kcal", "kj", "cal", "ml", "iu", "%"]

    private static func hasNumber(_ line: String) -> Bool {
        line.contains(where: \.isNumber)
    }

    private static func isBareUnit(_ text: String) -> Bool {
        bareUnits.contains(text.lowercased())
    }

    /// Arabic or Hebrew rows read right to left. A row counts as right to left
    /// when it has more of those letters than Latin ones, so "الطاقة 250 kcal"
    /// is right to left and a bilingual "Energy 250 kcal الطاقة" is not.
    private static func isRightToLeft(_ text: String) -> Bool {
        var rightToLeft = 0
        var latin = 0
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0x0590...0x08FF, 0xFB1D...0xFDFF, 0xFE70...0xFEFF:
                if scalar.properties.isAlphabetic { rightToLeft += 1 }
            case 0x41...0x5A, 0x61...0x7A:
                latin += 1
            default:
                break
            }
        }
        return rightToLeft > latin
    }

    private static func collapsingWhitespace(_ text: String) -> String {
        text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
