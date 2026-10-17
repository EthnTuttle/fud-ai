package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.WeightEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

class WeightHistoryImportTest {
    private val day = Instant.parse("2026-09-21T08:00:00Z")

    @Test
    fun deletedWeighInIsNotResurrectedByAHealthConnectReimport() {
        val scaleReading = WeightEntry(id = UUID.randomUUID(), date = day, weightKg = 81.4)
        val kept = WeightEntry(id = UUID.randomUUID(), date = day.plusSeconds(86_400), weightKg = 81.0)

        // The user deleted the scale reading; a full backfill delivers it again.
        val merged = mergeExternalWeights(
            current = listOf(kept),
            incoming = listOf(scaleReading, kept),
            deletedIds = setOf(scaleReading.id.toString())
        )

        assertNull(merged)
    }

    @Test
    fun olderBackfilledWeighInsAreMergedInDateOrder() {
        val recent = WeightEntry(date = day, weightKg = 80.0)
        val older = WeightEntry(date = day.minusSeconds(86_400L * 500), weightKg = 92.0)
        val deleted = WeightEntry(date = day.minusSeconds(86_400L * 400), weightKg = 90.0)

        val merged = mergeExternalWeights(
            current = listOf(recent),
            incoming = listOf(deleted, older),
            deletedIds = setOf(deleted.id.toString())
        )

        assertEquals(listOf(older, recent), merged)
    }

    @Test
    fun valueCorrectionUpsertsAndDuplicateIdsCollapse() {
        val id = UUID.randomUUID()
        val original = WeightEntry(id = id, date = day, weightKg = 80.0)
        val corrected = original.copy(weightKg = 79.6)

        assertEquals(listOf(corrected), mergeExternalWeights(listOf(original), listOf(corrected), emptySet()))
        assertEquals(listOf(original), mergeExternalWeights(listOf(original, original), listOf(original), emptySet()))
        assertNull(mergeExternalWeights(listOf(original), listOf(original), emptySet()))
    }
}
