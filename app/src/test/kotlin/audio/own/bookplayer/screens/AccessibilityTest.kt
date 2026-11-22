package audio.own.bookplayer.screens

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import audio.own.book.library.BookQuery
import audio.own.book.library.Capability
import audio.own.bookplayer.MainActivity
import audio.own.bookplayer.app.Load
import audio.own.bookplayer.book.BookContent
import audio.own.bookplayer.book.BookState
import audio.own.bookplayer.browse.BooksListContent
import audio.own.bookplayer.browse.BooksListState
import audio.own.bookplayer.browse.FakeBookSource
import audio.own.bookplayer.browse.Samples
import audio.own.bookplayer.browse.sectionsFor
import audio.own.bookplayer.home.HomeContent
import audio.own.bookplayer.home.HomeState
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.bookplayer.playback.SleepUi
import audio.own.bookplayer.player.MiniPlayer
import audio.own.bookplayer.player.PlayerActions
import audio.own.bookplayer.player.PlayerContent
import audio.own.bookplayer.player.SpeedSheetContent
import audio.own.bookplayer.ui.Tab
import audio.own.bookplayer.ui.TabsScreen
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.player.spoken.SpokenChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What TalkBack, a keyboard and a large finger meet: every control has a name, the sliders and
 * the Play capsule say their value, screens have headings, targets are 48 dp, and the player
 * answers Space, Ctrl+←/→ and Esc. Also the restless navigation of book-ios `MisuseTests`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1100dp-xxhdpi")
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) { content() }
    }

    private val chapter = SpokenChapter("Chapter 3", 0, 49 * 60_000L + 23_000)
    private val playing = PlaybackUi(
        bookId = "s1|b1", serverId = "s1", title = Samples.pride.title, author = Samples.pride.authorLine, isPlaying = true,
        positionMs = 12 * 60_000L + 4_000, durationMs = chapter.endMs, chapters = listOf(chapter), hasRealChapters = true,
        speed = 1.75f, sleep = SleepUi.EndOfChapter,
    )

    private fun all(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes()

    private fun SemanticsNode.spoken(): String = listOfNotNull(
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
        config.getOrNull(SemanticsProperties.Text)?.joinToString(),
    ).joinToString().trim()

    /** Every control TalkBack can reach has words, and is at least 48 dp each way. */
    private fun assertControlsNamedAndLargeEnough(screen: String) {
        val density = compose.density
        val min = with(density) { 48.dp.toPx() } - 1f
        val controls = all(hasClickAction())
        assertTrue("$screen has controls", controls.isNotEmpty())
        for (node in controls) {
            assertTrue("$screen: a control without a name at ${node.boundsInRoot}", node.spoken().isNotEmpty())
            val bounds = node.touchBoundsInRoot
            assertTrue("$screen: '${node.spoken()}' is ${bounds.width}×${bounds.height} px", bounds.width >= min && bounds.height >= min)
        }
    }

    @Test
    fun `the player names every control and says the chapter slider's value`() {
        show { PlayerContent(playing, PlayerActions()) }
        assertControlsNamedAndLargeEnough("player")
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12:04 of 49:23")).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Sleep Timer")).and(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "End of Chapter"))).assertExists()
        compose.onNode(isHeading().and(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString(Samples.pride.title))))).assertExists()
    }

    @Test
    fun `the player answers the keyboard`() {
        var toggles = 0
        var back = 0
        var forward = 0
        var closed = 0
        show {
            PlayerContent(playing, PlayerActions(onToggle = { toggles++ }, onSkipBack = { back++ }, onSkipForward = { forward++ }, onClose = { closed++ }))
        }
        compose.waitForIdle()
        compose.onRoot().performKeyInput {
            pressKey(Key.Spacebar)
            withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionRight) }
            withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionLeft) }
            // Without Ctrl an arrow moves the focus, as everywhere else.
            pressKey(Key.DirectionRight)
            pressKey(Key.Escape)
        }
        assertEquals(1, toggles)
        assertEquals(1, forward)
        assertEquals(1, back)
        assertEquals(1, closed)
    }

    @Test
    fun `the Play capsule says how far the book is`() {
        show {
            BookContent(
                serverId = "s1",
                state = BookState(Load.Loaded(Samples.pride), Samples.progress["b1"]),
                playback = PlaybackUi(),
                onBack = {}, onRetry = {}, onPlay = {}, onToggle = {}, onPlayFrom = { _, _ -> }, onOpenSeries = {}, onDismissError = {},
            )
        }
        assertControlsNamedAndLargeEnough("book")
        // 4 h of 11 h 35 min.
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "35% listened")).assertExists()
        assertTrue(all(isHeading()).any { it.spoken() == Samples.pride.title })
    }

    @Test
    fun `home and the library read each book with its badges`() {
        val shelves = runBlocking { FakeBookSource().shelves().filterNot { it.isEmpty } }
        show {
            Column {
                HomeContent(
                    "s1", "own.audio", HomeState(Load.Loaded(shelves), Samples.progress, setOf("b2")), offline = false, onRetry = {}, onRefresh = {},
                    onPlay = {}, onOpenBook = {}, onOpenSeries = {}, onOpenAuthor = {}, hour = 9, modifier = androidx.compose.ui.Modifier.weight(1f),
                )
                MiniPlayer(playing, onOpen = {}, onToggle = {}, onSkipForward = {})
            }
        }
        assertControlsNamedAndLargeEnough("home")
        val states = all(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).map { it.config[SemanticsProperties.StateDescription] }
        assertTrue("$states", "Shared with the family, 35% listened" in states)
        assertTrue("$states", "Favorite" in states)
        assertTrue("shelf titles are headings", all(isHeading()).any { it.spoken() == "Continue Listening" })
    }

    @Test
    fun `a restored book in the mini player says it plays from where it was left`() {
        show { MiniPlayer(playing.copy(restored = true, isPlaying = false), onOpen = {}, onToggle = {}, onSkipForward = {}) }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Pride and Prejudice, play from where you left off"))).assertExists()
    }

    @Test
    fun `the library list names its controls`() {
        val capabilities = setOf(Capability.SERIES, Capability.COLLECTIONS, Capability.FAVOURITES)
        show {
            BooksListContent(
                title = "Library", serverId = "s1",
                state = BooksListState(BookQuery(), Load.Loaded(Samples.books.take(6)), total = 70, hasMore = true, progress = Samples.progress, favourites = setOf("b2"), grid = false),
                capabilities = capabilities, onBack = null, onOpenBook = {}, onLoadMore = {}, onRetry = {}, onRefresh = {},
                onSort = {}, onDescending = {}, onFilter = {}, onGrid = {}, sections = sectionsFor(capabilities),
                nowPlaying = audio.own.bookplayer.browse.NowPlaying("s1|b1", true),
            )
        }
        assertControlsNamedAndLargeEnough("library")
        assertTrue(all(isHeading()).any { it.spoken() == "Library" })
    }

    @Test
    fun `the speed sheet names its steppers`() {
        show { SpeedSheetContent(1.75f, {}, {}) }
        assertControlsNamedAndLargeEnough("speed")
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("1.75×")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    @Config(qualifiers = "+cs")
    fun `speed has the locale's decimal sign`() {
        show { PlayerContent(playing, PlayerActions()) }
        compose.onNodeWithText("1,75×", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `tabs switched as fast as they can be tapped end on the last one`() {
        show { TabsScreen(content = { tab -> Text("Showing ${tab.name}") }) }
        val labels = listOf("Library", "Downloads", "Search", "Home")
        repeat(60) { i -> compose.onNodeWithText(labels[i % labels.size], useUnmergedTree = true).performClick() }
        compose.onNodeWithText("Showing ${Tab.HOME.name}").assertExists()
        compose.onNodeWithText("Search", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Showing ${Tab.SEARCH.name}").assertExists()
    }

    @Test
    fun `phones stay upright, tablets and unfolded foldables turn`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, MainActivity.orientationFor(411))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, MainActivity.orientationFor(600))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, MainActivity.orientationFor(840))
    }
}
