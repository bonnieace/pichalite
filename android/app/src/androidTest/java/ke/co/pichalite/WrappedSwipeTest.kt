package ke.co.pichalite

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WrappedSwipeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun horizontalSwipesChangeCardsBothWays() {
        val stats = WrappedStats.from(QueueState(loaded = true))
        compose.setContent { MaterialTheme {
            val pager = rememberPagerState(pageCount = { WrappedArtwork.CARD_COUNT })
            Column {
                WrappedCardPager(stats, pager, Modifier.fillMaxWidth().height(400.dp))
                Text("Card ${pager.currentPage + 1}")
            }
        } }
        compose.onNodeWithText("Card 1").assertIsDisplayed()
        compose.onNodeWithTag("wrapped_artwork").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Card 2").assertIsDisplayed()
        compose.onNodeWithTag("wrapped_artwork").performTouchInput { swipeRight() }
        compose.onNodeWithText("Card 1").assertIsDisplayed()
    }
}
