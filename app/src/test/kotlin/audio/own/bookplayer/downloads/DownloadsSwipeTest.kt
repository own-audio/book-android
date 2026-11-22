package audio.own.bookplayer.downloads

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import audio.own.book.library.Book
import audio.own.bookplayer.browse.Samples
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

/**
 * Found on the emulator: swiping a download away while an earlier removal was still under way
 * left the row below as an empty red "Remove" strip, and that book left the list too. The rows
 * are keyed by book now; this keeps a removal from touching the book that moves up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi")
class DownloadsSwipeTest {
    @get:Rule val compose = createComposeRule()

    private fun entry(book: Book, at: Long) =
        DownloadedBook("s1|${book.id}", "s1", book, 100_000_000, at, at, null, DownloadState.Downloaded)

    @Test
    fun aRemovalLandingWhileARowIsSwipedAwayDoesNotTakeTheNextBook() {
        val first = entry(Samples.pride, 30)
        val second = entry(Samples.emma, 20)
        val snapshot = mutableStateOf(BookDownloadsSnapshot(listOf(first, second).associateBy { it.compositeId }, loaded = true))
        val removed = mutableListOf<String>()
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DownloadsContent(snapshot.value, emptyList(), onOpen = {}, onRemove = { removed += it.compositeId }, onRetryFailed = {}, onRemoveAll = {})
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("downloads.${Samples.pride.id}").performTouchInput { swipeLeft() }
        // An earlier removal of the same book lands while the row is still sliding away.
        snapshot.value = BookDownloadsSnapshot(mapOf(second.compositeId to second), loaded = true)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse("the book that moved up was not removed", second.compositeId in removed)
        val row = compose.onNodeWithTag("downloads.${Samples.emma.id}").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Emma's row at ${row.left}..${row.right} of ${root.width}", row.left >= 0f && row.right <= root.right)
    }

    @Test
    fun oneSwipeRemovesOnceAndARowThatStaysSpringsBack() {
        val only = entry(Samples.pride, 30)
        // The removal hasn't landed yet: the book is still in the snapshot.
        val snapshot = BookDownloadsSnapshot(mapOf(only.compositeId to only), loaded = true)
        val removed = mutableListOf<String>()
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DownloadsContent(snapshot, emptyList(), onOpen = {}, onRemove = { removed += it.compositeId }, onRetryFailed = {}, onRemoveAll = {})
                }
            }
        }
        val before = compose.onNodeWithTag("downloads.${Samples.pride.id}").fetchSemanticsNode().boundsInRoot.left
        compose.onNodeWithTag("downloads.${Samples.pride.id}").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("one swipe, one removal", listOf(only.compositeId), removed)
        val row = compose.onNodeWithTag("downloads.${Samples.pride.id}").fetchSemanticsNode().boundsInRoot
        assertTrue("the row is back in place, not left open at ${row.left}", row.left in (before - 1)..(before + 1))
    }
}
