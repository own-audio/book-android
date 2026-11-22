package audio.own.bookplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import audio.own.book.library.Book
import audio.own.book.library.BookSource
import audio.own.book.library.ListeningProgress
import audio.own.book.library.SourceException
import audio.own.bookplayer.browse.FakeBookSource
import audio.own.bookplayer.browse.Samples
import audio.own.player.spoken.SpokenChapter
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The car's browse tree, as `CarPlayContentTests` checks book-ios's. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class AutoLibraryTest {
    private var source: BookSource? = FakeBookSource()
    private var serverId: String? = "s1"
    private var downloads: List<Book> = emptyList()
    private var playing = PlaybackUi()

    private val library = AutoLibrary(
        library = object : AutoLibrary.Library {
            override val serverId: String? get() = this@AutoLibraryTest.serverId
            override val source: BookSource? get() = this@AutoLibraryTest.source
            override fun downloaded(serverId: String) = downloads
            override val playing: PlaybackUi get() = this@AutoLibraryTest.playing
            override suspend fun progress(serverId: String) = Samples.progress
        },
        words = object : AutoLibrary.Words {
            override fun title(entry: AutoContent.RootEntry) = entry.name
            override val chapters = "Chapters"
            override val addServer = "Add a server on your phone"
            override val nothingStarted = "Nothing started yet"
            override val nothingDownloaded = "Nothing downloaded"
            override val nothingHere = "Nothing here yet"
            override val noChapters = "No chapters"
            override fun books(count: Int) = "$count books"
            override fun chapter(number: Int) = "Chapter $number"
            override fun failure(error: Throwable) = "Failed: ${error.javaClass.simpleName}"
        },
        artwork = { _, _ -> null },
    )

    private fun titles(parent: String) = runBlocking { library.children(parent) }.map { it.mediaMetadata.title.toString() }

    @Test
    fun rootPutsContinueListeningAndDownloadsFirst() {
        assertEquals(listOf("CONTINUE_LISTENING", "DOWNLOADS", "LIBRARY", "SERIES", "AUTHORS"), titles(AutoContent.ROOT))
        val root = runBlocking { library.children(AutoContent.ROOT) }
        assertTrue(root.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
    }

    @Test
    fun withoutAServerTheRootSaysToAddOne() {
        source = null
        serverId = null
        val root = runBlocking { library.children(AutoContent.ROOT) }
        assertEquals(listOf("Add a server on your phone"), root.map { it.mediaMetadata.title.toString() })
        assertFalse(root.single().mediaMetadata.isPlayable == true)
        assertEquals(listOf("Add a server on your phone"), titles(AutoContent.LIBRARY))
    }

    @Test
    fun continueListeningIsTheServersShelf() {
        val rows = runBlocking { library.children(AutoContent.CONTINUE) }
        assertEquals(listOf("s1|b1"), rows.map { it.mediaId })
        val extras = rows.single().mediaMetadata.extras!!
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED, extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
        assertEquals(4.0 / 11.583, extras.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.01)
        assertEquals(Samples.pride, library.known("s1|b1"))
    }

    @Test
    fun downloadsComeFromThePhoneAndSayWhenEmpty() {
        assertEquals(listOf("Nothing downloaded"), titles(AutoContent.DOWNLOADS))
        downloads = listOf(Samples.hound, Samples.emma)
        val rows = runBlocking { library.children(AutoContent.DOWNLOADS) }
        assertEquals(listOf("s1|b3", "s1|b2"), rows.map { it.mediaId })
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED, rows[0].mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
    }

    @Test
    fun listsAreCappedAtAHundred() {
        source = FakeBookSource(books = (1..150).map { Book(id = "x$it", title = "Book $it", durationMs = 1, addedAt = Instant.EPOCH) })
        assertEquals(100, runBlocking { library.children(AutoContent.LIBRARY) }.size)
    }

    @Test
    fun seriesAndAuthorsOpenTheirBooks() {
        val series = runBlocking { library.children(AutoContent.SERIES) }
        assertEquals(listOf("@series/s1", "@series/s2"), series.map { it.mediaId })
        assertEquals("2 books", series[0].mediaMetadata.subtitle.toString())
        assertEquals(listOf("Emma"), titles("@series/s1"))
        val authors = runBlocking { library.children(AutoContent.AUTHORS) }
        assertEquals(listOf("@author/a1", "@author/a2"), authors.map { it.mediaId })
        assertEquals(listOf("Emma", "Pride and Prejudice"), titles("@author/a1"))
    }

    @Test
    fun aFailureIsOneRowThatSaysSo() {
        (source as FakeBookSource).fail = SourceException.Network
        val rows = runBlocking { library.children(AutoContent.LIBRARY) }
        assertEquals(1, rows.size)
        assertTrue(rows.single().mediaMetadata.title.toString().startsWith("Failed"))
        assertFalse(rows.single().mediaMetadata.isPlayable == true)
    }

    @Test
    fun chaptersShowOnlyWhileABookWithChaptersPlays() {
        assertFalse(AutoContent.CHAPTERS in runBlocking { library.children(AutoContent.ROOT) }.map { it.mediaId })
        val chapters = listOf(SpokenChapter("Opening", 0, 60_000), SpokenChapter("", 60_000, 3_725_000), SpokenChapter("End", 3_725_000, 4_000_000))
        // The last book remembered after a relaunch isn't loaded: no chapters to jump to.
        playing = PlaybackUi(bookId = "s1|b1", serverId = "s1", title = "Pride and Prejudice", chapters = chapters, restored = true)
        assertFalse(AutoContent.CHAPTERS in runBlocking { library.children(AutoContent.ROOT) }.map { it.mediaId })
        playing = playing.copy(restored = false, positionMs = 90_000)
        val root = runBlocking { library.children(AutoContent.ROOT) }.map { it.mediaId }
        assertEquals(AutoContent.CHAPTERS, root.last())
        val rows = runBlocking { library.children(AutoContent.CHAPTERS) }
        assertEquals(listOf("Opening", "Chapter 2", "End"), rows.map { it.mediaMetadata.title.toString() })
        assertEquals(listOf("0:00", "1:00", "1:02:05"), rows.map { it.mediaMetadata.subtitle.toString() })
        assertEquals("Pride and Prejudice", rows[1].mediaMetadata.artist.toString())
        assertNull(rows[0].mediaMetadata.artist)
        assertEquals(3_725_000L, library.chapter(2)?.startMs)
    }

    @Test
    fun itemsAreFoundById() {
        assertNotNull(library.item(AutoContent.DOWNLOADS))
        assertNull(library.item("s1|b1"))
        runBlocking { library.children(AutoContent.CONTINUE) }
        assertEquals("Pride and Prejudice", library.item("s1|b1")?.mediaMetadata?.title.toString())
    }

    @Test
    fun speedCyclesThroughThePhonesSteps() {
        var speed = 1f
        val seen = (1..6).map { AutoContent.nextRate(speed).also { speed = it } }
        assertEquals(listOf(1.25f, 1.5f, 1.75f, 2f, 0.75f, 1f), seen)
        assertEquals(0.75f, AutoContent.nextRate(2.5f))
        assertEquals(1f, AutoContent.nextRate(0.8f))
    }

    @Test
    fun progressIsNoneForFinishedOrUnstartedBooks() {
        assertNull(AutoContent.progress(ListeningProgress(0, false, Instant.EPOCH), 100))
        assertNull(AutoContent.progress(ListeningProgress(50, true, Instant.EPOCH), 100))
        assertEquals(0.5, AutoContent.progress(ListeningProgress(50, false, Instant.EPOCH), 100)!!, 0.0)
    }
}
