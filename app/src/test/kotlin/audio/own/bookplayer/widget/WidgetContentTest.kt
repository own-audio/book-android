package audio.own.bookplayer.widget

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.assertHasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.assertHasStartServiceClickAction
import androidx.glance.appwidget.testing.unit.isLinearProgressIndicator
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.test.core.app.ApplicationProvider
import audio.own.bookplayer.MainActivity
import audio.own.bookplayer.playback.BookPlaybackService
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.player.core.SkipCommands
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What each widget shows in each state, and what its buttons send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sample = WidgetSamples.state()
    private val playing = sample
    private val paused = sample.copy(player = sample.player!!.copy(isPlaying = false))
    private val resume = WidgetState(resume = sample.shelf[1], shelf = sample.shelf, skipBackSecs = 10, skipForwardSecs = 45)

    private val strip = DpSize(350.dp, 80.dp)
    private val big = DpSize(350.dp, 180.dp)

    private fun command(command: WidgetActions.Command) = actionParametersOf(WidgetActions.COMMAND to command.name)

    @Test
    fun `the strip playing shows the book and sends pause and the skips to the session`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(strip)
        provideComposable { PlayerStripContent(playing) }
        onNode(hasTextEqualTo("The Lighthouse Keeper")).assertExists()
        onNode(hasTextEqualTo("Mira Lane")).assertExists()
        onNode(hasTestTag("pause")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.PAUSE))
        onNode(hasTestTag("back")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.SKIP_BACK))
        onNode(hasTestTag("forward")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.SKIP_FORWARD))
        onNode(hasContentDescriptionEqualTo("Back 15 seconds")).assertExists()
        onNode(hasContentDescriptionEqualTo("Forward 30 seconds")).assertExists()
        onNode(hasContentDescriptionEqualTo("Pause")).assertExists()
        onNode(hasTestTag("open")).assertHasStartActivityClickAction(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    @Test
    fun `paused, play starts the media service in the foreground with a play key, as the notification does`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(strip)
        provideComposable { PlayerStripContent(paused) }
        onNode(hasTestTag("play")).assertHasStartServiceClickAction(WidgetActions.playIntent(context), isForegroundService = true)
        onNode(hasContentDescriptionEqualTo("Play")).assertExists()
    }

    @Test
    fun `a narrow strip keeps only play`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(160.dp, 80.dp))
        provideComposable { PlayerStripContent(paused) }
        onNode(hasTestTag("play")).assertExists()
        onNode(hasTestTag("back")).assertDoesNotExist()
        onNode(hasTestTag("forward")).assertDoesNotExist()
    }

    @Test
    fun `a strip stretched taller becomes the big player, with its progress`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(350.dp, 140.dp))
        provideComposable { PlayerStripContent(playing) }
        onNode(isLinearProgressIndicator(playing.player!!.progress)).assertExists()
    }

    @Test
    fun `nothing in the player offers the book to continue with how far you are, and play resumes it`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(big)
        provideComposable { NowPlayingContent(resume) }
        onNode(hasTextEqualTo("Continue · 18%")).assertExists()
        onNode(hasTextEqualTo("Paper Boats")).assertExists()
        onNode(isLinearProgressIndicator(0.18f)).assertExists()
        onNode(hasTestTag("play")).assertHasStartServiceClickAction(WidgetActions.playIntent(context), isForegroundService = true)
        onNode(hasTestTag("open")).assertHasStartActivityClickAction(WidgetActions.link(context, sample.shelf[1]))
        // The listener's own intervals, even before anything is loaded.
        onNode(hasContentDescriptionEqualTo("Back 10 seconds")).assertExists()
        onNode(hasContentDescriptionEqualTo("Forward 45 seconds")).assertExists()
    }

    @Test
    fun `the big player shows the progress, the time left and the controls`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(big)
        provideComposable { NowPlayingContent(playing) }
        onNode(isLinearProgressIndicator(0.42f)).assertExists()
        onNode(hasTextEqualTo("5 h 14 min left")).assertExists()
        onNode(hasTestTag("pause")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.PAUSE))
        onNode(hasTestTag("forward")).assertHasRunCallbackClickAction<WidgetControlCallback>(command(WidgetActions.Command.SKIP_FORWARD))
    }

    @Test
    fun `long titles stay one line on the strip`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(strip)
        val long = "A Very Long Book Title That Does Not Fit On One Line At All, Volume Two"
        provideComposable { PlayerStripContent(playing.copy(player = playing.player!!.copy(title = long))) }
        onNode(hasText(long)).assertExists()
    }

    @Test
    fun `nothing started yet says so and opens the app`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(big)
        provideComposable { NowPlayingContent(WidgetState()) }
        onNode(hasTextEqualTo("Nothing started yet")).assertExists()
        onNode(hasTestTag("play")).assertDoesNotExist()
    }

    @Test
    fun `the shelf fills its size with books and how far you are, and a tap plays one`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(350.dp, 200.dp))
        provideComposable { ShelfContent(sample) }
        val layout = ShelfLayout.of(DpSize(350.dp, 200.dp))
        onNode(hasContentDescriptionEqualTo("Paper Boats, Ada Kovač, 18% listened")).assertHasStartActivityClickAction(WidgetActions.link(context, sample.shelf[1]))
        onNode(isLinearProgressIndicator(0.18f)).assertExists()
        val shown = layout.columns * layout.rows
        if (shown < sample.shelf.size) onAllNodes(hasText(sample.shelf[shown].title)).assertCountEquals(0)
    }

    @Test
    fun `an old 2x2 widget, now the shelf, shows two by two`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(170.dp, 180.dp))
        provideComposable { ShelfContent(sample) }
        onAllNodes(hasContentDescriptionEqualTo("Small Hours, Tomas Brandt, 5% listened")).assertCountEquals(1)
        onAllNodes(hasContentDescriptionEqualTo("The Orchard, Lena Hart, 56% listened")).assertCountEquals(0)
    }

    @Test
    fun `the shelf with nothing started says so`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(350.dp, 180.dp))
        provideComposable { ShelfContent(WidgetState()) }
        onNode(hasTextEqualTo("Nothing started yet")).assertExists()
    }

    @Test
    fun `the shelf's layout from its minimum to large sizes`() {
        val old = ShelfLayout.of(DpSize(170.dp, 180.dp))
        assertEquals(2, old.columns)
        assertEquals(2, old.rows)
        assertEquals(4, ShelfLayout.of(DpSize(350.dp, 120.dp)).columns)
        assertEquals(1, ShelfLayout.of(DpSize(350.dp, 120.dp)).rows)
        assertEquals(3, ShelfLayout.of(DpSize(350.dp, 300.dp)).rows)
    }

    @Test
    fun `a book only remembered is not in the player, a loaded one is`() {
        assertNull(WidgetPlayer.of(PlaybackUi(bookId = "s|b", title = "T", restored = true)))
        assertNull(WidgetPlayer.of(PlaybackUi()))
        val loaded = WidgetPlayer.of(PlaybackUi(bookId = "s|b", title = "T", author = "A", isPlaying = true, positionMs = 60_000, durationMs = 240_000, speed = 2f))!!
        assertEquals(WidgetSnapshot.coverName("s|b"), loaded.cover)
        assertEquals(0.25f, loaded.progress)
        assertEquals("time left at the listener's speed", 90_000L, loaded.remainingMs)
    }

    @Test
    fun `play is the media button's play key, which resumes the last book when nothing is loaded`() {
        val intent = WidgetActions.playIntent(context)
        assertEquals(Intent.ACTION_MEDIA_BUTTON, intent.action)
        assertEquals(BookPlaybackService::class.java.name, intent.component?.className)
        @Suppress("DEPRECATION")
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)!!.keyCode)
    }

    @Test
    fun `each command does what its button says, the skips by the session's own commands`() {
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            calls += method.name
            null
        } as Player
        val custom: (SessionCommand) -> Unit = { calls += it.customAction }
        WidgetActions.Command.PAUSE.sendTo(player, custom)
        WidgetActions.Command.SKIP_BACK.sendTo(player, custom)
        WidgetActions.Command.SKIP_FORWARD.sendTo(player, custom)
        assertEquals(listOf("pause", SkipCommands.ACTION_SKIP_BACK, SkipCommands.ACTION_SKIP_FORWARD), calls)
    }

    @Test
    fun `the old widget's receiver is the shelf`() {
        assertEquals(ContinueListeningWidget::class, ContinueListeningWidgetReceiver().glanceAppWidget::class)
    }
}
