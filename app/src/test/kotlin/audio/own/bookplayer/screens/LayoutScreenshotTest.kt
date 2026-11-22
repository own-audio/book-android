package audio.own.bookplayer.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.book.library.BookQuery
import audio.own.book.library.Capability
import audio.own.book.library.Person
import audio.own.book.library.SearchResult
import audio.own.book.library.SeriesSummary
import audio.own.bookplayer.app.Load
import audio.own.bookplayer.book.BookContent
import audio.own.bookplayer.book.BookState
import audio.own.bookplayer.browse.BooksListContent
import audio.own.bookplayer.browse.BooksListState
import audio.own.bookplayer.browse.FakeBookSource
import audio.own.bookplayer.browse.PeopleContent
import audio.own.bookplayer.browse.Samples
import audio.own.bookplayer.browse.SeriesListContent
import audio.own.bookplayer.browse.sectionsFor
import audio.own.bookplayer.home.HomeContent
import audio.own.bookplayer.home.HomeState
import audio.own.bookplayer.onboarding.AddServerContent
import audio.own.bookplayer.onboarding.ServerFormState
import audio.own.bookplayer.onboarding.TwoFactorContent
import audio.own.bookplayer.onboarding.TwoFactorState
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.bookplayer.playback.SleepUi
import audio.own.bookplayer.player.ChaptersSheetContent
import audio.own.bookplayer.player.MiniPlayer
import audio.own.bookplayer.player.PlayerActions
import audio.own.bookplayer.player.PlayerContent
import audio.own.bookplayer.search.SearchContent
import audio.own.bookplayer.search.SearchState
import audio.own.bookplayer.servers.SignInAgainContent
import audio.own.bookplayer.servers.SignInAgainState
import audio.own.bookplayer.account.DevicesContent
import audio.own.bookplayer.account.DevicesState
import audio.own.bookplayer.ui.LibrarySidebar
import audio.own.bookplayer.ui.Tab
import audio.own.bookplayer.ui.TabsScreen
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.kit.ownaudio.Session
import audio.own.player.spoken.SpokenChapter
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
 * The screens the other screenshot tests show only at the usual size, at twice the font size;
 * and the app on a tablet and an unfolded foldable, upright and on its side. Record with
 * `:app:recordRoborazziPlayDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1100dp-xxhdpi")
class LayoutScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) {
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
        bookId = "s1|b1", serverId = "s1", title = Samples.pride.title, author = Samples.pride.authorLine, isPlaying = true,
        positionMs = 4 * 3_600_000L + 12 * 60_000L + 4_000, durationMs = Samples.pride.durationMs, chapters = chapters,
        hasRealChapters = true, speed = 1.75f, sleep = SleepUi.Countdown(23 * 60.0 + 12), savedAheadMs = 9 * 60_000L,
    )

    @Composable
    private fun Library(grid: Boolean, modifier: Modifier = Modifier) = BooksListContent(
        title = "Library", serverId = "s1",
        state = BooksListState(BookQuery(), Load.Loaded(Samples.books.take(12)), total = 70, hasMore = true, progress = Samples.progress, favourites = setOf("b2"), grid = grid),
        capabilities = capabilities, onBack = null, onOpenBook = {}, onLoadMore = {}, onRetry = {}, onRefresh = {},
        onSort = {}, onDescending = {}, onFilter = {}, onGrid = {}, sections = sectionsFor(capabilities),
        nowPlaying = audio.own.bookplayer.browse.NowPlaying("s1|b1", true), modifier = modifier,
    )

    @Composable
    private fun Search() = SearchContent(
        serverId = "s1",
        state = SearchState("aus", SearchResult(books = listOf(Samples.pride, Samples.emma), authors = listOf(Person("a1", "Jane Austen", 2)), series = listOf(SeriesSummary("s1", "Novels", 2)))),
        onText = {}, onOpenBook = {}, onOpenSeries = {}, onOpenAuthor = {}, onOpenNarrator = {}, focusOnOpen = false,
    )

    @Composable
    private fun Book() = BookContent(
        serverId = "s1",
        state = BookState(Load.Loaded(Samples.pride), Samples.progress["b1"], isFavourite = true, canFavourite = true),
        playback = PlaybackUi(),
        onBack = {}, onRetry = {}, onPlay = {}, onToggle = {}, onPlayFrom = { _, _ -> }, onOpenSeries = {}, onDismissError = {},
    )

    private val sessions = listOf(
        Session("d1", "android", "Pixel 9", Instant.parse("2026-11-02T10:00:00Z"), expiresAt = Instant.parse("2027-01-01T00:00:00Z"), current = true),
        Session("d2", "ios", "iPhone", Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-20T10:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")),
    )

    // region Twice the font size

    @Test @Config(fontScale = 2f) fun libraryListLargestText() = shoot("library_list_font200") { Library(false) }
    @Test @Config(fontScale = 2f) fun chaptersSheetLargestText() = shoot("sheet_chapters_font200") { ChaptersSheetContent(playing, {}, {}) }
    @Test @Config(fontScale = 2f) fun searchLargestText() = shoot("search_font200") { Search() }
    @Test fun searchLight() = shoot("search_light") { Search() }
    @Test @Config(qualifiers = "+cs") fun searchCzech() = shoot("search_cs") { Search() }
    @Test @Config(fontScale = 2f) fun authorsLargestText() = shoot("authors_font200") {
        PeopleContent(false, Load.Loaded(listOf(Person("a1", "Jane Austen", 2), Person("a2", "Arthur Conan Doyle", 68))), onBack = {}, onRetry = {}, onOpen = {})
    }
    @Test @Config(fontScale = 2f) fun seriesLargestText() = shoot("series_font200") {
        SeriesListContent(Load.Loaded(listOf(SeriesSummary("s1", "Novels", 2), SeriesSummary("s2", "Sherlock Holmes", 1))), onBack = {}, onRetry = {}, onOpen = {})
    }
    @Test @Config(fontScale = 2f) fun addServerLargestText() = shoot("add_server_font200") {
        AddServerContent(ServerFormState(address = "books.example.com", username = "alex"), showGoogle = true, onCancel = {}, onAddress = {}, onUsername = {}, onPassword = {}, onName = {}, onConnect = {}, onGoogle = {})
    }
    @Test @Config(fontScale = 2f) fun signInAgainLargestText() = shoot("sign_in_again_font200") {
        SignInAgainContent(SignInAgainState("Shelf", isOwnAudio = false, username = "kim"), showGoogle = false, onCancel = {}, onUsername = {}, onPassword = {}, onSubmit = {}, onGoogle = {})
    }
    @Test @Config(fontScale = 2f) fun twoFactorLargestText() = shoot("two_factor_font200") { TwoFactorContent(TwoFactorState("123"), {}, {}, {}) }
    @Test @Config(fontScale = 2f) fun devicesLargestText() = shoot("devices_font200") {
        DevicesContent(DevicesState(Load.Loaded(sessions)), onBack = {}, onRetry = {}, onSignOut = {}, onSignOutEverywhere = {}, onDismissError = {}, clock = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC))
    }
    @Test @Config(fontScale = 2f) fun miniPlayerLargestText() = shoot("mini_player_font200") {
        Column { MiniPlayer(playing, onOpen = {}, onToggle = {}, onSkipForward = {}); MiniPlayer(playing.copy(restored = true, isPlaying = false), onOpen = {}, onToggle = {}, onSkipForward = {}) }
    }

    // endregion

    // region Tablets and foldables

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi") fun playerTabletLandscape() = shoot("tablet_player_landscape") { PlayerContent(playing, PlayerActions()) }
    @Test @Config(qualifiers = "w800dp-h1280dp-port-xhdpi") fun playerTabletPortrait() = shoot("tablet_player_portrait") { PlayerContent(playing, PlayerActions()) }

    /** The navigation suite turns the tab bar into a rail; the Library gets its sidebar. */
    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi") fun libraryTabletLandscape() = shoot("tablet_library_landscape") {
        TabsScreen(selected = Tab.LIBRARY, miniPlayer = { MiniPlayer(playing, onOpen = {}, onToggle = {}, onSkipForward = {}) }) {
            Row(Modifier.fillMaxSize()) {
                LibrarySidebar(capabilities, selection = null, onSelect = {})
                VerticalDivider(Modifier.fillMaxHeight())
                Library(grid = true, modifier = Modifier.weight(1f))
            }
        }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi") fun bookTabletLandscape() = shoot("tablet_book_landscape") { Book() }

    /** An unfolded foldable is about square: the tabs as a rail, Home in the rest. */
    @Test @Config(qualifiers = "w673dp-h841dp-xhdpi") fun homeFoldable() = shoot("foldable_home") {
        TabsScreen(selected = Tab.HOME, miniPlayer = { MiniPlayer(playing, onOpen = {}, onToggle = {}, onSkipForward = {}) }) {
            HomeContent(
                serverId = "s1", serverName = "own.audio", state = HomeState(Load.Loaded(shelves), Samples.progress, setOf("b2")),
                offline = false, onRetry = {}, onRefresh = {}, onPlay = {}, onOpenBook = {}, onOpenSeries = {}, onOpenAuthor = {}, hour = 9,
            )
        }
    }

    // endregion
}
