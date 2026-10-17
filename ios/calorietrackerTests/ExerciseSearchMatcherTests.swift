import Foundation
import Testing
@testable import calorietracker

@Suite struct ExerciseSearchMatcherTests {
    private let tricepsPushdown = ExerciseLibraryItem(
        id: "Triceps_Pushdown",
        name: "Triceps Pushdown",
        rawLevel: "Beginner",
        force: "Push",
        mechanic: "Isolation",
        category: "Strength",
        rawEquipment: "Cable",
        primaryMuscles: ["Triceps"],
        instructions: ["Attach a bar to a high pulley."]
    )

    @Test func contiguousQueryMatches() {
        #expect(ExerciseSearchMatcher.matches(tricepsPushdown, query: "triceps pushdown"))
    }

    @Test func tokenizedNonContiguousQueryMatches() {
        #expect(ExerciseSearchMatcher.matches(tricepsPushdown, query: "triceps cable pushdown"))
    }

    @Test func aliasQueryMatches() {
        #expect(ExerciseSearchMatcher.relevance(of: tricepsPushdown, query: "cable pushdown") == 0)
    }

    @Test func missingTokenFails() {
        #expect(!ExerciseSearchMatcher.matches(tricepsPushdown, query: "triceps rope"))
    }

    @Test func emptyQueryMatchesEverything() {
        #expect(ExerciseSearchMatcher.relevance(of: tricepsPushdown, query: "   ") == 0)
    }

    @Test func wordPrefixMatchesPluralMuscleNames() {
        #expect(ExerciseSearchMatcher.matches(tricepsPushdown, query: "tricep"))
    }

    @Test func instructionsAreNotSearched() {
        #expect(!ExerciseSearchMatcher.matches(tricepsPushdown, query: "pulley"))
    }

    @Test func tokensOnlyMatchAtWordStarts() {
        #expect(!ExerciseSearchMatcher.matches(tricepsPushdown, query: "down"))
    }

    @Test func hyphenatedAndJoinedSpellingsMatchExactly() {
        let pushUp = ExerciseLibraryItem(id: "Push_Up", name: "Push-Up", category: "Strength")
        #expect(ExerciseSearchMatcher.relevance(of: pushUp, query: "pushup") == 0)
        #expect(ExerciseSearchMatcher.relevance(of: pushUp, query: "push up") == 0)
    }

    @Test func relevanceTiersOrderExactPrefixPhraseWordsAndMetadata() {
        let exact = ExerciseLibraryItem(id: "a", name: "Chest Press")
        let prefix = ExerciseLibraryItem(id: "b", name: "Chest Press Machine")
        let phrase = ExerciseLibraryItem(id: "c", name: "Cable Chest Press")
        let words = ExerciseLibraryItem(id: "d", name: "Press Against Chest Wall")
        let metadata = ExerciseLibraryItem(id: "e", name: "Barbell Bench Press", primaryMuscles: ["chest"])

        #expect(ExerciseSearchMatcher.relevance(of: exact, query: "Chest press") == 0)
        #expect(ExerciseSearchMatcher.relevance(of: prefix, query: "Chest press") == 1)
        #expect(ExerciseSearchMatcher.relevance(of: phrase, query: "Chest press") == 2)
        #expect(ExerciseSearchMatcher.relevance(of: words, query: "Chest press") == 3)
        #expect(ExerciseSearchMatcher.relevance(of: metadata, query: "Chest press") == 4)
    }

    @Test func nonASCIIQueriesMatchInsideUnspacedNames() {
        let item = ExerciseLibraryItem(id: "user_exercise_ja", name: "ベンチプレス")
        #expect(ExerciseSearchMatcher.matches(item, query: "プレス"))
    }

    /// Reporter's example (#497): "Chest press" returned unrelated presses whose
    /// instructions merely mention the chest, in alphabetical order.
    @Test func chestPressRanksNameMatchesFirstAndSkipsInstructionOnlyHits() {
        let items = [
            ExerciseLibraryItem(
                id: "Alternating_Cable_Shoulder_Press",
                name: "Alternating Cable Shoulder Press",
                rawEquipment: "cable",
                primaryMuscles: ["shoulders"],
                secondaryMuscles: ["triceps"],
                instructions: ["Keeping your head and chest up, extend through the elbow to press one side directly over head."]
            ),
            ExerciseLibraryItem(
                id: "Barbell_Bench_Press_-_Medium_Grip",
                name: "Barbell Bench Press - Medium Grip",
                rawEquipment: "barbell",
                primaryMuscles: ["chest"],
                secondaryMuscles: ["shoulders", "triceps"]
            ),
            ExerciseLibraryItem(
                id: "Leverage_Chest_Press",
                name: "Leverage Chest Press",
                rawEquipment: "machine",
                primaryMuscles: ["chest"]
            ),
            ExerciseLibraryItem(
                id: "Cable_Chest_Press",
                name: "Cable Chest Press",
                rawEquipment: "cable",
                primaryMuscles: ["chest"]
            ),
        ]
        let service = ExerciseLibraryService(exercises: items)

        let results = service.filtered(
            levels: [],
            rawEquipment: [],
            primaryMuscles: [],
            secondaryMuscles: [],
            forces: [],
            mechanics: [],
            categories: [],
            sort: .name,
            searchText: "Chest press"
        )

        #expect(results.map(\.name) == [
            "Cable Chest Press",
            "Leverage Chest Press",
            "Barbell Bench Press - Medium Grip",
        ])
    }

    @Test func emptyQueryKeepsChosenSortOrder() {
        let service = ExerciseLibraryService(exercises: [
            ExerciseLibraryItem(id: "b", name: "Bench Press"),
            ExerciseLibraryItem(id: "a", name: "Air Bike"),
        ])

        let results = service.filtered(
            levels: [],
            rawEquipment: [],
            primaryMuscles: [],
            secondaryMuscles: [],
            forces: [],
            mechanics: [],
            categories: [],
            sort: .name,
            searchText: ""
        )

        #expect(results.map(\.name) == ["Air Bike", "Bench Press"])
    }

    @Test func userExerciseNamesAreSearchable() {
        var draft = UserExerciseDraft()
        draft.name = "Landmine Chest Press"
        let item = draft.libraryItem(id: UserExercise.newID(), imagePaths: [])

        #expect(ExerciseSearchMatcher.relevance(of: item, query: "chest press") == 2)
        #expect(ExerciseSearchMatcher.relevance(of: item, query: "landmine") == 1)
    }

    @Test func bundledCatalogChestPressListsChestPressesFirst() throws {
        let service = ExerciseLibraryService.shared
        try #require(!service.exercises.isEmpty)

        let results = service.filtered(
            levels: [],
            rawEquipment: [],
            primaryMuscles: [],
            secondaryMuscles: [],
            forces: [],
            mechanics: [],
            categories: [],
            sort: .name,
            searchText: "Chest press"
        )
        let nameMatches = results.prefix { item in
            let name = item.name.lowercased()
            return name.contains("chest") && name.contains("press")
        }

        #expect(nameMatches.count >= 6)
        #expect(results.first?.name == "Cable Chest Press")
        #expect(!results.contains { $0.name == "Alternating Cable Shoulder Press" })
    }
}
