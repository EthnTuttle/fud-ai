package com.apoorvdarshan.calorietracker.models

import java.text.Normalizer

/** One entry of a scanned ingredient label, for display only (#428). */
data class IngredientLabelItem(
    val segments: List<Segment>,
    /** True when the entry mentions one of the user's allergen sensitivities. */
    val matchesSensitivity: Boolean = false
) {
    data class Segment(
        val text: String,
        /** Open Food Facts marks declared allergens with underscores, e.g. `_milk_`. */
        val isAllergen: Boolean
    )

    val text: String get() = segments.joinToString("") { it.text }
}

/**
 * Turns an Open Food Facts `ingredients_text` into a readable list (#428). Display only: the
 * stored label and the allergen check keep using the raw text, and label entries never become
 * macro-bearing meal ingredients. Mirrors iOS IngredientLabelParser; keep both in sync.
 */
object IngredientLabelParser {
    /** Longer labels stay a paragraph so nothing is cut off. */
    const val MAX_ITEMS = 60

    private val separators = setOf(',', ';', '，', '；', '、')
    private val openers = setOf('(', '[', '{', '（', '［', '【')
    private val closers = setOf(')', ']', '}', '）', '］', '】')
    private val headingStems = listOf(
        "ingred", "zutaten", "składnik", "skladnik", "slozeni", "icindekiler", "tərkib", "terkib",
        "состав", "склад", "配料", "原材料", "원재료", "المكونات", "सामग्री"
    )
    // Includes no-break spaces, which the JVM's \s leaves out but iOS (ICU) collapses.
    private val whitespace = "[\\s\\u00A0\\u2007\\u202F]+".toRegex()
    private val combiningMarks = "\\p{Mn}+".toRegex()

    /**
     * The label split into entries. Empty when it does not make a useful list (a single entry,
     * or more than [MAX_ITEMS]); show [segments] of the whole text instead.
     */
    fun items(text: String, sensitivities: List<String> = emptyList()): List<IngredientLabelItem> {
        val parts = splitTopLevel(stripHeading(text))
        if (parts.size <= 1 || parts.size > MAX_ITEMS) return emptyList()
        return parts.map { part ->
            val segments = segments(part)
            IngredientLabelItem(
                segments = segments,
                matchesSensitivity = AllergenAnalysis.mentionsAny(
                    sensitivities,
                    segments.joinToString("") { it.text }
                )
            )
        }
    }

    /** Splits `_allergen_` markers into emphasized segments and drops stray underscores. */
    fun segments(text: String): List<IngredientLabelItem.Segment> {
        val result = mutableListOf<IngredientLabelItem.Segment>()
        val plain = StringBuilder()
        var index = 0
        while (index < text.length) {
            val open = text.indexOf('_', index)
            if (open < 0) break
            val close = text.indexOf('_', open + 1)
            if (close < 0) break
            plain.append(text, index, open)
            val marked = text.substring(open + 1, close)
            if (marked.isBlank()) {
                plain.append(marked)
            } else {
                if (plain.isNotEmpty()) {
                    result += IngredientLabelItem.Segment(plain.toString(), isAllergen = false)
                    plain.clear()
                }
                result += IngredientLabelItem.Segment(marked, isAllergen = true)
            }
            index = close + 1
        }
        plain.append(text.substring(index).replace("_", ""))
        if (plain.isNotEmpty()) result += IngredientLabelItem.Segment(plain.toString(), isAllergen = false)
        return result
    }

    /** Removes a leading "Ingredients:" style heading in the app's label languages. */
    fun stripHeading(text: String): String {
        val trimmed = text.trim()
        val colon = trimmed.indexOfFirst { it == ':' || it == '：' }
        if (colon < 0) return trimmed
        val heading = trimmed.substring(0, colon).trim()
        if (heading.isEmpty() ||
            heading.codePointCount(0, heading.length) > 30 ||
            heading.any { it in separators || it in openers }
        ) {
            return trimmed
        }
        val lowered = heading.lowercase()
        val folded = Normalizer.normalize(lowered, Normalizer.Form.NFD).replace(combiningMarks, "")
        if (headingStems.none { lowered.contains(it) || folded.contains(it) }) return trimmed
        return trimmed.substring(colon + 1).trim()
    }

    /** Splits on commas and semicolons outside brackets, keeping decimal commas ("8,7%"). */
    fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        text.forEachIndexed { index, char ->
            when {
                char in openers -> depth += 1
                char in closers -> depth = maxOf(0, depth - 1)
                depth == 0 && char in separators && !isDecimalComma(text, index) -> {
                    parts += current.toString()
                    current.clear()
                    return@forEachIndexed
                }
            }
            current.append(char)
        }
        parts += current.toString()
        return parts.map(::clean).filter { it.isNotEmpty() }
    }

    private fun isDecimalComma(text: String, index: Int): Boolean =
        text[index] == ',' && index > 0 && index + 1 < text.length &&
            text[index - 1] in '0'..'9' && text[index + 1] in '0'..'9'

    private fun clean(part: String): String =
        part.replace(whitespace, " ").trim().trimEnd('.', '。', ' ')
}
