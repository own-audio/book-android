package audio.own.bookplayer.widget

import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.playback.PlaybackUi
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The player widgets are redrawn on a change and once a minute while playing, never while paused. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WidgetPlayerUpdatesTest {
    @Test
    fun redrawnOnPlayPauseAndAnotherBookAndOnceAMinuteOnlyWhilePlaying() = runTest {
        val playback = MutableStateFlow(PlaybackUi())
        var redraws = 0
        val updater = WidgetUpdater(
            context = RuntimeEnvironment.getApplication(),
            store = WidgetStore(Files.createTempDirectory("widget").toFile()),
            playback = playback,
            playingCoverUrl = { null },
            shelf = { emptyList() },
            progress = { emptyMap() },
            log = AppLog(Files.createTempDirectory("logs").toFile()),
            jpeg = { null },
            redraw = {},
            redrawPlayers = { redraws++ },
            hasWidgets = { true },
        )
        updater.start(backgroundScope, emptyFlow())
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals("drawn once at the start", 1, redraws)

        playback.value = PlaybackUi(bookId = "s|b1", title = "A", isPlaying = true)
        advanceTimeBy(1_000)
        assertEquals(2, redraws)
        playback.value = playback.value.copy(positionMs = 5_000)
        advanceTimeBy(59_500)
        assertEquals("a minute later, for the progress; not for every tick", 3, redraws)

        playback.value = playback.value.copy(isPlaying = false)
        advanceTimeBy(1_000)
        assertEquals(4, redraws)
        advanceTimeBy(10 * 60_000)
        assertEquals("nothing while paused", 4, redraws)

        playback.value = PlaybackUi(bookId = "s|b2", title = "B", restored = true)
        advanceTimeBy(1_000)
        assertEquals("another book remembered", 5, redraws)
    }
}
