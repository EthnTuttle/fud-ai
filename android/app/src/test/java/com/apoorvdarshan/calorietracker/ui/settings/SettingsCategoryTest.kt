package com.apoorvdarshan.calorietracker.ui.settings

import com.apoorvdarshan.calorietracker.services.voice.VoiceSettingsSection
import com.apoorvdarshan.calorietracker.ui.about.AboutSettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoryTest {
    @Test
    fun hubKeepsEveryFocusedCategory() {
        assertEquals(17, SettingsCategory.entries.size)
        assertEquals(17, SettingsCategory.entries.map { it.titleRes }.toSet().size)
        assertEquals(12, SettingsCategory.preferenceEntries.size)
        assertEquals(5, SettingsCategory.appInfoEntries.size)
        assertEquals(5, SettingsCategory.entries.mapNotNull { it.aboutCategory }.size)
    }

    @Test
    fun appInfoKeepsEveryFocusedCategory() {
        assertEquals(5, AboutSettingsCategory.entries.size)
        assertEquals(5, AboutSettingsCategory.entries.map { it.titleRes }.toSet().size)
    }

    @Test
    fun everyVoiceSectionOpensACategoryThatReportsItBack() {
        for (section in VoiceSettingsSection.entries) {
            assertEquals(section, SettingsCategory.forVoiceSection(section).voiceSection)
        }
        assertEquals(SettingsCategory.VOICE_ASSISTANT, SettingsCategory.forVoiceSection(VoiceSettingsSection.VOICE_ASSISTANT))
        assertEquals(VoiceSettingsSection.ABOUT, SettingsCategory.LEGAL.voiceSection)
        assertEquals(VoiceSettingsSection.AI_PROVIDERS, SettingsCategory.AI_ACCESS.voiceSection)
    }
}
