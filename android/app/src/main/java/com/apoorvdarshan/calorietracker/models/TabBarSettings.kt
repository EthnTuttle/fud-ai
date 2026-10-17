package com.apoorvdarshan.calorietracker.models

/**
 * Tabs the user can hide from the bottom bar. Home and Settings are always shown.
 * Values are the tab routes, stored comma-separated ("coach,workouts"), the same format iOS uses.
 */
object TabBarSettings {
    const val STORAGE_KEY = "hiddenTabs"
    val Hideable = listOf("progress", "coach", "workouts")

    /** Unknown values (including Home and Settings) are ignored, so a missing key shows every tab. */
    fun decode(raw: String?): Set<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it in Hideable }.toSet()

    fun encode(hidden: Set<String>): String = Hideable.filter { it in hidden }.joinToString(",")
}
