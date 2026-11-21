package audio.own.bookplayer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.bookplayer.ui.TabsScreen
import audio.own.bookplayer.ui.currentChoice
import audio.own.bookplayer.ui.serverChoices
import audio.own.core.servers.ServerLibraryRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun server(id: String) = ServerRecord(id, "own_audio", "Server $id", "https://$id.example.com", ServerAuth.OwnAudio("a@example.com", null))

    @Test
    fun showsTheFourTabsInOrder() {
        compose.setContent { TabsScreen() }
        listOf("Library", "Downloads", "Search").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
        // Home is the tab and the bar's title.
        compose.onAllNodesWithText("Home").assertCountEquals(2)
        compose.onNodeWithText("Search").performClick()
    }

    @Test
    @Config(qualifiers = "cs")
    fun tabsAreTranslated() {
        compose.setContent { TabsScreen() }
        listOf("Knihovna", "Stažené", "Hledat").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
        }
    }

    @Test
    fun theServerMenuOnlyWithMoreThanOneServer() {
        var servers by mutableStateOf(listOf(server("a")))
        compose.setContent { TabsScreen(servers = servers, activeId = "a") }
        compose.onAllNodesWithContentDescription("Servers").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Settings").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Offline Mode").assertCountEquals(1)
        servers = listOf(server("a"), server("b"))
        compose.onAllNodesWithContentDescription("Servers").assertCountEquals(1)
    }

    @Test
    fun anAudiobookshelfAccountWithSeveralLibrariesGivesOneEntryEach() {
        val abs = ServerRecord(
            "c", "audiobookshelf", "NAS", "http://nas:13378", ServerAuth.Audiobookshelf("kim", null),
            libraryId = "l2", libraries = listOf(ServerLibraryRef("l1", "Fiction"), ServerLibraryRef("l2", "Kids")),
        )
        val choices = serverChoices(listOf(server("a"), abs))
        assertEquals(listOf("Server a", "NAS · Fiction", "NAS · Kids"), choices.map { it.name })
        assertEquals(choices[2], currentChoice(listOf(server("a"), abs), "c"))
        // One server with two libraries is still a choice to make.
        assertEquals(2, serverChoices(listOf(abs)).size)
        compose.setContent { TabsScreen(servers = listOf(abs), activeId = "c") }
        compose.onAllNodesWithContentDescription("Servers").assertCountEquals(1)
    }
}
