package com.example.kftgcs.aquaculture

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.kftgcs.aquaculture.ui.*
import com.example.kftgcs.aquaculture.repository.AquaculturePreferences
import com.example.kftgcs.aquaculture.model.PondPoint
import com.example.kftgcs.FeatureFlags
import com.example.kftgcs.navigation.AppNavGraph
import com.example.kftgcs.navigation.Screen
import com.example.kftgcs.telemetry.SharedViewModel
import com.example.kftgcs.usersettings.UserSettingsViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AquacultureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledGateShowsOnlyAgricultureAndPreservesItsCallback() {
        var agricultureSelected = false
        compose.setContent { MaterialTheme {
            OperationSelectionScreen(false, { agricultureSelected = true }, { error("Aquaculture must be hidden") })
        } }
        compose.onNodeWithText("Select Operation").assertIsDisplayed()
        compose.onNodeWithText("Aquaculture", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Agriculture").performClick()
        compose.runOnIdle { assertTrue(agricultureSelected) }
    }

    @Test fun enabledGateExposesAquaculture() {
        var selected = false
        compose.setContent { MaterialTheme { OperationSelectionScreen(true, {}, { selected = true }) } }
        val agriculture = compose.onNodeWithText("Agriculture").fetchSemanticsNode().boundsInRoot
        val aquaculture = compose.onNodeWithText("Aquaculture").fetchSemanticsNode().boundsInRoot
        assertEquals(agriculture.center.y, aquaculture.center.y, 1f)
        assertTrue(agriculture.center.x < aquaculture.center.x)
        compose.onNodeWithText("Aquaculture").performClick()
        compose.runOnIdle { assertTrue(selected) }
        captureIfRequested("operation-selection.png")
    }

    @Test fun fishIsDisabledAndPrawnOpensThePlanner() {
        var prawnSelected = false
        compose.setContent { MaterialTheme { AquacultureCategoryScreen({ prawnSelected = true }, {}) } }
        compose.onNodeWithText("Fish").assertIsNotEnabled()
        compose.onNodeWithText("Coming Soon").assertIsDisplayed()
        compose.onNodeWithContentDescription("Prawn").assertExists()
        compose.onNodeWithText("Prawn").performClick()
        compose.runOnIdle { assertTrue(prawnSelected) }
        captureIfRequested("culture-selection.png")
    }

    @Test fun realNavigationGateAndAgricultureHandoff() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val settings = UserSettingsViewModel(application)
        lateinit var navigation: NavHostController
        compose.setContent { MaterialTheme {
            navigation = rememberNavController()
            AppNavGraph(navigation, settings)
        } }
        compose.runOnIdle {
            assertEquals(FeatureFlags.enableAquaculture, navigation.graph.findNode(Screen.AquaculturePrawn.route) != null)
            assertEquals(FeatureFlags.enableAquaculture, navigation.graph.findNode(Screen.AquacultureCategory.route) != null)
            navigation.navigate(Screen.OperationSelection.route)
        }
        compose.onNodeWithText("Agriculture").performClick()
        compose.runOnIdle { assertEquals(Screen.SelectMethod.route, navigation.currentDestination?.route) }
    }

    @Test fun fullPrawnScreenGeneratesDifferentLapsForSamePond() {
        val vm = AquacultureViewModel()
        val telemetry = SharedViewModel()
        compose.setContent { MaterialTheme { AquacultureScreen(vm, telemetry, {}, {}) } }
        compose.onNodeWithText("Load sample pond").performScrollTo().performClick()
        captureIfRequested("prawn-setup.png")
        compose.onNodeWithText("Calculate & review").performClick()
        compose.onNodeWithText("Generate mission").performScrollTo().performClick()
        var pond = vm.state.value.boundary
        compose.runOnIdle {
            pond = vm.state.value.boundary
            assertEquals(3, vm.state.value.plan!!.totalPasses)
        }
        compose.onNodeWithText("Setup").performClick()
        compose.onNodeWithTag("culture_day_picker").performScrollTo().performClick()
        compose.onNodeWithTag("culture_day_75").performScrollTo().performClick()
        compose.onNodeWithText("Day 75").assertIsDisplayed()
        compose.onNodeWithText("Calculate & review").performClick()
        compose.onNodeWithText("Generate mission").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(pond, vm.state.value.boundary)
            assertEquals(4, vm.state.value.plan!!.totalPasses)
        }
        compose.onNodeWithText("Upload load 1").performScrollTo().assertIsNotEnabled()
        val panel = compose.onNodeWithTag("aquaculture_planning_panel").fetchSemanticsNode().boundsInRoot
        val map = compose.onNodeWithTag("aquaculture_map").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.right <= map.left + 1f)
        compose.onAllNodesWithText("DEMO", substring = true, ignoreCase = true).assertCountEquals(0)
        captureIfRequested("prawn-mission.png")
    }

    @Test fun hopperCapacityFieldIsEditableAndSavedForFutureMissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("aquaculture_settings", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getString("hopper_capacity_kg", null)
        try {
            val settings = AquaculturePreferences(context)
            settings.saveHopperCapacityKg(2.0)
            val vm = AquacultureViewModel(settings = settings)
            val telemetry = SharedViewModel()
            compose.setContent { MaterialTheme { AquacultureScreen(vm, telemetry, {}, {}) } }
            compose.onNodeWithTag("hopper_capacity").performScrollTo().assertIsDisplayed()
            captureIfRequested("prawn-hopper.png")
            compose.onNodeWithTag("hopper_capacity").performTextClearance()
            compose.onNodeWithTag("hopper_capacity").performTextInput("5")
            compose.runOnIdle {
                val reopened = AquacultureViewModel(settings = AquaculturePreferences(context))
                assertEquals(5.0, reopened.state.value.fields.getValue(AquacultureField.HOPPER).toDouble(), 0.0)
                reopened.setBoundary(listOf(PondPoint(17.0, 78.0), PondPoint(17.0, 78.001),
                    PondPoint(17.0008, 78.001), PondPoint(17.0008, 78.0)))
                reopened.setCultureDay(75)
                reopened.calculate()
                reopened.generate()
                assertEquals(1, reopened.state.value.plan!!.loads.size)
                assertEquals(4.8, reopened.state.value.plan!!.loads.first().feedKg, 1e-9)
            }
        } finally {
            preferences.edit().apply {
                if (previous == null) remove("hopper_capacity_kg") else putString("hopper_capacity_kg", previous)
            }.commit()
        }
    }

    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureAquaculture") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Let the external Maps renderer draw while advancing Compose's test frame clock.
        repeat(8) { SystemClock.sleep(500); compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
        val image = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
