import SwiftUI

struct FoodProductMetadataSection: View {
    let metadata: FoodProductMetadata
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
                VStack(alignment: .leading, spacing: 6) {
                    Text(field.title)
                        .font(.subheadline.weight(.semibold))
                    Text(ingredients)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
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
