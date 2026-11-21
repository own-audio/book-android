package audio.own.bookplayer.browse

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import audio.own.book.library.Book
import audio.own.bookplayer.app.Load
import audio.own.bookplayer.home.HomeContent
import audio.own.bookplayer.home.HomeState
import audio.own.bookplayer.player.MiniPlayer
import audio.own.bookplayer.playback.PlaybackUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class HomeInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun continueListeningPlaysAndOtherShelvesOpen() {
        val shelves = runBlocking { FakeBookSource().shelves().filterNot { it.isEmpty } }
        val played = mutableListOf<Book>()
        val opened = mutableListOf<Book>()
        compose.setContent {
            HomeContent(
                "s1", "own.audio", HomeState(Load.Loaded(shelves)), offline = false, onRetry = {}, onRefresh = {},
                onPlay = { played += it }, onOpenBook = { opened += it }, onOpenSeries = {}, onOpenAuthor = {}, hour = 20,
            )
        }
        compose.onNodeWithText("Good evening").assertExists()
        compose.onAllNodesWithContentDescription("Pride and Prejudice, Jane Austen")[0].performClick()
        compose.onNodeWithContentDescription("Emma, Jane Austen").performClick()
        assertEquals(listOf("b1"), played.map { it.id })
        assertEquals(listOf("b2"), opened.map { it.id })
    }

    @Test
    fun theMiniPlayerSkipsForwardAndARestoredBookPlaysOnTap() {
        var skips = 0
        var toggles = 0
        var opens = 0
        val state = PlaybackUi(bookId = "s1|b1", title = "Emma", durationMs = 3_600_000, skipForwardSecs = 45, restored = false)
        compose.setContent { MiniPlayer(state, onOpen = { opens++ }, onToggle = { toggles++ }, onSkipForward = { skips++ }) }
        compose.onNodeWithContentDescription("Skip forward 45 seconds").performClick()
        compose.onNodeWithText("1 h left").assertExists()
        compose.onNodeWithText("Emma").performClick()
        assertEquals(1, skips)
        assertEquals(0, toggles)
        assertEquals(1, opens)
    }
}
