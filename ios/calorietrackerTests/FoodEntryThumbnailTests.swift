import Foundation
import Testing
import UIKit
@testable import calorietracker

@MainActor
struct FoodEntryThumbnailTests {
    @Test func progressiveThumbnailUsesFinalMealPhotoAndKeepsGalleryChronological() {
        let entry = meal(first: "first.jpg", additional: ["second.jpg", "final.jpg"])
        #expect(entry.thumbnailPhotoCandidates.map(\.id) == [.filename("final.jpg"), .filename("first.jpg"), .filename("second.jpg")])
        #expect(entry.photoSources.map(\.id) == [.filename("first.jpg"), .filename("second.jpg"), .filename("final.jpg")])
        #expect(entry.allImageFilenames == ["first.jpg", "second.jpg", "final.jpg"])
    }

    @Test func regularMealKeepsFirstPhotoAsThumbnail() {
        let entry = meal(first: "first.jpg", additional: ["second.jpg"], progressive: false)
        #expect(entry.thumbnailPhotoCandidates.map(\.id) == [.filename("first.jpg"), .filename("second.jpg")])
    }

    @Test func ingredientPhotosDoNotReplaceTheFinalMealThumbnail() {
        var entry = meal(first: "first.jpg", additional: ["final.jpg"])
        entry.ingredients = [MealIngredient(name: "Rice", grams: 100, calories: 100, protein: 2, carbs: 20, fat: 1, imageFilename: "ingredient.jpg")]
        #expect(entry.thumbnailPhotoCandidates.first?.id == .filename("final.jpg"))
        #expect(entry.photoSources.last?.id == .filename("ingredient.jpg"))
    }

    @Test func missingParentPhotosAllowIngredientFallbackAndEmptyMealsHaveNoCandidates() {
        var entry = meal()
        #expect(entry.thumbnailPhotoCandidates.isEmpty)
        entry.ingredients = [MealIngredient(name: "Rice", grams: 100, calories: 100, protein: 2, carbs: 20, fat: 1, imageFilename: "ingredient.jpg")]
        #expect(entry.thumbnailPhotoCandidates.map(\.id) == [.filename("ingredient.jpg")])
    }

    @Test func compactedLegacyCacheCannotImpersonateAMissingFile() {
        var entry = meal(first: "first.jpg", additional: ["missing.jpg", "final.jpg"])
        entry.additionalImageData = [Data("final-photo-only".utf8)]
        #expect(entry.photoSources.dropFirst().allSatisfy { $0.data == nil })
        #expect(entry.thumbnailPhotoCandidates.first?.id == .filename("final.jpg"))
    }

    @Test func inMemoryProgressivePhotosUseFinalImageAndOpenItsGalleryPage() async throws {
        var entry = meal()
        entry.imageData = imageData(.red)
        entry.additionalImageData = [imageData(.green), imageData(.blue)]
        let loaded = await FoodEntryPhotoLoader.thumbnail(from: entry.thumbnailPhotoCandidates, maxPixelSize: 32)
        #expect(loaded?.id == .additionalData(1))
        let preview = try #require(await FoodEntryPhotoLoader.viewerPreview(for: entry, matching: loaded?.id))
        #expect(preview.images.count == 3)
        #expect(preview.initialIndex == 2)
    }

    @Test func viewerMatchesFinalThumbnailAfterAnEarlierPhotoFailsToLoad() async throws {
        let first = try #require(FoodImageStore.shared.store(data: imageData(.red), for: UUID()))
        let final = try #require(FoodImageStore.shared.store(data: imageData(.blue), for: UUID()))
        defer { [first, final].forEach { FoodImageStore.shared.delete(filename: $0) } }
        let entry = meal(first: first, additional: ["missing-\(UUID()).jpg", final])
        let loaded = await FoodEntryPhotoLoader.thumbnail(from: entry.thumbnailPhotoCandidates, maxPixelSize: 32)
        #expect(loaded?.id == .filename(final))
        let preview = try #require(await FoodEntryPhotoLoader.viewerPreview(for: entry, matching: loaded?.id))
        #expect(preview.images.count == 2)
        #expect(preview.initialIndex == 1)
    }

    @Test func missingFinalPhotoFallsBackToTheFirstAvailableThumbnail() async throws {
        let first = try #require(FoodImageStore.shared.store(data: imageData(.red), for: UUID()))
        defer { FoodImageStore.shared.delete(filename: first) }
        let entry = meal(first: first, additional: ["missing-\(UUID()).jpg"])
        let loaded = await FoodEntryPhotoLoader.thumbnail(from: entry.thumbnailPhotoCandidates, maxPixelSize: 32)
        #expect(loaded?.id == .filename(first))
        let preview = try #require(await FoodEntryPhotoLoader.viewerPreview(for: entry, matching: loaded?.id))
        #expect(preview.images.count == 1)
        #expect(preview.initialIndex == 0)
    }

    private func meal(first: String? = nil, additional: [String] = [], progressive: Bool = true) -> FoodEntry {
        FoodEntry(name: "Lunch", calories: 450, protein: 25, carbs: 50, fat: 15,
                  imageFilename: first, additionalImageFilenames: additional,
                  source: .snapFood, progressiveMeal: progressive)
    }

    private func imageData(_ color: UIColor) -> Data {
        UIGraphicsImageRenderer(size: CGSize(width: 16, height: 16)).pngData { context in
            color.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 16, height: 16))
        }
    }
}
