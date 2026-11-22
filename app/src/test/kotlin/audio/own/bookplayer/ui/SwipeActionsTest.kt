package audio.own.bookplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A row that stays in its list, swiped both ways, as a bookmark's (Rename and Delete). The
 * rename dialog itself never idles under Robolectric, so the row here stands in for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi")
class SwipeActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eachSwipeActsOnceAndTheRowThatStaysSpringsBack() {
        val done = mutableListOf<SwipeToDismissBoxValue>()
        compose.setContent {
            val swipe = rememberSwipeToDismissBoxState()
            SwipeActions(swipe) { done += it }
            SwipeToDismissBox(state = swipe, backgroundContent = {}) {
                Box(Modifier.fillMaxWidth().height(60.dp).testTag("row")) { Text("Row") }
            }
        }
        compose.onNodeWithTag("row").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals("one swipe, one action", listOf(SwipeToDismissBoxValue.StartToEnd), done)
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        assertTrue("the row is back in place, not left open at ${row.left}", row.left in (-1).dp..1.dp)
        compose.onNodeWithTag("row").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("row").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(
            "each swipe acts once",
            listOf(SwipeToDismissBoxValue.StartToEnd, SwipeToDismissBoxValue.EndToStart, SwipeToDismissBoxValue.StartToEnd),
            done,
        )
    }
}
