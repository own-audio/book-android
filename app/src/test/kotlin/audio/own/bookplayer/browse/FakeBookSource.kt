package audio.own.bookplayer.browse

import audio.own.book.library.Book
import audio.own.book.library.BookCollection
import audio.own.book.library.BookFile
import audio.own.book.library.BookFilter
import audio.own.book.library.BookQuery
import audio.own.book.library.BookSort
import audio.own.book.library.BookSource
import audio.own.book.library.Bookmark
import audio.own.book.library.BooksPage
import audio.own.book.library.Capability
import audio.own.book.library.Chapter
import audio.own.book.library.ConnectionStatus
import audio.own.book.library.ListeningProgress
import audio.own.book.library.Person
import audio.own.book.library.PlayPlan
import audio.own.book.library.PlaybackReport
import audio.own.book.library.SearchResult
import audio.own.book.library.SeriesRef
import audio.own.book.library.SeriesSummary
import audio.own.book.library.Shelf
import audio.own.book.library.SourceException
import audio.own.book.library.Visibility
import java.time.Instant

/** Books for the screens and their tests: public-domain titles, made-up ids. */
object Samples {
    private val austen = Person("a1", "Jane Austen", 2)
    private val doyle = Person("a2", "Arthur Conan Doyle", 1)
    private val reader = Person("n1", "Karen Savage", 2)

    private fun files(vararg minutes: Int) = BookFile.timeline(minutes.mapIndexed { i, m -> BookFile.Entry("f$i", "Part ${i + 1}", m * 60_000L, 40_000_000) })

    val pride = Book(
        id = "b1",
        title = "Pride and Prejudice",
        authors = listOf(austen),
        narrators = listOf(reader),
        series = emptyList(),
        description = "<p>It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.</p>",
        durationMs = 11 * 3_600_000L + 35 * 60_000L,
        addedAt = Instant.parse("2026-10-01T10:00:00Z"),
        publishedYear = "1813",
        publisher = "LibriVox",
        genres = listOf("Fiction", "Romance"),
        files = files(240, 240, 215),
        chapters = (0 until 6).map { Chapter("Chapter ${it + 1}", it * 115 * 60_000L, (it + 1) * 115 * 60_000L) },
        visibility = Visibility.FAMILY,
    )
    val emma = Book(
        id = "b2", title = "Emma", authors = listOf(austen), narrators = listOf(reader),
        durationMs = 17 * 3_600_000L, addedAt = Instant.parse("2026-10-05T10:00:00Z"),
        series = listOf(SeriesRef("s1", "Novels", "2")),
    )
    val hound = Book(
        id = "b3", title = "The Hound of the Baskervilles", authors = listOf(doyle),
        durationMs = 6 * 3_600_000L + 20 * 60_000L, addedAt = Instant.parse("2026-11-01T10:00:00Z"),
        series = listOf(SeriesRef("s2", "Sherlock Holmes", "5")),
    )
    val books = listOf(pride, emma, hound) + (4..70).map {
        Book(id = "b$it", title = "Book $it", authors = listOf(doyle), durationMs = it * 600_000L, addedAt = Instant.parse("2026-09-01T10:00:00Z"))
    }
    val progress = mapOf(
        pride.id to ListeningProgress(4 * 3_600_000L, false, Instant.parse("2026-11-20T08:00:00Z")),
        hound.id to ListeningProgress(0, true, Instant.parse("2026-11-10T08:00:00Z")),
    )
}

