package com.apoorvdarshan.calorietracker.data

/**
 * Lowercased word lists precomputed once per exercise so library search does not
 * re-tokenize every item on each keystroke. Mirrors iOS `ExerciseSearchIndex`.
 */
class ExerciseSearchIndex(name: String, metadata: List<String>) {
    val nameWords: List<String> = ExerciseSearch.words(name)

    /**
     * The name words joined without separators, starting at each word in turn
     * ("cablechestpress", "chestpress", "press"), so phrase matches stay word-aligned
     * and "pushup" / "push up" / "push-up" all compare equal.
     */
    val nameSuffixes: List<String> = nameWords.indices.map { nameWords.drop(it).joinToString("") }

    /**
     * Equipment, category, and primary-muscle words. Instructions and secondary muscles
     * are deliberately excluded: they mention many unrelated words and made searches
     * return non-matching items.
     */
    val metadataWords: List<String> = metadata.flatMap { ExerciseSearch.words(it) }
}

/**
 * Tokenized AND search for the exercise library, with optional query aliases and
 * relevance ranking: exact name > name prefix > phrase inside the name >
 * every word in the name > match that needs equipment/category/primary-muscle words.
 */
object ExerciseSearch {
    private val aliases = mapOf(
        "cable pushdown" to "Triceps_Pushdown",
        "triceps cable pushdown" to "Triceps_Pushdown",
        "tricep cable pushdown" to "Triceps_Pushdown",
        "tricep pushdown" to "Triceps_Pushdown",
    )

    // Marks stay inside words so Devanagari vowel signs and combining accents do not split
    // them (Swift's Character-based split already keeps those graphemes whole).
    private val wordSeparators = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /** Lowercased runs of letters and digits; punctuation and whitespace separate words. */
    fun words(text: String): List<String> =
        text.lowercase().split(wordSeparators).filter { it.isNotEmpty() }

    fun tokens(query: String): List<String> = words(query)

    /**
     * Relevance rank for [query] (lower is better), or null when the item does not match.
     * An empty query matches everything with rank 0.
     */
    fun relevance(index: ExerciseSearchIndex, query: String, exerciseId: String): Int? {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return 0

        val compactQuery = queryTokens.joinToString("")
        val compactName = index.nameSuffixes.firstOrNull().orEmpty()
        if (aliases[queryTokens.joinToString(" ")] == exerciseId || compactName == compactQuery) return 0
        if (compactName.startsWith(compactQuery)) return 1
        if (index.nameSuffixes.drop(1).any { it.startsWith(compactQuery) }) return 2
        if (queryTokens.all { token -> index.nameWords.any { wordMatches(it, token) } }) return 3
        val allWords = index.nameWords + index.metadataWords
        if (queryTokens.all { token -> allWords.any { wordMatches(it, token) } }) return 4
        return null
    }

    fun relevance(item: ExerciseItem, query: String): Int? = relevance(item.searchIndex, query, item.id)

    fun matches(item: ExerciseItem, query: String): Boolean = relevance(item, query) != null

    /**
     * Word-prefix match, so "tricep" finds "Triceps" but "press" no longer matches
     * inside unrelated words. Non-ASCII tokens also match inside a word because
     * scripts such as Japanese or Chinese do not separate words with spaces.
     */
    private fun wordMatches(word: String, token: String): Boolean =
        word.startsWith(token) || (token.any { it.code > 127 } && word.contains(token))
}
