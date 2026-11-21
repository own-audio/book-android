package audio.own.bookplayer.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.book.library.BookQuery
import audio.own.book.library.Capability
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
import audio.own.bookplayer.player.MiniPlayer
import audio.own.bookplayer.player.PlayerActions
import audio.own.bookplayer.player.PlayerContent
import audio.own.bookplayer.player.SleepSheetContent
import audio.own.bookplayer.player.SpeedSheetContent
import audio.own.bookplayer.player.ChaptersSheetContent
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.bookplayer.playback.SleepUi
import audio.own.book.library.Bookmark
import audio.own.book.library.Shelf
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.player.spoken.SpokenChapter
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home, the Library as grid and list, a book, the player and its sheets in light, dark, Czech
 * and at twice the font size. Record with `:app:recordRoborazziPlayDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1100dp-xxhdpi")
class BookScreensScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val source = FakeBookSource()
    private val shelves = runBlocking { source.shelves().filterNot { it.isEmpty } }
    private val capabilities = setOf(Capability.SERIES, Capability.COLLECTIONS, Capability.FAVOURITES)

    private val chapters = Samples.pride.chapters!!.map { SpokenChapter(it.title, it.startMs, it.endMs) }
    private val playing = PlaybackUi(
        bookId = "s1|b1",
        serverId = "s1",
        title = Samples.pride.title,
        author = Samples.pride.authorLine,
        isPlaying = true,
        positionMs = 4 * 3_600_000L + 12 * 60_000L + 4_000,
        durationMs = Samples.pride.durationMs,
        chapters = chapters,
        hasRealChapters = true,
        speed = 1.75f,
        sleep = SleepUi.Countdown(23 * 60.0 + 12),
        savedAheadMs = 9 * 60_000L,
    )

    @Composable
    private fun Home() = Column {
        HomeContent(
            serverId = "s1", serverName = "own.audio",
            state = HomeState(Load.Loaded(shelves), Samples.progress, setOf("b2")),
            offline = false, onRetry = {}, onRefresh = {}, onPlay = {}, onOpenBook = {}, onOpenSeries = {}, onOpenAuthor = {},
            modifier = androidx.compose.ui.Modifier.weight(1f),
            hour = 9,
        )
        MiniPlayer(playing, onOpen = {}, onToggle = {}, onSkipForward = {})
    }

    @Composable
    private fun Library(grid: Boolean) = BooksListContent(
        title = "Library", serverId = "s1",
        state = BooksListState(BookQuery(), Load.Loaded(Samples.books.take(12)), total = 70, hasMore = true, progress = Samples.progress, favourites = setOf("b2"), grid = grid),
        capabilities = capabilities, onBack = null, onOpenBook = {}, onLoadMore = {}, onRetry = {}, onRefresh = {},
        onSort = {}, onDescending = {}, onFilter = {}, onGrid = {}, sections = sectionsFor(capabilities),
        nowPlaying = audio.own.bookplayer.browse.NowPlaying("s1|b1", true),
    )

    @Composable
    private fun Book() = BookContent(
        serverId = "s1",
        state = BookState(Load.Loaded(Samples.pride), Samples.progress["b1"], listOf(Bookmark("m1", "b1", 754_000, "The letter", Instant.EPOCH)), isFavourite = true, canFavourite = true),
        playback = PlaybackUi(),
        onBack = {}, onRetry = {}, onPlay = {}, onToggle = {}, onPlayFrom = { _, _ -> }, onOpenSeries = {}, onDismissError = {},
    )

    @Composable
    private fun Player() = PlayerContent(playing, PlayerActions())

    @Test fun homeLight() = shoot("home_light", false) { Home() }
    @Test fun homeDark() = shoot("home_dark", true) { Home() }
    @Test @Config(qualifiers = "+cs") fun homeCzech() = shoot("home_cs", false) { Home() }
    @Test @Config(fontScale = 2f) fun homeLargestText() = shoot("home_font200", false) { Home() }

    @Test fun libraryGridLight() = shoot("library_grid_light", false) { Library(true) }
    @Test fun libraryGridDark() = shoot("library_grid_dark", true) { Library(true) }
    @Test @Config(qualifiers = "+cs") fun libraryGridCzech() = shoot("library_grid_cs", false) { Library(true) }
    @Test @Config(fontScale = 2f) fun libraryGridLargestText() = shoot("library_grid_font200", false) { Library(true) }
    @Test fun libraryListLight() = shoot("library_list_light", false) { Library(false) }
    @Test fun libraryListDark() = shoot("library_list_dark", true) { Library(false) }
    @Test @Config(qualifiers = "+cs") fun libraryListCzech() = shoot("library_list_cs", false) { Library(false) }

    @Test fun bookLight() = shoot("book_light", false) { Book() }
    @Test fun bookDark() = shoot("book_dark", true) { Book() }
    @Test @Config(qualifiers = "+cs") fun bookCzech() = shoot("book_cs", false) { Book() }
    @Test @Config(fontScale = 2f) fun bookLargestText() = shoot("book_font200", false) { Book() }

    @Test fun playerLight() = shoot("player_light", false) { Player() }
    @Test fun playerDark() = shoot("player_dark", true) { Player() }
    @Test @Config(qualifiers = "+cs") fun playerCzech() = shoot("player_cs", false) { Player() }
    @Test @Config(fontScale = 2f) fun playerLargestText() = shoot("player_font200", false) { Player() }

    @Test fun speedSheetLight() = shoot("sheet_speed_light", false) { SpeedSheetContent(1.75f, {}, {}) }
    @Test fun speedSheetDark() = shoot("sheet_speed_dark", true) { SpeedSheetContent(1.75f, {}, {}) }
    @Test @Config(qualifiers = "+cs") fun speedSheetCzech() = shoot("sheet_speed_cs", false) { SpeedSheetContent(1.75f, {}, {}) }
    @Test @Config(fontScale = 2f) fun speedSheetLargestText() = shoot("sheet_speed_font200", false) { SpeedSheetContent(1.75f, {}, {}) }

    @Test fun sleepSheetLight() = shoot("sheet_sleep_light", false) { SleepSheetContent(playing.sleep, true, PlayerActions(), {}) }
    @Test fun sleepSheetDark() = shoot("sheet_sleep_dark", true) { SleepSheetContent(playing.sleep, true, PlayerActions(), {}) }
    @Test @Config(qualifiers = "+cs") fun sleepSheetCzech() = shoot("sheet_sleep_cs", false) { SleepSheetContent(SleepUi.EndOfChapter, true, PlayerActions(), {}) }
    @Test @Config(fontScale = 2f) fun sleepSheetLargestText() = shoot("sheet_sleep_font200", false) { SleepSheetContent(SleepUi.Off, true, PlayerActions(), {}) }

    private val bookmarksState = audio.own.bookplayer.player.BookmarksState(
        listOf(
            Bookmark("m1", "b1", 754_000, "The letter", Instant.EPOCH),
            Bookmark("m2", "b1", 2 * 3_600_000L + 5 * 60_000L, null, Instant.EPOCH),
            Bookmark("m3", "b1", 4 * 3_600_000L, "Netherfield ball, the long conversation in the garden", Instant.EPOCH),
        ),
    )

    @Composable
    private fun Bookmarks(state: audio.own.bookplayer.player.BookmarksState = bookmarksState) =
        audio.own.bookplayer.player.BookmarksSheetContent(state, playing.positionMs, audio.own.bookplayer.player.BookmarkSheetActions(), {})

    @Test fun bookmarksSheetLight() = shoot("sheet_bookmarks_light", false) { Bookmarks() }
    @Test fun bookmarksSheetDark() = shoot("sheet_bookmarks_dark", true) { Bookmarks() }
    @Test @Config(qualifiers = "+cs") fun bookmarksSheetCzech() = shoot("sheet_bookmarks_cs", false) { Bookmarks() }
    @Test @Config(fontScale = 2f) fun bookmarksSheetLargestText() = shoot("sheet_bookmarks_font200", false) { Bookmarks() }
    @Test fun bookmarksSheetOffline() = shoot("sheet_bookmarks_offline", false) { Bookmarks(audio.own.bookplayer.player.BookmarksState(emptyList(), offline = true)) }

    @Composable
    private fun Menu(finished: Boolean) = Column {
        audio.own.bookplayer.book.BookMenuItems(
            Samples.pride,
            BookState(Load.Loaded(Samples.pride), Samples.progress["b1"]!!.copy(finished = finished), isFavourite = !finished, canFavourite = true, canShare = true),
            audio.own.bookplayer.book.BookMenuActions(), onResetAsked = {}, onClose = {},
        )
    }

    @Test fun bookMenuLight() = shoot("book_menu_light", false) { Menu(finished = false) }
    @Test fun bookMenuDark() = shoot("book_menu_dark", true) { Menu(finished = true) }
    @Test @Config(qualifiers = "+cs") fun bookMenuCzech() = shoot("book_menu_cs", false) { Menu(finished = false) }

    private fun shootScreen(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        // A dialog is a window of its own: the whole screen is captured.
        @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
        com.github.takahirom.roborazzi.captureScreenRoboImage("src/test/screenshots/$name.png")
    }

    private val question = audio.own.bookplayer.playback.PlaybackModel.ResumeQuestion("s1", Samples.pride, 754_000, 3_723_000, "Pixel Tablet")

    @Test fun resumeDialogLight() = shootScreen("dialog_resume_light", false) { audio.own.bookplayer.player.ResumeDialog(question, {}, {}, {}) }
    @Test fun resumeDialogDark() = shootScreen("dialog_resume_dark", true) { audio.own.bookplayer.player.ResumeDialog(question, {}, {}, {}) }
    @Test @Config(qualifiers = "+cs") fun resumeDialogCzech() = shootScreen("dialog_resume_cs", false) { audio.own.bookplayer.player.ResumeDialog(question, {}, {}, {}) }
    @Test @Config(fontScale = 2f) fun resumeDialogLargestText() = shootScreen("dialog_resume_font200", false) { audio.own.bookplayer.player.ResumeDialog(question, {}, {}, {}) }
    @Test fun chaptersSheetLight() = shoot("sheet_chapters_light", false) { ChaptersSheetContent(playing, {}, {}) }
    @Test fun chaptersSheetDark() = shoot("sheet_chapters_dark", true) { ChaptersSheetContent(playing, {}, {}) }
    @Test @Config(qualifiers = "+cs") fun chaptersSheetCzech() = shoot("sheet_chapters_cs", false) { ChaptersSheetContent(playing, {}, {}) }
}