open class FakeBookSource(
    override val capabilities: Set<Capability> = setOf(Capability.SERIES, Capability.COLLECTIONS, Capability.FAVOURITES),
    val books: List<Book> = Samples.books,
) : BookSource {
    override val serverId = "s1"
    var fail: SourceException? = null
    var favourite = mutableSetOf("b2")
    var failFavourite = false
    val searches = mutableListOf<String>()
    var failChanges: SourceException? = null
    val progress = Samples.progress.toMutableMap()
    val bookmarks = mutableListOf(Bookmark("m1", "b1", 754_000, "The letter", Instant.EPOCH), Bookmark("m2", "b2", 754_000, "The letter", Instant.EPOCH))
    val bookmarkCalls = mutableListOf<String>()
    val pages = mutableListOf<String?>()

    private fun check() = fail?.let { throw it }

    override suspend fun checkConnection() = ConnectionStatus.OK
    override suspend fun shelves(): List<Shelf> {
        check()
        return listOf(
            Shelf("continue-listening", "Continue Listening", Shelf.Content.Books(listOf(Samples.pride))),
            Shelf("recently-added", "Recently Added", Shelf.Content.Books(listOf(Samples.hound, Samples.emma, Samples.pride))),
            Shelf("empty", "Empty", Shelf.Content.Books(emptyList())),
        )
    }

    override suspend fun books(query: BookQuery, cursor: String?, limit: Int): BooksPage {
        check()
        pages += cursor
        var list = books
        if (query.filter == BookFilter.FINISHED) list = list.filter { Samples.progress[it.id]?.finished == true }
        if (query.filter == BookFilter.FAVOURITES) list = list.filter { it.id in favourite }
        query.authorId?.let { id -> list = list.filter { b -> b.authors.any { it.id == id } } }
        list = when (query.sort) {
            BookSort.TITLE -> list.sortedBy { it.title }
            BookSort.DURATION -> list.sortedBy { it.durationMs }
            else -> list
        }
        if (query.descending) list = list.reversed()
        val start = cursor?.toInt() ?: 0
        val end = minOf(list.size, start + limit)
        return BooksPage(list.subList(start, end), if (end < list.size) "$end" else null, list.size)
    }

    override suspend fun book(id: String): Book {
        check()
        return books.firstOrNull { it.id == id } ?: throw SourceException.NotFound
    }

    override suspend fun authors() = listOf(Person("a1", "Jane Austen", 2), Person("a2", "Arthur Conan Doyle", 68))
    override suspend fun narrators() = listOf(Person("n1", "Karen Savage", 2))
    override suspend fun series() = listOf(SeriesSummary("s1", "Novels", 2), SeriesSummary("s2", "Sherlock Holmes", 1))
    override suspend fun series(id: String) = SeriesSummary(id, "Novels", 1, listOf(Samples.emma))
    override suspend fun collections() = listOf(BookCollection("c1", "Bedtime", null, listOf(Samples.emma, Samples.hound)))
    override suspend fun search(text: String): SearchResult {
        searches += text
        check()
        return SearchResult(books = books.filter { it.title.contains(text, ignoreCase = true) })
    }

    override suspend fun progress(bookId: String) = progress[bookId]
    override suspend fun allProgress() = progress.toMap()
    override suspend fun saveProgress(bookId: String, positionMs: Long, durationMs: Long, finished: Boolean, at: Instant): ListeningProgress {
        failChanges?.let { throw it }
        return ListeningProgress(positionMs, finished, at).also { progress[bookId] = it }
    }

    override suspend fun clearProgress(bookId: String) {
        failChanges?.let { throw it }
        progress.remove(bookId)
    }

    override suspend fun bookmarks(bookId: String) = bookmarks.filter { it.bookId == bookId }

    override suspend fun addBookmark(bookId: String, positionMs: Long, label: String?): Bookmark {
        bookmarkCalls += "add $positionMs $label"
        failChanges?.let { throw it }
        return Bookmark("m${bookmarks.size + 1}", bookId, positionMs, label, Instant.EPOCH).also { bookmarks += it }
    }

    override suspend fun renameBookmark(bookmark: Bookmark, label: String?): Bookmark {
        bookmarkCalls += "rename ${bookmark.id} $label"
        failChanges?.let { throw it }
        return bookmark.copy(label = label).also { renamed -> bookmarks.replaceAll { if (it.id == bookmark.id) renamed else it } }
    }

    override suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkCalls += "delete ${bookmark.id}"
        failChanges?.let { throw it }
        bookmarks.removeAll { it.id == bookmark.id }
    }
    override suspend fun favourites(): Set<String> = favourite.toSet()
    override suspend fun setFavourite(bookId: String, on: Boolean) {
        if (failFavourite) throw SourceException.Network
        if (on) favourite += bookId else favourite -= bookId
    }

    override suspend fun playPlan(book: Book, maxBitrateKbps: Int?) = throw SourceException.Unsupported
    override suspend fun report(report: PlaybackReport): ListeningProgress? = null
    override suspend fun partUrl(book: Book, fileId: String, startMs: Long?, maxBitrateKbps: Int?) = throw SourceException.Unsupported
    override suspend fun signOut() = Unit
}

/** An own.audio-like source whose signed-in listener owns [Samples.pride]. */
class SharingFakeSource : FakeBookSource(capabilities = setOf(Capability.FAVOURITES, Capability.FAMILY_SHARING), books = Samples.books.map { if (it.id == "b1") it.copy(ownerId = "me") else it }),
    audio.own.book.library.BookSharing {
    val shared = mutableListOf<Pair<String, Boolean>>()

    override fun canShare(book: Book) = book.ownerId == "me"

    override suspend fun setShared(bookId: String, shared: Boolean) {
        failChanges?.let { throw it }
        this.shared += bookId to shared
    }
}
