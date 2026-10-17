import SwiftUI

/// Settings > App Preferences > Product Information (#427): turn off the barcode
/// product details block or choose which Open Food Facts fields it shows.
struct ProductInfoSettingsView: View {
    @AppStorage(ProductInfoSettings.isShownKey) private var isShown = true
    @AppStorage(ProductInfoSettings.hiddenFieldsKey) private var hiddenFieldsRaw = ""

    var body: some View {
        Form {
            Section {
                Toggle("Show Product Information", isOn: $isShown)
                    .tint(AppColors.calorie)
            } footer: {
                Text("Shown at the end of the review and edit screens for barcode-scanned foods. Allergen Check is not affected and always appears when you have allergen sensitivities set.")
            }

            if isShown {
                Section {
                    ForEach(ProductInfoField.allCases) { field in
                        Toggle(isOn: binding(for: field)) {
                            Text(field.title)
                        }
                        .tint(AppColors.calorie)
                    }
                } header: {
                    Text("Details to Show")
                }
            }
        }
        .navigationTitle("Product Information")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func binding(for field: ProductInfoField) -> Binding<Bool> {
        Binding(
            get: { !ProductInfoSettings.hiddenFields(from: hiddenFieldsRaw).contains(field) },
            set: { isVisible in
                var hidden = ProductInfoSettings.hiddenFields(from: hiddenFieldsRaw)
                if isVisible {
                    hidden.remove(field)
                } else {
                    hidden.insert(field)
                }
                hiddenFieldsRaw = ProductInfoSettings.storageValue(for: hidden)
            }
        )
    }
}
