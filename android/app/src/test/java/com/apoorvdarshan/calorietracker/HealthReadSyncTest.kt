package com.apoorvdarshan.calorietracker

import com.apoorvdarshan.calorietracker.services.health.HealthCapabilities
import com.apoorvdarshan.calorietracker.ui.progress.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthReadSyncTest {
    private fun caps(weight: Boolean = true, bodyFat: Boolean = true, history: Boolean = false) =
        HealthCapabilities(
            weightRead = weight,
            weightWrite = true,
            bodyFatRead = bodyFat,
            bodyFatWrite = true,
            nutritionRead = false,
            nutritionWrite = false,
            energyRead = false,
            activeEnergyWrite = false,
            stepsRead = false,
            historyRead = history
        )

    @Test
    fun grantingHistoryAccessLaterReRunsTheWeightBackfill() {
        // Token seeded before history access: Health Connect hid weigh-ins older than
        // 30 days before the first grant, and the token only reports new changes.
        val seeded = healthReadTokenTypes(caps(history = false))
        val withHistory = healthReadTokenTypes(caps(history = true))

        assertTrue(healthReadsNeedBackfill(seeded, withHistory))
        assertFalse(healthReadsNeedBackfill(withHistory, withHistory))
        // Revoking history later must not force another full import.
        assertFalse(healthReadsNeedBackfill(withHistory, seeded))
    }

    @Test
    fun historyMarkerOnlyCountsWithAWeightOrBodyFatRead() {
        assertEquals(emptySet<String>(), healthReadTokenTypes(caps(weight = false, bodyFat = false, history = true)))
        assertEquals(setOf("weight"), healthReadTokenTypes(caps(bodyFat = false)))
    }

    @Test
    fun backfillWindowCoversTheAllTimeProgressRange() {
        assertTrue(HEALTH_BODY_BACKFILL_DAYS >= TimeRange.ALL_TIME.days)
    }
}
