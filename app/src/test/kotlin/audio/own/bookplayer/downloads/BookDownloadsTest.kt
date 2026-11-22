package audio.own.bookplayer.downloads

import audio.own.book.library.Book
import audio.own.book.library.BookFile
import audio.own.book.library.Chapter
import audio.own.book.library.Person
import audio.own.book.library.SeriesRef
import audio.own.book.library.Visibility
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.eventually
import audio.own.bookplayer.playback.PlaybackPrefs
import audio.own.core.downloads.DownloadEntity
import audio.own.core.downloads.DownloadFetcher
import audio.own.core.downloads.DownloadScheduler
import audio.own.core.downloads.DownloadsDatabase
import audio.own.player.core.PlaybackLink
import audio.own.player.core.ProxyMemory
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Books as the downloads index keeps them: one entry per file, the state per book, Keep at Most by book. */
@RunWith(RobolectricTestRunner::class)
class BookDownloadsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = DownloadsDatabase.inMemory(context)
    private val root = File(context.filesDir, "book-downloads-${System.nanoTime()}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = MutableStateFlow(PlaybackPrefs())
    private var playing: String? = null
    private val finished = mutableSetOf<String>()
    private val scheduler = object : DownloadScheduler {
        val queued = mutableListOf<String>()
        override suspend fun active(): Set<String> = queued.toSet()
        override fun enqueue(key: String, wifiOnly: Boolean, replace: Boolean) {
            if (key !in queued) queued += key
        }
        override fun cancel(key: String) {
            queued -= key
        }
        override fun cancelAll() = queued.clear()
    }
    private val downloads = BookDownloads(
        db = db, root = root, scheduler = scheduler,
        fetcher = DownloadFetcher(OkHttpClient(), ProxyMemory()),
        sourceOf = { null }, serversLoaded = {}, prefs = prefs,
        playing = { playing }, isFinished = { it in finished },
        log = AppLog(File(root, "logs")), scope = scope,
        linkResolver = { PlaybackLink("http://localhost/none") },
        holdingsDelayMs = 60_000,
    )

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        root.deleteRecursively()
    }

    private fun book(id: String, files: Int = 2, size: Long? = 100) = Book(
        id = id, title = "Book $id", authors = listOf(Person("a1", "Author")), narrators = listOf(Person("n1", "Reader")),
        series = listOf(SeriesRef("s1", "Series", "1")), description = "About it", durationMs = files * 1_000L,
        addedAt = Instant.ofEpochMilli(1_000), coverUrl = "https://example.com/c.jpg",
        files = (0 until files).map { BookFile("f$it", "Part $it", 1_000, it * 1_000L, size, "audio/mpeg") },
        chapters = listOf(Chapter("One", 0, files * 1_000L)), visibility = Visibility.FAMILY,
    )

    /** Marks one file downloaded with [bytes], as a worker would. */
    private suspend fun finish(composite: String, fileId: String, bytes: Long = 100, at: Long = 10) {
        val key = "$composite#$fileId"
        val item = db.downloads().item(key)!!
        db.downloads().upsert(item.copy(state = DownloadEntity.DONE, fileName = "x/$fileId.mp3", bytes = bytes, downloadedAt = at))
    }

    @Test
    fun `the record survives the index with every field the screens need`() {
        val b = book("b1")
        assertEquals(b, BookRecordJson.decode(BookRecordJson.encode(b)))
        assertNull(BookRecordJson.decode("not json"))
    }

    @Test
    fun `a book is one entry per file, and done when every file is`() = runBlocking {
        downloads.download("srv", book("b1"))
        val keys = db.downloads().items().map { it.key }
        assertEquals(listOf("srv|b1#f0", "srv|b1#f1"), keys)
        assertTrue(db.downloads().items().all { it.itemId == "srv|b1" && it.title == "Book b1" && it.extensionHint == "mp3" })
        eventually { downloads.state.value.state("srv|b1") is DownloadState.Downloading }

        finish("srv|b1", "f0")
        eventually { (downloads.state.value.state("srv|b1") as? DownloadState.Downloading)?.fraction == 0.5f }

        finish("srv|b1", "f1", at = 20)
        eventually { downloads.state.value.state("srv|b1") == DownloadState.Downloaded }
        val entry = downloads.state.value.books.getValue("srv|b1")
        assertEquals(200L, entry.bytes)
        assertEquals(20L, entry.finishedAt)
        assertEquals(listOf("b1"), downloads.state.value.downloadedBooks("srv").map { it.id })
        assertEquals("Book b1", downloads.downloadedBook("srv|b1")?.title)
    }

    @Test
    fun `a failed file makes the book failed once nothing else is coming, and retry queues it again`() = runBlocking {
        downloads.download("srv", book("b1"))
        finish("srv|b1", "f0")
        val item = db.downloads().item("srv|b1#f1")!!
        db.downloads().upsert(item.copy(state = DownloadEntity.FAILED, error = "http_404"))
        eventually { downloads.state.value.state("srv|b1") == DownloadState.Failed("http_404") }
        assertEquals(1, downloads.state.value.failedCount)

        downloads.retryFailed()
        eventually { downloads.state.value.state("srv|b1") is DownloadState.Downloading }
    }

    @Test
    fun `removing a book removes every file of it, and nothing else`() = runBlocking {
        downloads.download("srv", book("b1"))
        downloads.download("srv", book("b2", files = 1))
        eventually { kotlinx.coroutines.runBlocking { db.downloads().items().size } == 3 }
        downloads.remove("srv|b1")
        eventually { kotlinx.coroutines.runBlocking { db.downloads().items().size } == 1 }
        assertEquals(listOf("srv|b2#f0"), db.downloads().items().map { it.key })
    }

    @Test
    fun `a whole set skips the books already here`() = runBlocking {
        downloads.download("srv", book("b1"))
        eventually { downloads.state.value.books.containsKey("srv|b1") }
        downloads.downloadAll("srv", listOf(book("b1"), book("b2", files = 1)))
        assertEquals(3, db.downloads().items().size)
    }

    @Test
    fun `over Keep at Most, finished books go first, then the least recently played, never the playing one`() = runBlocking {
        val gb = 1L shl 30
        for (id in listOf("finished", "old", "playing", "recent")) {
            downloads.download("srv", book(id, files = 1))
            finish("srv|$id", "f0", bytes = gb / 2, at = 100)
        }
        db.downloads().notePlayed("srv|old#f0", 200)
        db.downloads().notePlayed("srv|recent#f0", 900)
        db.downloads().notePlayed("srv|playing#f0", 100)
        finished += "srv|finished"
        playing = "srv|playing"
        eventually { downloads.state.value.books.values.count { it.isComplete } == 4 && downloads.state.value.books["srv|recent"]?.lastPlayedAt == 900L }

        prefs.value = PlaybackPrefs(keepAtMostGb = 1)
        downloads.enforceCap()
        eventually { downloads.state.value.books.size == 2 }
        assertEquals(setOf("srv|playing", "srv|recent"), downloads.state.value.books.keys)
    }

    @Test
    fun `no limit keeps everything`() = runBlocking {
        downloads.download("srv", book("b1", files = 1))
        finish("srv|b1", "f0", bytes = 5L shl 30)
        downloads.enforceCap()
        assertEquals(1, db.downloads().items().size)
    }

    @Test
    fun `content types name the file where the bytes don't`() {
        assertEquals("mp3", BookDownloads.extensionOf("audio/mpeg"))
        assertEquals("m4a", BookDownloads.extensionOf("audio/mp4; codecs=mp4a"))
        assertEquals("flac", BookDownloads.extensionOf("audio/flac"))
        assertNull(BookDownloads.extensionOf("application/octet-stream"))
        assertNull(BookDownloads.extensionOf(null))
    }
}
