package audio.own.bookplayer.player

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import audio.own.book.library.Bookmark
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * own.audio Podcasts acted on a row again and again while it stayed swiped open (899 writes to a
 * server); here every swipe of a bookmark acts once and the row springs back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi")
class BookmarkSwipeTest {
    @get:Rule val compose = createComposeRule()

    private fun bookmark(id: String, positionMs: Long, label: String) = Bookmark(id, "b1", positionMs, label, Instant.EPOCH)

    private fun rows(bookmarks: () -> List<Bookmark>, actions: BookmarkActions) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = false, reduceMotion = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column { bookmarks().forEach { key(it.id) { BookmarkRow(it, actions) } } }
                }
            }
        }
    }

    private fun assertInPlace(tag: String) {
        val row = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue("$tag is back in place, not left open at ${row.left}", row.left in (-1).dp..1.dp)
    }

    @Test
    fun aSwipeToDeleteDeletesOnceAndARowThatStaysSpringsBack() {
        val deleted = mutableListOf<String>()
        // The server refused: the bookmark stays in the list.
        rows({ listOf(bookmark("m1", 60_000, "Start")) }, BookmarkActions(onRename = { _, _ -> }, onDelete = { deleted += it.id }))
        compose.onNodeWithTag("bookmark.60000").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("one swipe, one delete", listOf("m1"), deleted)
        assertInPlace("bookmark.60000")
        compose.onNodeWithTag("bookmark.60000").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("each swipe deletes once", listOf("m1", "m1"), deleted)
    }

    @Test
    fun aSwipeToRenameOpensTheDialogAndSavesTheNewLabelOnce() {
        val renamed = mutableListOf<Pair<String, String>>()
        val list = mutableStateOf(listOf(bookmark("m1", 60_000, "Start")))
        // Before the dialog's field was state-based, the screen never went idle once it showed.
        rows({ list.value }, BookmarkActions(onRename = { b, label -> renamed += b.id to label; list.value = list.value.map { if (it.id == b.id) it.copy(label = label) else it } }, onDelete = {}))
        compose.onNodeWithTag("bookmark.60000").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("Rename Bookmark").assertIsDisplayed()
        compose.onNodeWithTag("bookmark.label").assert(hasText("Start")).performTextReplacement("Netherfield")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals("one swipe, one rename", listOf("m1" to "Netherfield"), renamed)
        compose.onNodeWithText("Rename Bookmark").assertDoesNotExist()
        compose.onNodeWithText("Netherfield").assertIsDisplayed()
        assertInPlace("bookmark.60000")
        // Cancel changes nothing, and the next swipe opens the dialog again.
        compose.onNodeWithTag("bookmark.60000").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("bookmark.label").assert(hasText("Netherfield"))
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rename Bookmark").assertDoesNotExist()
        assertEquals(1, renamed.size)
        assertInPlace("bookmark.60000")
    }

    @Test
    fun aDeletionLandingWhileARowIsSwipedAwayLeavesTheNextBookmarkAlone() {
        val first = bookmark("m1", 60_000, "Start")
        val second = bookmark("m2", 120_000, "Later")
        val list = mutableStateOf(listOf(first, second))
        val deleted = mutableListOf<String>()
        rows({ list.value }, BookmarkActions(onRename = { _, _ -> }, onDelete = { deleted += it.id }))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("bookmark.60000").performTouchInput { swipeLeft() }
        // An earlier deletion of the same bookmark lands while the row is still sliding away.
        list.value = listOf(second)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("the bookmark that moved up was not deleted", "m2" !in deleted)
        val row = compose.onNodeWithTag("bookmark.120000").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the second row at ${row.left}..${row.right} of ${root.width}", row.left >= 0f && row.right <= root.right)
    }
}
