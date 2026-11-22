package audio.own.bookplayer

import androidx.compose.ui.unit.dp
import audio.own.book.library.Book
import audio.own.book.library.Chapter
import audio.own.bookplayer.book.shownChapters
import audio.own.bookplayer.browse.BooksListViewModel
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.bookplayer.player.portraitCoverSize
import audio.own.bookplayer.ui.LibraryListing
import audio.own.player.spoken.SpokenChapter
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the emulator test of 2026-11-22 found, kept from coming back. */
class EmulatorFindingsTest {
    private val m4b = Book(
        id = "b1", title = "One File", authors = emptyList(), narrators = emptyList(), series = emptyList(),
        durationMs = 600_000, addedAt = Instant.EPOCH, chapters = emptyList(),
    )
    private val playing = PlaybackUi(
        bookId = "s1|b1", serverId = "s1", durationMs = 600_000, hasRealChapters = true,
        chapters = listOf(SpokenChapter("Foreword", 0, 440_000), SpokenChapter("Introduction", 440_000, 600_000)),
    )

    @Test
    fun `the book page lists the chapter marks read from the file of the book that plays`() {
        assertEquals(listOf("Foreword", "Introduction"), shownChapters(m4b, isThis = true, playback = playing).map { it.title })
        assertEquals(440_000L, shownChapters(m4b, isThis = true, playback = playing).first().durationMs)
        assertEquals("another book's marks are not this one's", emptyList<Chapter>(), shownChapters(m4b, isThis = false, playback = playing))
        assertEquals("one chapter per file is no marks", emptyList<Chapter>(), shownChapters(m4b, isThis = true, playback = playing.copy(hasRealChapters = false)))
        val server = m4b.copy(chapters = listOf(Chapter("Server", 0, 600_000)))
        assertEquals("the server's chapters come first", listOf("Server"), shownChapters(server, isThis = true, playback = playing).map { it.title })
    }

    @Test
    fun `the player's cover gives way on a phone too short for it and the tools`() {
        // A 1080 × 2424 phone at 420 dpi: about 789 dp between the top bar and the gesture bar.
        assertEquals(209.dp, portraitCoverSize(789.dp))
        assertEquals(260.dp, portraitCoverSize(1_000.dp))
        assertEquals(160.dp, portraitCoverSize(600.dp))
    }

    @Test
    fun `Whole Library switched while offline is another list`() {
        assertEquals(LibraryListing.ONLINE, LibraryListing.of(offline = false, wholeLibrary = true))
        assertEquals(LibraryListing.DOWNLOADS, LibraryListing.of(offline = true, wholeLibrary = false))
        assertEquals(LibraryListing.WHOLE_CATALOGUE, LibraryListing.of(offline = true, wholeLibrary = true))
        // The list's model remembers what it was loaded for: the screen is gone while Settings is open.
        val list = BooksListViewModel(null)
        assertFalse(list.seesListing(LibraryListing.DOWNLOADS))
        assertFalse(list.seesListing(LibraryListing.DOWNLOADS))
        assertTrue("coming back from Settings with Whole Library on", list.seesListing(LibraryListing.WHOLE_CATALOGUE))
    }
}
