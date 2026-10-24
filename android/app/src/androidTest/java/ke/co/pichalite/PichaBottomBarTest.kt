package ke.co.pichalite

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PichaBottomBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allDestinationsAreAccessibleAndLargeReadyCountsFit() {
        var selected by mutableIntStateOf(0)
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { PichaBottomBar(selected, 2000) { selected = it } }
        } }
        compose.onNodeWithText("Studio").assertIsSelected()
        compose.onNodeWithText("Studio").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText("Ready").performClick().assertIsSelected()
        compose.onNodeWithText("Wrapped").performClick().assertIsSelected()
        compose.onNodeWithText("Studio").assertIsNotSelected()
        compose.onNodeWithContentDescription("2000 finished photos").assertExists()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "ui").apply { mkdirs() }
            File(folder, "custom-navigation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
