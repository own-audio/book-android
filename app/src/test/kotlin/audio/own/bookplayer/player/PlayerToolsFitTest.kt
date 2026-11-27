package audio.own.bookplayer.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.bookplayer.playback.SleepUi
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rychlost, Spánek, Záložky, Kapitoly, Výstup: every caption whole on a small phone and with larger text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlayerToolsFitTest {
    @get:Rule val compose = createComposeRule()

    private fun assertCaptionsFit() {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = true, reduceMotion = true) {
                PlayerContent(
                    PlaybackUi(bookId = "s|b", serverId = "s", title = "Pride and Prejudice", author = "Jane Austen", positionMs = 60_000, durationMs = 3_600_000, speed = 1.75f, sleep = SleepUi.Countdown(600.0)),
                    PlayerActions(),
                )
            }
        }
        compose.waitForIdle()
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val captions = compose.onAllNodesWithTag("player.tool.label", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("${captions.size} captions", captions.size >= 4)
        for (node in captions) {
            val coordinates = node.layoutInfo.coordinates
            val bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
            assertTrue("caption at ${bounds.left}..${bounds.right} is on screen", bounds.left >= screen.left - 0.5f && bounds.right <= screen.right + 0.5f)
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            assertEquals("\"${layout.layoutInput.text}\" on one line", 1, layout.lineCount)
            assertFalse("\"${layout.layoutInput.text}\" whole, not clipped", layout.hasVisualOverflow)
        }
    }

    @Test @Config(qualifiers = "cs-w360dp-h780dp") fun czechOnASmallPhone() = assertCaptionsFit()

    @Test @Config(qualifiers = "cs-w360dp-h780dp", fontScale = 1.3f) fun czechOnASmallPhoneWithLargerText() = assertCaptionsFit()

    @Test @Config(qualifiers = "w360dp-h780dp", fontScale = 1.3f) fun englishOnASmallPhoneWithLargerText() = assertCaptionsFit()
}
