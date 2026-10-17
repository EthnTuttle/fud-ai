package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.UserExercise
import com.apoorvdarshan.calorietracker.models.UserExerciseDraft
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExerciseSearchTest {
    private val tricepsPushdown = ExerciseItem(
        id = "Triceps_Pushdown",
        name = "Triceps Pushdown",
        level = "Beginner",
        imagePaths = emptyList(),
        force = "Push",
        mechanic = "Isolation",
        category = "Strength",
        equipment = "Cable",
        primaryMuscles = listOf("Triceps"),
        secondaryMuscles = emptyList(),
        instructions = listOf("Attach a bar to a high pulley.")
    )

    @Test
    fun contiguousQueryMatches() {
        assertTrue(ExerciseSearch.matches(tricepsPushdown, "triceps pushdown"))
    }

    @Test
    fun tokenizedNonContiguousQueryMatches() {
        assertTrue(ExerciseSearch.matches(tricepsPushdown, "triceps cable pushdown"))
    }

    @Test
    fun aliasQueryMatches() {
        assertEquals(0, ExerciseSearch.relevance(tricepsPushdown, "cable pushdown"))
    }

    @Test
    fun missingTokenFails() {
        assertFalse(ExerciseSearch.matches(tricepsPushdown, "triceps rope"))
    }

    @Test
    fun emptyQueryMatchesEverything() {
        assertEquals(0, ExerciseSearch.relevance(tricepsPushdown, "   "))
    }

    @Test
    fun wordPrefixMatchesPluralMuscleNames() {
        assertTrue(ExerciseSearch.matches(tricepsPushdown, "tricep"))
    }

    @Test
    fun instructionsAreNotSearched() {
        assertFalse(ExerciseSearch.matches(tricepsPushdown, "pulley"))
    }

    @Test
    fun tokensOnlyMatchAtWordStarts() {
        assertFalse(ExerciseSearch.matches(tricepsPushdown, "down"))
    }

    @Test
    fun hyphenatedAndJoinedSpellingsMatchExactly() {
        val pushUp = exercise(id = "Push_Up", name = "Push-Up")
        assertEquals(0, ExerciseSearch.relevance(pushUp, "pushup"))
        assertEquals(0, ExerciseSearch.relevance(pushUp, "push up"))
    }

    @Test
    fun relevanceTiersOrderExactPrefixPhraseWordsAndMetadata() {
        assertEquals(0, ExerciseSearch.relevance(exercise(name = "Chest Press"), "Chest press"))
        assertEquals(1, ExerciseSearch.relevance(exercise(name = "Chest Press Machine"), "Chest press"))
        assertEquals(2, ExerciseSearch.relevance(exercise(name = "Cable Chest Press"), "Chest press"))
        assertEquals(3, ExerciseSearch.relevance(exercise(name = "Press Against Chest Wall"), "Chest press"))
        assertEquals(
            4,
            ExerciseSearch.relevance(exercise(name = "Barbell Bench Press", primaryMuscles = listOf("Chest")), "Chest press")
        )
    }

    @Test
    fun nonAsciiQueriesMatchInsideUnspacedNames() {
        assertTrue(ExerciseSearch.matches(exercise(id = "user_exercise_ja", name = "ベンチプレス"), "プレス"))
    }

    @Test
    fun devanagariVowelSignsStayInsideWords() {
        assertEquals(listOf("पुश", "अप"), ExerciseSearch.words("पुश अप"))
    }

    /**
     * Reporter's example (#497): "Chest press" returned unrelated presses whose
     * instructions merely mention the chest, in alphabetical order.
     */
    @Test
    fun chestPressRanksNameMatchesFirstAndSkipsInstructionOnlyHits() {
        val repository = ExerciseRepository.forTests(
            listOf(
                exercise(
                    id = "Alternating_Cable_Shoulder_Press",
                    name = "Alternating Cable Shoulder Press",
                    equipment = "Cable",
                    primaryMuscles = listOf("Shoulders"),
                    secondaryMuscles = listOf("Triceps"),
                    instructions = listOf("Keeping your head and chest up, extend through the elbow to press one side directly over head.")
                ),
                exercise(
                    id = "Barbell_Bench_Press_-_Medium_Grip",
                    name = "Barbell Bench Press - Medium Grip",
                    equipment = "Barbell",
                    primaryMuscles = listOf("Chest"),
                    secondaryMuscles = listOf("Shoulders", "Triceps")
                ),
                exercise(id = "Leverage_Chest_Press", name = "Leverage Chest Press", equipment = "Machine", primaryMuscles = listOf("Chest")),
                exercise(id = "Cable_Chest_Press", name = "Cable Chest Press", equipment = "Cable", primaryMuscles = listOf("Chest"))
            )
        )

        val results = repository.filtered(searchText = "Chest press")

        assertEquals(
            listOf("Cable Chest Press", "Leverage Chest Press", "Barbell Bench Press - Medium Grip"),
            results.map { it.name }
        )
    }

    @Test
    fun emptyQueryKeepsChosenSortOrder() {
        val repository = ExerciseRepository.forTests(
            listOf(exercise(id = "b", name = "Bench Press"), exercise(id = "a", name = "Air Bike"))
        )

        assertEquals(listOf("Air Bike", "Bench Press"), repository.filtered(searchText = "").map { it.name })
    }

    @Test
    fun userExerciseNamesAreSearchable() {
        val item = UserExerciseDraft(name = "Landmine Chest Press").toExerciseItem(UserExercise.newId(), emptyList())

        assertEquals(2, ExerciseSearch.relevance(item, "chest press"))
        assertEquals(1, ExerciseSearch.relevance(item, "landmine"))
    }

    @Test
    fun bundledCatalogChestPressListsChestPressesFirst() {
        val records: List<ExerciseRecord> = File(repositoryRoot(), "ios/calorietracker/Resources/FreeExerciseDB/dist/exercises.json")
            .reader(Charsets.UTF_8)
            .use { Gson().fromJson(it, object : TypeToken<List<ExerciseRecord>>() {}.type) }
        val repository = ExerciseRepository.forTests(records.mapNotNull { ExerciseItem.from(it) })

        val results = repository.filtered(searchText = "Chest press")
        val nameMatches = results.takeWhile { item ->
            val name = item.name.lowercase()
            "chest" in name && "press" in name
        }

        assertTrue("expected the chest presses first, got ${results.take(8).map { it.name }}", nameMatches.size >= 6)
        assertEquals("Cable Chest Press", results.first().name)
        assertFalse(results.any { it.name == "Alternating Cable Shoulder Press" })
    }

    private fun exercise(
        id: String = "id",
        name: String,
        equipment: String = "Unspecified",
        primaryMuscles: List<String> = emptyList(),
        secondaryMuscles: List<String> = emptyList(),
        instructions: List<String> = emptyList()
    ) = ExerciseItem(
        id = id,
        name = name,
        level = "Beginner",
        imagePaths = emptyList(),
        force = "Unspecified",
        mechanic = "Unspecified",
        category = "Strength",
        equipment = equipment,
        primaryMuscles = primaryMuscles,
        secondaryMuscles = secondaryMuscles,
        instructions = instructions
    )

    private fun repositoryRoot(): File = generateSequence(
        File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
    ) { directory -> directory.parentFile }
        .firstOrNull { directory ->
            File(directory, "ios/calorietracker/Resources/FreeExerciseDB").isDirectory
        }
        ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")
}
