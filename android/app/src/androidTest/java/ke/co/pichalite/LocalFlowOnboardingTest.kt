package ke.co.pichalite

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalFlowOnboardingTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun firstLaunchIsShownAfterUpgradeThenRememberedAndReplayable() {
        val preferences = context.getSharedPreferences("local_flow_intro_v1", 0)
        preferences.edit().clear().commit()
        try {
            val first = PhotoModel(context.applicationContext as Application)
            assertTrue(first.showOnboarding)
            first.completeOnboarding()
            assertFalse(first.showOnboarding)
            val reopened = PhotoModel(context.applicationContext as Application)
            assertFalse(reopened.showOnboarding)
            reopened.replayOnboarding()
            assertTrue(reopened.showOnboarding)
            reopened.completeOnboarding()
            assertFalse(reopened.showOnboarding)
        } finally { preferences.edit().clear().commit() }
    }

    @Test fun introductionExplainsAllThreeScenesAndOnlyFinishesOnAcknowledgement() {
        var finished = false
        compose.setContent { MaterialTheme { LocalFlowOnboarding({ finished = true }, autoAdvance = false, playMotion = false) } }
        compose.onNodeWithTag("local_flow_onboarding").assertIsDisplayed()
        compose.onNodeWithContentDescription("A large photo becomes a smaller photo").assertIsDisplayed()
        capture("intro-compress")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithContentDescription("The smaller photo is saved and verified in Gallery").assertIsDisplayed()
        assertFalse(finished)
        capture("intro-save")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithContentDescription("The verified copy is kept while the original moves to the bin").assertIsDisplayed()
        capture("intro-replace")
        compose.onNodeWithText("Let’s get started").performClick()
        compose.runOnIdle { assertTrue(finished) }
    }

    @Test fun skipIsAvailableImmediately() {
        var finished = false
        compose.setContent { MaterialTheme { LocalFlowOnboarding({ finished = true }, autoAdvance = false, playMotion = false) } }
        compose.onNodeWithText("Skip").performClick()
        compose.runOnIdle { assertTrue(finished) }
    }

    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            val folder = File(context.getExternalFilesDir(null), "ui").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
