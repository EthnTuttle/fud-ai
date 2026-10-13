package com.apoorvdarshan.calorietracker

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.testing.FudAITestState
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Drives the production MainActivity, navigation, screens and persistent repositories.
 * Only initial app data and background integrations are isolated by the test application.
 * Recreation/relaunch below means an Activity restart, not Android process death.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowsTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    private var activity: ActivityScenario<MainActivity>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = (context.applicationContext as FudAIApp).container.prefs

    // Capture while the failed screen still exists, before closing its Activity.
    @get:Rule(order = 1)
    val failureArtifacts = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching {
                val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                val name = "${description.methodName}-${System.currentTimeMillis()}"
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory, "$name.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                File(directory, "$name.txt").writeText(
                    compose.onRoot(useUnmergedTree = true).printToString()
                )
            }
        }

        override fun finished(description: Description) {
            activity?.close()
            activity = null
        }
    }

    @Test
    fun freshInstallShowsOnboardingAndAdvancesThroughProfileSteps() {
        launchFresh(onboarded = false)
        waitFor(hasTestTag("onboarding.WELCOME"))
        compose.onNodeWithTag("nav.home").assertDoesNotExist()

        clickText(R.string.action_get_started)
        waitFor(hasTestTag("onboarding.GENDER"))
        clickText(R.string.gender_female)
        clickText(R.string.action_continue)
        waitFor(hasTestTag("onboarding.BIRTHDAY"))
        compose.onNodeWithText(string(R.string.onboarding_birthday_title)).assertIsDisplayed()
        clickText(R.string.action_continue)
        waitFor(hasTestTag("onboarding.HEIGHT_WEIGHT"))
        compose.onNodeWithText(string(R.string.onboarding_height_weight_title)).assertIsDisplayed()
        compose.onNodeWithTag("nav.home").assertDoesNotExist()
        // The test deliberately stops before provider setup and permission prompts.
    }

    @Test
    fun completedOnboardingOpensHomeAndEveryMainTabNavigates() {
        launchFresh()
        compose.onNodeWithTag("nav.home").assertIsSelected()
        compose.onNodeWithTag("home.add").assertIsDisplayed()
        for (tab in listOf("progress", "coach", "settings", "workouts", "home")) {
            openTab(tab)
            compose.onNodeWithTag("nav.$tab").assertIsSelected()
        }
        compose.onNodeWithTag("home.add").assertIsDisplayed()
        compose.onNodeWithTag("onboarding.WELCOME").assertDoesNotExist()
    }

    @Test
    fun manualFoodRequiresNameAndCaloriesAndPersistsAcrossActivityRestarts() {
        launchFresh()
        openManualEntry()
        compose.onNodeWithTag("manual.save").assertIsNotEnabled()
        compose.onNodeWithTag("manual.name").performTextReplacement("CI oat bowl")
        compose.onNodeWithTag("manual.save").assertIsNotEnabled()
        compose.onNodeWithTag("manual.calories").performTextReplacement("321")
        compose.onNodeWithTag("manual.name").performTextClearance()
        compose.onNodeWithTag("manual.save").assertIsNotEnabled()
        compose.onNodeWithTag("manual.name").performTextReplacement("CI oat bowl")
        closeSoftKeyboard()
        compose.onNodeWithTag("manual.save").assertIsEnabled().performClick()
        waitForAbsent(hasTestTag("manual.name"))

        val saved = runBlocking {
            withTimeout(10_000) { prefs.foodEntries.first { it.size == 1 } }.single()
        }
        assertEquals("CI oat bowl", saved.name)
        assertEquals(321, saved.calories)
        assertFoodVisible(saved.name)

        activity!!.recreate()
        waitForHome()
        assertFoodVisible(saved.name)
        relaunchActivity()
        assertFoodVisible(saved.name)
        runBlocking {
            val restored = prefs.foodEntries.first().single()
            assertEquals(saved.id, restored.id)
            assertEquals(321, restored.calories)
        }
    }

    @Test
    fun cancellingManualEntryDoesNotWriteToDiary() {
        launchFresh()
        openManualEntry()
        compose.onNodeWithTag("manual.name").performTextReplacement("Do not save this meal")
        compose.onNodeWithTag("manual.calories").performTextReplacement("200")
        closeSoftKeyboard()
        clickText(R.string.action_cancel)
        waitForAbsent(hasTestTag("manual.name"))
        assertEmptyDiary()
        relaunchActivity()
        assertEmptyDiary()
        runBlocking { assertTrue(prefs.foodEntries.first().isEmpty()) }
    }

    @Test
    fun waterLogAndConfirmedDeletionPersistAcrossActivityRelaunch() {
        launchFresh()
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOff().performClick()
        waitForWaterSetting(true)
        openTab("home")

        compose.onNodeWithTag("home.add").performClick()
        clickText(R.string.water)
        val oneGlass = string(R.string.water_one_glass_dynamic, WaterUnit.MILLILITERS.format(250))
        waitFor(hasText(oneGlass))
        compose.onNodeWithText(oneGlass).performClick()
        val entry = runBlocking {
            withTimeout(10_000) { prefs.waterEntries.first { it.size == 1 } }.single()
        }
        assertEquals(250, entry.milliliters)
        val row = "diary.water.${entry.id}"
        showDiaryRow(hasTestTag(row))
        compose.onNodeWithTag(row).assertIsDisplayed()

        relaunchActivity()
        showDiaryRow(hasTestTag(row))
        compose.onNodeWithTag(row).assertIsDisplayed().performTouchInput { swipeLeft() }
        waitFor(hasText("Delete Water Log?"))
        clickText(R.string.action_delete)
        runBlocking { withTimeout(10_000) { prefs.waterEntries.first { it.isEmpty() } } }
        assertEmptyDiary()
        relaunchActivity()
        assertEmptyDiary()
        compose.onNodeWithTag(row).assertDoesNotExist()
        runBlocking { assertTrue(prefs.waterEntries.first().isEmpty()) }
    }

    @Test
    fun waterTrackingSettingPersistsOnAndOffAcrossActivityRestarts() {
        launchFresh()
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOff().performClick()
        waitForWaterSetting(true)

        activity!!.recreate()
        // Settings saves its selected category in the real navigation state.
        openTab("home")
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOn()
        relaunchActivity()
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOn().performClick()
        waitForWaterSetting(false)

        relaunchActivity()
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOff()
        openTab("home")
        compose.onNodeWithTag("home.add").performClick()
        compose.onNodeWithText(string(R.string.water)).assertDoesNotExist()
    }

    @Test
    fun workoutLibrarySearchOpensRealExerciseDetails() {
        launchFresh()
        openTab("workouts")
        waitFor(hasContentDescription("Show exercise library"))
        compose.onNodeWithContentDescription("Show exercise library").performClick()
        waitFor(hasTestTag("workouts.search"))
        compose.onNodeWithTag("workouts.search").performTextReplacement("Barbell Full Squat")
        closeSoftKeyboard()
        waitFor(hasText("Barbell Full Squat"))
        compose.onNodeWithTag("workouts.results").performScrollToNode(hasText("Barbell Full Squat"))
        compose.onNodeWithText("Barbell Full Squat").performClick()
        waitFor(hasTestTag("workouts.detail"))
        compose.onNodeWithText("Barbell Full Squat").assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.back)).performClick()
        waitFor(hasTestTag("workouts.search"))
        compose.onNodeWithTag("workouts.search").assertTextEquals("Barbell Full Squat")
    }

    private fun launchFresh(onboarded: Boolean = true) {
        FudAITestState.reset(onboarded = onboarded)
        launchActivity()
        if (onboarded) waitForHome()
    }

    private fun launchActivity() {
        activity = ActivityScenario.launch(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun relaunchActivity() {
        activity!!.close()
        activity = null
        launchActivity()
        waitForHome()
    }

    private fun waitForHome() {
        waitFor(hasTestTag("home.add"))
        compose.onNodeWithTag("nav.home").assertIsSelected()
    }

    private fun openTab(tab: String) {
        waitFor(hasTestTag("nav.$tab"))
        compose.onNodeWithTag("nav.$tab").performClick()
        waitFor(hasTestTag("nav.$tab") and isSelected())
    }

    private fun openManualEntry() {
        compose.onNodeWithTag("home.add").performClick()
        clickText(R.string.home_menu_describe_meal)
        clickText(R.string.home_menu_manual_entry)
        waitFor(hasTestTag("manual.name"))
    }

    private fun openWaterSettings() {
        openTab("settings")
        // A restored Settings tab can still be inside its last category.
        // Re-tapping the real tab returns to the hub, just as a user would.
        compose.onNodeWithTag("nav.settings").performClick()
        waitFor(hasTestTag("settings.category.TRACKING_REMINDERS"))
        compose.onNodeWithTag("settings.category.TRACKING_REMINDERS").performScrollTo().performClick()
        waitFor(hasTestTag("settings.water.enabled"))
        compose.onNodeWithTag("settings.water.enabled").performScrollTo()
    }

    private fun waitForWaterSetting(enabled: Boolean) {
        runBlocking { withTimeout(10_000) { prefs.waterTrackingEnabled.first { it == enabled } } }
        waitFor(hasTestTag("settings.water.enabled") and if (enabled) isOn() else isOff())
    }

    private fun assertFoodVisible(name: String) {
        showDiaryRow(hasText(name))
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onAllNodesWithText("321 kcal").onFirst().assertExists()
    }

    private fun assertEmptyDiary() {
        val empty = string(R.string.home_no_diary_entries)
        showDiaryRow(hasText(empty))
        compose.onNodeWithText(empty).assertIsDisplayed()
    }

    private fun showDiaryRow(matcher: SemanticsMatcher) {
        waitFor(hasTestTag("home.diary"))
        compose.onNodeWithTag("home.diary").performScrollToNode(matcher)
    }

    private fun clickText(resource: Int) {
        val text = string(resource)
        waitFor(hasText(text))
        compose.onNodeWithText(text).performClick()
    }

    private fun string(resource: Int, vararg args: Any): String = context.getString(resource, *args)

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun waitForAbsent(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
    }
}
