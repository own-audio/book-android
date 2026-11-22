package audio.own.book.library

import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineBookSourceTest {
    private fun book(id: String, title: String, author: String, series: SeriesRef? = null, added: Long = 0, duration: Long = 1000) = Book(
        id = id, title = title, authors = listOf(Person("a-$author", author)), series = listOfNotNull(series),
        durationMs = duration, addedAt = Instant.ofEpochSecond(added),
        files = listOf(BookFile("$id-f1", null, duration, 0)),
    )

    private val dune = book("1", "Dune", "Herbert", SeriesRef("s1", "Dune", "1"), added = 3, duration = 500)
    private val messiah = book("2", "Dune Messiah", "Herbert", SeriesRef("s1", "Dune", "2"), added = 1, duration = 300)
    private val hobbit = book("3", "The Hobbit", "Tolkien", added = 2, duration = 900)

    private class Inner(
        val progress: Map<String, ListeningProgress> = emptyMap(),
        val catalogue: Boolean = false,
    ) : BookSource {
        var asked = mutableListOf<String>()
        override val serverId = "server"
        override val capabilities = setOf(Capability.SERIES)
        override suspend fun checkConnection() = ConnectionStatus.OK
        override suspend fun shelves(): List<Shelf> = listOf(Shelf("server", "Server", Shelf.Content.Books(emptyList()))).also { asked += "shelves" }
        override suspend fun books(query: BookQuery, cursor: String?, limit: Int) = BooksPage(emptyList(), null, 0).also { asked += "books" }
        override suspend fun book(id: String): Book = throw SourceException.Offline
        override suspend fun authors(): List<Person> = emptyList()
        override suspend fun narrators(): List<Person> = emptyList()
        override suspend fun series(): List<SeriesSummary> = emptyList()
        override suspend fun series(id: String): SeriesSummary = throw SourceException.Offline
        override suspend fun collections(): List<BookCollection> = listOf(BookCollection("c", "Favourites", null, listOf(dune(), hobbitStub())))
        override suspend fun search(text: String) = SearchResult()
        override suspend fun progress(bookId: String) = progress[bookId]
        override suspend fun allProgress() = progress
        override suspend fun saveProgress(bookId: String, positionMs: Long, durationMs: Long, finished: Boolean, at: Instant) = error("unused")
        override suspend fun clearProgress(bookId: String) = error("unused")
        override suspend fun bookmarks(bookId: String): List<Bookmark> = emptyList()
        override suspend fun addBookmark(bookId: String, positionMs: Long, label: String?) = error("unused")
        override suspend fun renameBookmark(bookmark: Bookmark, label: String?) = error("unused")
        override suspend fun deleteBookmark(bookmark: Bookmark) = error("unused")
        override suspend fun favourites(): Set<String> = emptySet()
        override suspend fun setFavourite(bookId: String, on: Boolean) = error("unused")
        override suspend fun playPlan(book: Book, maxBitrateKbps: Int?) = error("unused")
        override suspend fun report(report: PlaybackReport) = error("unused")
        override suspend fun partUrl(book: Book, fileId: String, startMs: Long?, maxBitrateKbps: Int?) = error("unused")
        override suspend fun signOut() = Unit

        private fun dune() = Book(id = "1", title = "Dune", durationMs = 1, addedAt = Instant.EPOCH)
        private fun hobbitStub() = Book(id = "9", title = "Not here", durationMs = 1, addedAt = Instant.EPOCH)
    }

    private fun source(inner: BookSource, offline: Boolean = true, whole: Boolean = false) =
        OfflineBookSource(inner, { listOf(dune, messiah, hobbit) }, { offline }, { whole }, Locale.ENGLISH)

    @Test
    fun `online, everything comes from the server`() = runTest {
        val inner = Inner()
        val source = source(inner, offline = false)
        assertEquals("server", source.shelves().single().id)
        source.books()
        assertEquals(listOf("shelves", "books"), inner.asked)
    }

    @Test
    fun `offline, the downloads are sorted and filtered here`() = runTest {
        val progress = mapOf("1" to ListeningProgress(100, false, Instant.ofEpochSecond(5)), "3" to ListeningProgress(900, true, Instant.ofEpochSecond(6)))
        val source = source(Inner(progress))
        assertEquals(listOf("Dune", "Dune Messiah", "The Hobbit"), source.books().books.map { it.title })
        assertEquals(listOf("Dune", "The Hobbit", "Dune Messiah"), source.books(BookQuery(sort = BookSort.ADDED, descending = true)).books.map { it.title })
        assertEquals(listOf("2", "1", "3"), source.books(BookQuery(sort = BookSort.DURATION)).books.map { it.id })
        assertEquals(listOf("1"), source.books(BookQuery(filter = BookFilter.IN_PROGRESS)).books.map { it.id })
        assertEquals(listOf("3"), source.books(BookQuery(filter = BookFilter.FINISHED)).books.map { it.id })
        assertEquals(listOf("2"), source.books(BookQuery(filter = BookFilter.NOT_STARTED)).books.map { it.id })
        assertEquals(listOf("3"), source.books(BookQuery(authorId = "a-Tolkien")).books.map { it.id })
        assertEquals(listOf("1", "2"), source.books(BookQuery(text = "dune")).books.map { it.id })
    }

    @Test
    fun `offline pages through the downloads`() = runTest {
        val source = source(Inner())
        val first = source.books(BookQuery(), null, 2)
        assertEquals(2, first.books.size)
        assertEquals(3, first.total)
        val second = source.books(BookQuery(), first.next, 2)
        assertEquals(listOf("The Hobbit"), second.books.map { it.title })
        assertNull(second.next)
    }

    @Test
    fun `offline Home shows Continue Listening and On This Device`() = runTest {
        val source = source(Inner(mapOf("2" to ListeningProgress(10, false, Instant.EPOCH))))
        val shelves = source.shelves()
        assertEquals(listOf(OfflineBookSource.CONTINUE_LISTENING, OfflineBookSource.ON_THIS_DEVICE), shelves.map { it.id })
        assertEquals(listOf("2"), (shelves[0].content as Shelf.Content.Books).books.map { it.id })
    }

    @Test
    fun `offline series, people, collections and search come from the downloads`() = runTest {
        val source = source(Inner())
        assertEquals(listOf("Dune" to 2), source.series().map { it.name to it.bookCount })
        assertEquals(listOf("1", "2"), source.series("s1").books!!.map { it.id })
        assertEquals(listOf("Herbert" to 2, "Tolkien" to 1), source.authors().map { it.name to it.bookCount })
        assertEquals(listOf(listOf("1")), source.collections().map { c -> c.books.map { it.id } })
        val found = source.search("tolk")
        assertEquals(listOf("Tolkien"), found.authors.map { it.name })
        assertEquals(listOf("3"), found.books.map { it.id })
        assertEquals("Dune", source.book("1").title)
    }

    @Test
    fun `whole library offline needs an own audio catalogue`() = runTest {
        // Not a change-feed source: downloads only, even with the setting on.
        assertTrue(source(Inner(), whole = true).isLimited())
    }
}
