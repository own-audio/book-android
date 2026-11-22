package audio.own.book.library

import java.time.Instant
import java.util.Locale

/**
 * One server's library as the screens read it: the server's own while online; offline, only the
 * books on this device ([downloaded]), sorted, filtered and searched here because there is no
 * server to ask. With Whole Library When Offline, an own.audio server's whole catalogue on the
 * phone is listed instead (book-ios `BooksListView.filtered`, `AppModel.showsWholeLibraryOffline`).
 *
 * The listener's state (progress, bookmarks, favourites) and playing always go to [inner], which
 * answers from what it kept or says it is offline.
 */
class OfflineBookSource(
    val inner: BookSource,
    /** This server's downloaded books, newest download first, with files and chapters. */
    private val downloaded: () -> List<Book>,
    private val isOffline: () -> Boolean,
    /** The listener's Whole Library When Offline. */
    private val wholeLibrary: () -> Boolean = { false },
    locale: Locale = Locale.getDefault(),
) : BookSource, BookSharing {
    private val order = NaturalOrder(locale)

    override val serverId: String get() = inner.serverId
    override val capabilities: Set<Capability> get() = inner.capabilities
    override val pageSize: Int get() = inner.pageSize

    /** Offline and listing downloads only: the whole catalogue needs an own.audio copy on the phone. */
    suspend fun isLimited(): Boolean {
        if (!isOffline()) return false
        if (!wholeLibrary()) return true
        val catalogue = inner as? ChangeFeedCatalogue ?: return true
        return runCatching { catalogue.catalogueSummary() }.getOrNull() == null
    }

    override suspend fun checkConnection(): ConnectionStatus = inner.checkConnection()

    override suspend fun shelves(): List<Shelf> {
        if (!isLimited()) return inner.shelves()
        val books = downloaded()
        val progress = progressOrEmpty()
        val started = books.filter { progress[it.id]?.let { p -> p.positionMs > 0 && !p.finished } == true }
            .sortedByDescending { progress[it.id]?.updatedAt ?: Instant.EPOCH }
        return listOf(
            Shelf(CONTINUE_LISTENING, "Continue Listening", Shelf.Content.Books(started)),
            Shelf(ON_THIS_DEVICE, "On This Device", Shelf.Content.Books(books)),
        ).filterNot { it.isEmpty }
    }

    override suspend fun books(query: BookQuery, cursor: String?, limit: Int): BooksPage {
        if (!isLimited()) return inner.books(query, cursor, limit)
        val favourites = if (query.filter == BookFilter.FAVOURITES) runCatching { inner.favourites() }.getOrDefault(emptySet()) else emptySet()
        val books = filtered(downloaded(), query, progressOrEmpty(), favourites, order)
        val offset = cursor?.toIntOrNull() ?: 0
        val page = books.drop(offset).take(limit)
        return BooksPage(page, if (offset + limit < books.size) (offset + limit).toString() else null, books.size)
    }

    /** A downloaded book's record answers offline; online the server's detail is fresher. */
    override suspend fun book(id: String): Book {
        val local = downloaded().firstOrNull { it.id == id }
        if (local != null && isOffline()) return local
        return try {
            inner.book(id)
        } catch (e: SourceException) {
            local ?: throw e
        }
    }

    override suspend fun currentCoverUrl(bookId: String): String? = if (isOffline()) null else inner.currentCoverUrl(bookId)

    override suspend fun authors(): List<Person> =
        if (!isLimited()) inner.authors() else people(downloaded().flatMap { it.authors })

    override suspend fun narrators(): List<Person> =
        if (!isLimited()) inner.narrators() else people(downloaded().flatMap { it.narrators })

    override suspend fun series(): List<SeriesSummary> {
        if (!isLimited()) return inner.series()
        val books = downloaded()
        return books.flatMap { it.series }.distinctBy { it.id }
            .map { ref -> SeriesSummary(ref.id, ref.name, books.count { b -> b.series.any { it.id == ref.id } }) }
            .sortedWith(compareBy(order) { it.name })
    }

    override suspend fun series(id: String): SeriesSummary {
        if (!isLimited()) return inner.series(id)
        val books = inSeries(downloaded(), id, order)
        val name = books.firstNotNullOfOrNull { b -> b.series.firstOrNull { it.id == id }?.name } ?: throw SourceException.Offline
        return SeriesSummary(id, name, books.size, books)
    }

    /** Offline, a collection lists the books of it that are here, if the server's list was kept. */
    override suspend fun collections(): List<BookCollection> {
        if (!isLimited()) return inner.collections()
        val here = downloaded().associateBy { it.id }
        return runCatching { inner.collections() }.getOrDefault(emptyList())
            .map { c -> c.copy(books = c.books.mapNotNull { here[it.id] }) }
            .filter { it.books.isNotEmpty() }
    }

    override suspend fun search(text: String): SearchResult {
        if (!isLimited()) return inner.search(text)
        val needle = text.trim()
        if (needle.isEmpty()) return SearchResult()
        val books = downloaded()
        return SearchResult(
            books = filtered(books, BookQuery(text = needle), emptyMap(), emptySet(), order),
            authors = people(books.flatMap { it.authors }).filter { it.name.containsText(needle) },
            narrators = people(books.flatMap { it.narrators }).filter { it.name.containsText(needle) },
            series = series().filter { it.name.containsText(needle) },
        )
    }

    override suspend fun progress(bookId: String): ListeningProgress? = inner.progress(bookId)
    override suspend fun allProgress(): Map<String, ListeningProgress> = inner.allProgress()
    override suspend fun saveProgress(bookId: String, positionMs: Long, durationMs: Long, finished: Boolean, at: Instant): ListeningProgress =
        inner.saveProgress(bookId, positionMs, durationMs, finished, at)
    override suspend fun clearProgress(bookId: String) = inner.clearProgress(bookId)
    override suspend fun bookmarks(bookId: String): List<Bookmark> = inner.bookmarks(bookId)
    override suspend fun addBookmark(bookId: String, positionMs: Long, label: String?): Bookmark = inner.addBookmark(bookId, positionMs, label)
    override suspend fun renameBookmark(bookmark: Bookmark, label: String?): Bookmark = inner.renameBookmark(bookmark, label)
    override suspend fun deleteBookmark(bookmark: Bookmark) = inner.deleteBookmark(bookmark)
    override suspend fun favourites(): Set<String> = inner.favourites()
    override suspend fun setFavourite(bookId: String, on: Boolean) = inner.setFavourite(bookId, on)
    override suspend fun playPlan(book: Book, maxBitrateKbps: Int?): PlayPlan = inner.playPlan(book, maxBitrateKbps)
    override suspend fun report(report: PlaybackReport): ListeningProgress? = inner.report(report)
    override suspend fun partUrl(book: Book, fileId: String, startMs: Long?, maxBitrateKbps: Int?): PlayPlan.Part =
        inner.partUrl(book, fileId, startMs, maxBitrateKbps)
    override suspend fun signOut() = inner.signOut()

    override fun canShare(book: Book): Boolean = (inner as? BookSharing)?.canShare(book) == true

    override suspend fun setShared(bookId: String, shared: Boolean) {
        (inner as? BookSharing ?: throw SourceException.Unsupported).setShared(bookId, shared)
    }

    private suspend fun progressOrEmpty(): Map<String, ListeningProgress> = runCatching { inner.allProgress() }.getOrDefault(emptyMap())

    private fun people(all: List<Person>): List<Person> =
        all.groupBy { it.id }.map { (_, same) -> same.first().copy(bookCount = same.size) }.sortedWith(compareBy(order) { it.name })

    companion object {
        const val CONTINUE_LISTENING = "continue-listening"

        /** Home's shelf of the downloaded books while offline. */
        const val ON_THIS_DEVICE = "downloaded"

        /** [all] as [query] asks, sorted as the servers sort. */
        fun filtered(
            all: List<Book>,
            query: BookQuery,
            progress: Map<String, ListeningProgress>,
            favourites: Set<String>,
            order: Comparator<String> = NaturalOrder(),
        ): List<Book> {
            var books = all
            query.authorId?.let { id -> books = books.filter { b -> b.authors.any { it.id == id } } }
            query.narratorId?.let { id -> books = books.filter { b -> b.narrators.any { it.id == id } } }
            query.seriesId?.let { id -> return inSeries(books, id, order) }
            query.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                books = books.filter { it.title.containsText(text) || it.authorLine.containsText(text) || it.narratorLine.containsText(text) }
            }
            books = when (query.filter) {
                BookFilter.ALL -> books
                BookFilter.NOT_STARTED -> books.filter { progress[it.id].let { p -> p == null || (p.positionMs <= 0 && !p.finished) } }
                BookFilter.IN_PROGRESS -> books.filter { progress[it.id]?.let { p -> p.positionMs > 0 && !p.finished } == true }
                BookFilter.FINISHED -> books.filter { progress[it.id]?.finished == true }
                BookFilter.FAVOURITES -> books.filter { it.id in favourites }
            }
            val comparator: Comparator<Book> = when (query.sort) {
                BookSort.TITLE -> compareBy(order) { it.title }
                BookSort.AUTHOR -> compareBy<Book, String>(order) { it.authorLine }.thenBy(order) { it.title }
                BookSort.ADDED -> compareBy { it.addedAt }
                BookSort.DURATION -> compareBy { it.durationMs }
            }
            return books.sortedWith(if (query.descending) comparator.reversed() else comparator)
        }

        /** A series' books in its order: by number, then title. */
        fun inSeries(books: List<Book>, seriesId: String, order: Comparator<String> = NaturalOrder()): List<Book> =
            books.filter { b -> b.series.any { it.id == seriesId } }.sortedWith(
                compareBy<Book, Double?>(nullsLast()) { b -> b.series.firstOrNull { it.id == seriesId }?.position }.thenBy(order) { it.title },
            )
    }
}
