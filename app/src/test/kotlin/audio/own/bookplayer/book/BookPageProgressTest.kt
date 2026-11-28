package audio.own.bookplayer.book

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.collectAsState
import audio.own.book.library.ListeningProgress
import audio.own.book.library.SourceException
import audio.own.bookplayer.app.Load
import audio.own.bookplayer.browse.FakeBookSource
import audio.own.bookplayer.browse.Samples
import audio.own.bookplayer.closeMain
import audio.own.bookplayer.eventually
import audio.own.bookplayer.mainThread
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The NAS test of 2026-11-22: a book page opened from Search before anything played showed the
 * length without "left" and Play instead of Continue Listening, until playing read it again.
 * The page now shows the place the phone knows at once, and keeps it when the server's answer
 * doesn't come.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi")
class BookPageProgressTest {
    @get:Rule val compose = createComposeRule()

    private val main = mainThread()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = closeMain(main)

    private val emma = Samples.emma
    private val kept = ListeningProgress(2 * 3_600_000L, false, Instant.parse("2026-11-22T18:00:00Z"))

    /** A server whose position answer is held back, refused, or a given one. */
    private class SlowSource : FakeBookSource() {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var refuse: SourceException? = null
        @Volatile var answer: ListeningProgress? = null

        override suspend fun progress(bookId: String): ListeningProgress? {
            gate?.await()
            refuse?.let { throw it }
            return answer
        }
    }

    @Test
    fun `the place the phone knows shows before the server answers`() = runBlocking {
        val source = SlowSource().apply { gate = CompletableDeferred() }
        val vm = BookViewModel(source, emma.id, known = emma, keptProgress = { kept })
        eventually { vm.state.value.progress == kept }
        show(vm)
        compose.onNodeWithContentDescription("Continue Listening").assertExists()
        compose.onNodeWithText("17 h · 15 h left").assertExists()
        source.gate!!.complete(Unit)
        Unit
    }

    @Test
    fun `a server that can't be asked leaves the place the phone knows`() = runBlocking {
        val source = SlowSource().apply { refuse = SourceException.Http(502) }
        val vm = BookViewModel(source, emma.id, known = emma, keptProgress = { kept })
        eventually { vm.state.value.book is Load.Loaded && vm.state.value.progress != null }
        vm.refresh()
        Thread.sleep(200)
        assertEquals(kept, vm.state.value.progress)
    }

    @Test
    fun `the newer place wins, the phone's or the server's`() = runBlocking {
        val source = SlowSource().apply { answer = kept.copy(positionMs = 3_600_000L, updatedAt = kept.updatedAt.minusSeconds(60)) }
        val vm = BookViewModel(source, emma.id, known = emma, keptProgress = { kept })
        eventually { vm.state.value.progress == kept }
        Thread.sleep(200)
        assertEquals("not sent yet: this phone's", kept, vm.state.value.progress)
        val newer = kept.copy(positionMs = 4 * 3_600_000L, updatedAt = kept.updatedAt.plusSeconds(60))
        source.answer = newer
        vm.refresh()
        eventually { vm.state.value.progress == newer }
    }

    @Test
    fun `nothing kept and nothing on the server is a book not started`() = runBlocking {
        val source = SlowSource()
        val vm = BookViewModel(source, emma.id, known = emma)
        eventually { vm.state.value.book is Load.Loaded }
        Thread.sleep(200)
        assertEquals(null, vm.state.value.progress)
    }

    private fun show(vm: BookViewModel) {
        compose.setContent {
            val state by vm.state.collectAsState()
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    BookContent(
                        serverId = "s1", state = state, playback = PlaybackUi(),
                        onBack = {}, onRetry = {}, onPlay = {}, onToggle = {}, onPlayFrom = { _, _ -> }, onOpenSeries = {}, onDismissError = {},
                    )
                }
            }
        }
    }
}
