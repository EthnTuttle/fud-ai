import SwiftUI

struct FoodProductMetadataSection: View {
    let metadata: FoodProductMetadata
    /// Ingredient label entries that mention one of these are highlighted (#428).
    var allergenSensitivities: [String] = []
    @AppStorage(ProductInfoSettings.isShownKey) private var isShown = true
    @AppStorage(ProductInfoSettings.hiddenFieldsKey) private var hiddenFieldsRaw = ""

    private var visibleFields: [ProductInfoField] {
        ProductInfoSettings.visibleFields(
            for: metadata,
            isShown: isShown,
            hiddenFields: ProductInfoSettings.hiddenFields(from: hiddenFieldsRaw)
        )
    }

    var body: some View {
        let fields = visibleFields
        if !fields.isEmpty {
            Section("Product Information") {
                ForEach(fields) { field in
                    row(for: field)
                }
            }
        }
    }

    @ViewBuilder
    private func row(for field: ProductInfoField) -> some View {
        switch field {
        case .barcode:
            ProductMetadataRow(title: field.title, value: metadata.barcode)
        case .package:
            if let value = metadata.packageQuantity {
                ProductMetadataRow(title: field.title, value: value)
            }
        case .nutriScore:
            if let value = metadata.nutriScore {
                ProductMetadataRow(title: field.title, value: value)
            }
        case .novaGroup:
            if let value = metadata.novaGroup {
                ProductMetadataRow(title: field.title, value: "\(value)")
            }
        case .ecoScore:
            if let value = metadata.ecoScore {
                ProductMetadataRow(title: field.title, value: value)
            }
        case .allergens:
            ProductMetadataRow(title: field.title, value: metadata.allergens.formatted())
        case .traces:
            ProductMetadataRow(title: field.title, value: metadata.traces.formatted())
        case .labels:
            ProductMetadataRow(title: field.title, value: metadata.labels.formatted())
        case .categories:
            ProductMetadataRow(title: field.title, value: metadata.categories.formatted())
        case .ingredientLabel:
            if let ingredients = metadata.ingredientsText {
                IngredientLabelView(
                    title: field.title,
                    text: ingredients,
                    sensitivities: allergenSensitivities
                )
            }
        }
    }
}

extension ProductInfoField {
    var title: LocalizedStringKey {
        switch self {
        case .barcode: "Barcode"
        case .package: "Package"
        case .nutriScore: "Nutri-Score"
        case .novaGroup: "NOVA Group"
        case .ecoScore: "Eco-Score"
        case .allergens: "Allergens"
        case .traces: "May Contain"
        case .labels: "Labels"
        case .categories: "Categories"
        case .ingredientLabel: "Ingredient Label"
        }
    }
}

/// The scanned ingredient label as a readable list (#428), display only. Entries
/// Open Food Facts marks as allergens are bold; entries matching the user's
/// allergen sensitivities get a warning icon. Labels that do not split into a
/// list stay a paragraph.
private struct IngredientLabelView: View {
    let title: LocalizedStringKey
    let text: String
    let sensitivities: [String]

    var body: some View {
        let items = IngredientLabelParser.items(from: text, sensitivities: sensitivities)
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            if items.isEmpty {
                Text(Self.attributed(IngredientLabelParser.segments(in: text.trimmingCharacters(in: .whitespacesAndNewlines))))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    row(for: item)
                }
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func row(for item: IngredientLabelItem) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            if item.matchesSensitivity {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
            } else {
                Text(verbatim: "•")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Text(Self.attributed(item.segments))
                .font(item.matchesSensitivity ? .subheadline.weight(.semibold) : .subheadline)
                .foregroundStyle(item.matchesSensitivity ? .primary : .secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            item.matchesSensitivity
                ? "\(AllergenAssessment.possibleAllergen.displayName): \(item.text)"
                : item.text
        )
    }

    private static func attributed(_ segments: [IngredientLabelItem.Segment]) -> AttributedString {
        var result = AttributedString()
        for segment in segments {
            var part = AttributedString(segment.text)
            if segment.isAllergen {
                part.inlinePresentationIntent = .stronglyEmphasized
                part.foregroundColor = .primary
            }
            result += part
        }
        return result
    }
}

private struct ProductMetadataRow: View {
    let title: LocalizedStringKey
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
            Spacer(minLength: 12)
            Text(value)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }
}
