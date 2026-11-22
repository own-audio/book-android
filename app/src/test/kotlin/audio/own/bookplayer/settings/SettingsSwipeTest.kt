package audio.own.bookplayer.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** own.audio Podcasts' emulator finding: a swiped server row stayed open, or the next one did with no question. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp")
class SettingsSwipeTest {
    @get:Rule val compose = createComposeRule()

    private fun server(id: String, name: String) =
        ServerRecord(id, "audiobookshelf", name, "https://$id.example.com", ServerAuth.Audiobookshelf("kim", null))

    private fun content(servers: () -> List<ServerRecord>, onRemove: (String) -> Unit) {
        compose.setContent {
            SettingsContent(
                ui = SettingsUi(servers(), activeId = null, accent = AccentChoice.APP, language = AppLanguage.SYSTEM, version = "1"),
                onBack = {}, onOpenServer = {}, onRemoveServer = onRemove, onAddServer = {}, onAccent = {}, onLanguage = {}, onLicences = {}, onLogs = {},
            )
        }
    }

    private fun confirmRemove() {
        val buttons = compose.onAllNodesWithText("Remove")
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
        compose.waitForIdle()
    }

    @Test
    fun eachServerSwipedAwayAsksFirstAlsoTheOneThatMovedUp() {
        var servers by mutableStateOf(listOf(server("a", "First"), server("b", "Second"), server("c", "Third")))
        val removed = mutableListOf<String>()
        content({ servers }) { id -> removed += id; servers = servers.filterNot { it.id == id } }
        for (name in listOf("First", "Second", "Third")) {
            compose.onNodeWithText(name).performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithText("Remove $name?").assertExists()
            confirmRemove()
        }
        assertEquals(listOf("a", "b", "c"), removed)
    }

    @Test
    fun aServerKeptAfterCancelSpringsBackAndAsksAgainOnTheNextSwipe() {
        val removed = mutableListOf<String>()
        content({ listOf(server("a", "First")) }) { removed += it }
        repeat(2) {
            compose.onNodeWithText("First").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.onNodeWithText("Remove First?").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Remove First?").assertDoesNotExist()
            val title = compose.onNodeWithText("First").getUnclippedBoundsInRoot()
            assertTrue("the row is back in place, not left open at ${title.left}", title.left in 0.dp..200.dp)
        }
        assertEquals("Cancel removes nothing", emptyList<String>(), removed)
    }
}
