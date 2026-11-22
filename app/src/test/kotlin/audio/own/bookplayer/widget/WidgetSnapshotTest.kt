package audio.own.bookplayer.widget

import android.net.Uri
import audio.own.book.library.ListeningProgress
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.browse.Samples
import audio.own.bookplayer.playback.PlayRequests
import audio.own.bookplayer.playback.PlaybackUi
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The widget's snapshot and the `ownbook://` links it opens, as `WidgetSnapshotTests` and `DeepLinkTests` on iOS. */
@RunWith(RobolectricTestRunner::class)
class WidgetSnapshotTest {
    private val shelf = listOf(Samples.pride, Samples.emma, Samples.hound).map { WidgetSnapshot.ShelfBook("s1", it.copy(coverUrl = "https://books.example/${it.id}.jpg")) }
    private val progress = mapOf(
        "b1" to ListeningProgress(Samples.pride.durationMs / 3, false, Instant.EPOCH),
        "b3" to ListeningProgress(Samples.hound.durationMs, true, Instant.EPOCH),
    )

    @Test
    fun thePlayingBookComesFirstThenTheShelfThreeAtMost() {
        val playing = PlaybackUi(bookId = "s2|x9", serverId = "s2", title = "Emma", author = "Jane Austen", positionMs = 30 * 60_000L + 20_000, durationMs = 2 * 3_600_000L)
        val picked = WidgetSnapshot.pick(playing, "https://other.example/x9.jpg", shelf) { _, id -> progress[id] }
        assertEquals(listOf("s2|x9", "s1|b1", "s1|b2"), picked.map { it.entry.id })
        // Time left to the minute and the fraction to the percent: ticks don't rewrite the file.
        assertEquals(90 * 60_000L, picked[0].entry.remainingMs)
        assertEquals(0.25, picked[0].entry.fraction, 0.0)
        assertEquals(0.33, picked[1].entry.fraction, 0.0)
        assertEquals(0.0, picked[2].entry.fraction, 0.0)
        assertEquals("https://other.example/x9.jpg", picked[0].coverUrl)
        assertEquals(WidgetSnapshot.coverName("s1|b1"), picked[1].entry.cover)
    }

    @Test
    fun aBookPlayingFromTheShelfIsListedOnce() {
        val playing = PlaybackUi(bookId = "s1|b2", serverId = "s1", title = "Emma", durationMs = Samples.emma.durationMs)
        val picked = WidgetSnapshot.pick(playing, null, shelf) { _, id -> progress[id] }
        assertEquals(listOf("s1|b2", "s1|b1", "s1|b3"), picked.map { it.entry.id })
        assertEquals("https://books.example/b2.jpg", picked[0].coverUrl)
        // A finished book on the shelf counts from the start.
        assertEquals(0.0, picked[2].entry.fraction, 0.0)
    }

    @Test
    fun theFileIsWrittenOnlyWhenTheEntriesChange() = runBlocking {
        val folder = Files.createTempDirectory("widget").toFile()
        val store = WidgetStore(folder)
        val picked = WidgetSnapshot.pick(PlaybackUi(), null, shelf) { _, id -> progress[id] }
        var pictures = 0
        val jpeg: suspend (WidgetSnapshot.Picked) -> ByteArray? = { pictures++; byteArrayOf(1, 2, 3) }
        assertTrue(store.save(picked, 1, jpeg))
        assertEquals(3, pictures)
        assertFalse(store.save(picked, 2, jpeg))
        assertEquals(3, pictures)
        // One book fewer: its cover leaves with it.
        assertTrue(store.save(picked.take(2), 3, jpeg))
        assertFalse(File(folder, WidgetSnapshot.coverName("s1|b3")).exists())
        assertEquals(2, store.load()?.entries?.size)
    }

    @Test
    fun aCoverThatDoesNotLoadLeavesTheEntryWithout() = runBlocking {
        val store = WidgetStore(Files.createTempDirectory("widget").toFile())
        val picked = WidgetSnapshot.pick(PlaybackUi(), null, shelf.take(1)) { _, _ -> null }
        assertTrue(store.save(picked, 1) { null })
        assertNull(store.load()?.entries?.single()?.cover)
    }

    @Test
    fun theUpdaterRedrawsOnlyForNewEntries() = runBlocking {
        val playback = MutableStateFlow(PlaybackUi())
        var books = shelf.take(1)
        var redraws = 0
        val updater = WidgetUpdater(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            store = WidgetStore(Files.createTempDirectory("widget").toFile()),
            playback = playback,
            playingCoverUrl = { null },
            shelf = { books },
            progress = { progress },
            log = AppLog(Files.createTempDirectory("logs").toFile()),
            jpeg = { null },
            redraw = { redraws++ },
            hasWidgets = { true },
        )
        updater.refresh()
        updater.refresh()
        assertEquals(1, redraws)
        books = shelf
        updater.refresh()
        assertEquals(2, redraws)
    }

    @Test
    fun linksCarryTheCompositeIdEscaped() {
        val link = WidgetSnapshot.playLink("srv-1|book/7#a")
        assertEquals("ownbook", link.scheme)
        assertEquals("play", link.host)
        assertFalse(link.toString().contains("|"))
        assertEquals("srv-1|book/7#a", WidgetSnapshot.bookId(link))
        assertEquals("srv-1|b1", WidgetSnapshot.bookId(Uri.parse("ownbook://play/srv-1%7Cb1")))
        assertEquals("b1", WidgetSnapshot.bookId(Uri.parse("ownbook://play/b1")))
        assertNull(WidgetSnapshot.bookId(Uri.parse("ownbook://open/b1")))
        assertNull(WidgetSnapshot.bookId(Uri.parse("ownpodcast://play/b1")))
        assertNull(WidgetSnapshot.bookId(Uri.parse("ownbook://play/")))
    }

    @Test
    fun aBareIdMeansTheServerShown() {
        assertEquals("s1|b1", PlayRequests.resolve("b1", "s1")?.value)
        assertEquals("s2|b1", PlayRequests.resolve("s2|b1", "s1")?.value)
        assertNull(PlayRequests.resolve("b1", null))
        assertNull(PlayRequests.resolve("", "s1"))
    }
}
