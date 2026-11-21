package audio.own.book.library

import audio.own.kit.audiobookshelf.AudioTrack
import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.AudiobookshelfError
import audio.own.kit.audiobookshelf.Author
import audio.own.kit.audiobookshelf.Bookmark as AbsBookmark
import audio.own.kit.audiobookshelf.DeviceInfo
import audio.own.kit.audiobookshelf.ItemFilter
import audio.own.kit.audiobookshelf.ItemSort
import audio.own.kit.audiobookshelf.LibraryItem
import audio.own.kit.audiobookshelf.MediaProgress
import audio.own.kit.audiobookshelf.Mojibake
import audio.own.kit.audiobookshelf.ProgressUpdate
import audio.own.kit.audiobookshelf.Series
import audio.own.kit.audiobookshelf.allItems
import audio.own.kit.audiobookshelf.authorize
import audio.own.kit.audiobookshelf.authors
import audio.own.kit.audiobookshelf.closeSession
import audio.own.kit.audiobookshelf.collections
import audio.own.kit.audiobookshelf.createBookmark
import audio.own.kit.audiobookshelf.deleteBookmark
import audio.own.kit.audiobookshelf.deleteProgress
import audio.own.kit.audiobookshelf.item
import audio.own.kit.audiobookshelf.items
import audio.own.kit.audiobookshelf.me
import audio.own.kit.audiobookshelf.narrators
import audio.own.kit.audiobookshelf.personalized
import audio.own.kit.audiobookshelf.play
import audio.own.kit.audiobookshelf.progress
import audio.own.kit.audiobookshelf.search
import audio.own.kit.audiobookshelf.series
import audio.own.kit.audiobookshelf.seriesPage
import audio.own.kit.audiobookshelf.syncSession
import audio.own.kit.audiobookshelf.updateBookmark
import audio.own.kit.audiobookshelf.updateProgress
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * An Audiobookshelf server, one book library of it. Lists come from the server page by page;
 * progress and bookmarks are the account's, read from `/api/me`; playing goes through a play
 * session so the server's listening statistics count it.
 */
class AudiobookshelfBookSource(
    override val serverId: String,
    val client: AudiobookshelfClient,
    val libraryId: String,
    val userId: String?,
    private val device: DeviceInfo,
) : BookSource {

    override val capabilities: Set<Capability> = setOf(Capability.SERIES, Capability.COLLECTIONS, Capability.SERVER_SEARCH, Capability.LOWER_BITRATE)

    override val pageSize: Int get() = PAGE_SIZE

    /**
     * The open play session of each book. The server closes a book's session when another opens
     * for it, so only the newest is reported to; a report naming an older one goes in as a plain
     * position instead.
     */
    private val sessions = ConcurrentHashMap<String, String>()

    override suspend fun checkConnection(): ConnectionStatus {
        if (client.tokens.current == null) return ConnectionStatus.REJECTED
        return try {
            client.authorize()
            ConnectionStatus.OK
        } catch (e: AudiobookshelfError) {
            when {
                e is AudiobookshelfError.SignedOut || e is AudiobookshelfError.InvalidCredentials -> ConnectionStatus.REJECTED
                e is AudiobookshelfError.Http && (e.status == 401 || e.status == 403) -> ConnectionStatus.REJECTED
                else -> ConnectionStatus.UNREACHABLE
            }
        }
    }

    // region Browsing

    /** The server's own shelves with its labels; kinds of shelf a book app doesn't show are left out. */
    override suspend fun shelves(): List<Shelf> = translating {
        client.personalized(libraryId).mapNotNull { shelf ->
            when (shelf.type) {
                "book" -> Shelf(shelf.id, shelf.label, Shelf.Content.Books(shelf.books.map(::book)))
                "series" -> Shelf(shelf.id, shelf.label, Shelf.Content.Series(shelf.series.map(::summary)))
                "authors" -> Shelf(shelf.id, shelf.label, Shelf.Content.Authors(shelf.authors.map(::person)))
                else -> null
            }
        }
    }

    override suspend fun books(query: BookQuery, cursor: String?, limit: Int): BooksPage = translating {
        val text = query.text?.trim()
        if (!text.isNullOrEmpty()) {
            // The server's filter has no text; its search does, one page.
            val hits = client.search(libraryId, text, limit = limit).book.map { book(it.libraryItem) }
            return@translating BooksPage(hits, null, hits.size)
        }
        val page = cursor?.toIntOrNull() ?: 0
        val filter: ItemFilter? = when {
            query.seriesId != null -> ItemFilter.SeriesId(query.seriesId)
            query.authorId != null -> ItemFilter.AuthorId(query.authorId)
            query.narratorId != null -> ItemFilter.NarratorName(query.narratorId)
            else -> when (query.filter) {
                BookFilter.ALL -> null
                BookFilter.NOT_STARTED -> ItemFilter.ByProgress(ItemFilter.ProgressState.NOT_STARTED)
                BookFilter.IN_PROGRESS -> ItemFilter.ByProgress(ItemFilter.ProgressState.IN_PROGRESS)
                BookFilter.FINISHED -> ItemFilter.ByProgress(ItemFilter.ProgressState.FINISHED)
                // Audiobookshelf has no favourites; the screens don't offer the filter there.
                BookFilter.FAVOURITES -> return@translating BooksPage(emptyList(), null, 0)
            }
        }
        val sort = if (query.seriesId != null) {
            ItemSort.SEQUENCE
        } else {
            when (query.sort) {
                BookSort.TITLE -> ItemSort.TITLE
                BookSort.AUTHOR -> ItemSort.AUTHOR
                BookSort.ADDED -> ItemSort.ADDED
                BookSort.DURATION -> ItemSort.DURATION
            }
        }
        val answer = client.items(libraryId, page = page, limit = limit, sort = sort, descending = query.descending, filter = filter)
        BooksPage(answer.results.map(::book), if (answer.hasMore) (page + 1).toString() else null, answer.total)
    }

    override suspend fun book(id: String): Book = translating { book(client.item(id)) }

    override suspend fun authors(): List<Person> = translating { client.authors(libraryId).map(::person) }

    /** Narrators have no ids on the server: the name is the id, and what the filter takes. */
    override suspend fun narrators(): List<Person> = translating {
        client.narrators(libraryId).map { Person(id = it.name, name = Mojibake.repair(it.name), bookCount = it.numBooks) }
    }

    override suspend fun series(): List<SeriesSummary> = translating {
        val out = mutableListOf<SeriesSummary>()
        var page = 0
        while (true) {
            val answer = client.seriesPage(libraryId, page = page, limit = 200)
            out += answer.results.map(::summary)
            if (!answer.hasMore || answer.results.isEmpty()) break
            page++
        }
        out
    }

    /** The series' record has no books; they are the items filtered by it, in series order. */
    override suspend fun series(id: String): SeriesSummary = translating {
        val series = client.series(id)
        val books = client.allItems(libraryId, sort = ItemSort.SEQUENCE, filter = ItemFilter.SeriesId(id)).map(::book)
        SeriesSummary(series.id, Mojibake.repair(series.name), books.size, books)
    }

    override suspend fun collections(): List<BookCollection> = translating {
        client.collections(libraryId).map { BookCollection(it.id, Mojibake.repair(it.name), it.description, it.books.map(::book)) }
    }

    override suspend fun search(text: String): SearchResult = translating {
        val answer = client.search(libraryId, text)
        SearchResult(
            books = answer.book.map { book(it.libraryItem) },
            authors = answer.authors.map(::person),
            narrators = answer.narrators.map { Person(id = it.name, name = Mojibake.repair(it.name), bookCount = it.numBooks) },
            series = answer.series.map { SeriesSummary(it.series.id, Mojibake.repair(it.series.name), it.books.size, it.books.map(::book)) },
        )
    }

    // endregion

    // region The listener's state

    override suspend fun progress(bookId: String): ListeningProgress? = translating { client.progress(bookId)?.let(::progress) }

    override suspend fun allProgress(): Map<String, ListeningProgress> = translating {
        client.me().mediaProgress.filter { it.episodeId == null }.associate { it.libraryItemId to progress(it) }
    }

    override suspend fun saveProgress(bookId: String, positionMs: Long, durationMs: Long, finished: Boolean, at: Instant): ListeningProgress = translating {
        client.updateProgress(bookId, ProgressUpdate.of(seconds(positionMs), seconds(durationMs), finished))
        ListeningProgress(positionMs, finished, at)
    }

    override suspend fun clearProgress(bookId: String) = translating {
        client.progress(bookId)?.let { client.deleteProgress(it.id) }
        Unit
    }

    override suspend fun bookmarks(bookId: String): List<Bookmark> = translating {
        client.me().bookmarks.filter { it.libraryItemId == bookId }.map(::bookmark).sortedBy { it.positionMs }
    }

    override suspend fun addBookmark(bookId: String, positionMs: Long, label: String?): Bookmark = translating {
        bookmark(client.createBookmark(bookId, seconds(positionMs), label ?: ""))
    }

    override suspend fun renameBookmark(bookmark: Bookmark, label: String?): Bookmark = translating {
        bookmark(client.updateBookmark(bookmark.bookId, seconds(bookmark.positionMs), label ?: ""))
    }

    override suspend fun deleteBookmark(bookmark: Bookmark) = translating {
        client.deleteBookmark(bookmark.bookId, seconds(bookmark.positionMs))
    }

    override suspend fun favourites(): Set<String> = emptySet()

    override suspend fun setFavourite(bookId: String, on: Boolean): Unit = throw SourceException.Unsupported

    // endregion

    // region Playing

    /**
     * A play session: one part per file, or one HLS playlist when a smaller stream was asked for
     * (the server transcodes to HLS). Opening one for a book closes the book's open one on the
     * server, so a book has one plan at a time.
     */
    override suspend fun playPlan(book: Book, maxBitrateKbps: Int?): PlayPlan = translating {
        val session = client.play(book.id, device, forceTranscode = maxBitrateKbps != null)
        sessions[book.id] = session.id
        val token = client.freshToken()
        val parts = session.audioTracks.map { track ->
            PlayPlan.Part(
                fileId = fileId(track), url = client.mediaUrl(track.contentUrl, token).toString(),
                startMs = ms(track.startOffset), durationMs = ms(track.duration), contentType = track.mimeType, isHls = session.isHls,
            )
        }
        val expires = client.tokens.current?.expiresAtMillis?.takeIf { it != audio.own.kit.audiobookshelf.Tokens.NEVER }?.let(Instant::ofEpochMilli)
        PlayPlan(book.id, parts, session.id, expires, ms(session.currentTime))
    }

    override suspend fun report(report: PlaybackReport): ListeningProgress = translating {
        val seconds = seconds(report.positionMs)
        val duration = seconds(report.durationMs)
        val listened = seconds(report.listenedMs)
        val session = report.sessionId?.takeIf { sessions[report.bookId] == it }
        if (session != null) {
            if (report.closes) {
                client.closeSession(session, seconds, listened, duration)
                sessions.remove(report.bookId, session)
            } else {
                client.syncSession(session, seconds, listened, duration)
            }
            if (report.finished) client.updateProgress(report.bookId, ProgressUpdate.of(seconds, duration, true))
        } else {
            client.updateProgress(report.bookId, ProgressUpdate.of(seconds, duration, report.finished))
        }
        ListeningProgress(report.positionMs, report.finished, report.at)
    }

    /** The original file with a fresh token; an HLS part needs a new plan instead. */
    override suspend fun partUrl(book: Book, fileId: String, startMs: Long?, maxBitrateKbps: Int?): PlayPlan.Part {
        val file = book.files?.firstOrNull { it.id == fileId }
        if (maxBitrateKbps != null || file == null) throw SourceException.Unsupported
        return translating {
            val token = client.freshToken()
            PlayPlan.Part(fileId, client.fileUrl(book.id, fileId, token).toString(), file.startMs, file.durationMs, file.contentType, isHls = false)
        }
    }

    override suspend fun signOut() {
        sessions.clear()
        client.signOut()
    }

    // endregion

    // region From the server's records to the app's

    internal fun book(item: LibraryItem): Book {
        val m = item.media.metadata
        val authors = m.authors?.map(::person) ?: names(m.authorName).map { Person(it, Mojibake.repair(it)) }
        val narrators = (m.narrators ?: names(m.narratorName)).map { Person(it, Mojibake.repair(it)) }
        val series = m.series?.map { SeriesRef(it.id, Mojibake.repair(it.name), it.sequence) } ?: seriesRefs(m.seriesName)
        val files = item.media.tracks?.map { BookFile(fileId(it), Mojibake.repair(it.title), ms(it.duration), ms(it.startOffset), null, it.mimeType) }
        val chapters = item.media.chapters?.map { Chapter(Mojibake.repair(it.title), ms(it.start), ms(it.end)) }
        return Book(
            id = item.id, title = Mojibake.repair(m.title ?: UNTITLED), subtitle = Mojibake.repair(m.subtitle),
            authors = authors, narrators = narrators, series = series, description = m.description,
            durationMs = ms(item.media.duration), addedAt = Instant.ofEpochMilli(item.addedAt),
            publishedYear = m.publishedYear, publisher = m.publisher, genres = m.genres, language = m.language,
            coverUrl = item.media.coverPath?.let { client.coverUrl(item.id, 600).toString() },
            files = files, chapters = chapters, isMissing = item.isMissing,
        )
    }

    internal fun person(author: Author) = Person(author.id, Mojibake.repair(author.name), author.numBooks)

    internal fun summary(series: Series): SeriesSummary {
        val books = series.books.map(::book).sortedWith(compareBy<Book> { it.series.firstOrNull()?.position ?: 0.0 }.thenBy { it.title })
        return SeriesSummary(series.id, Mojibake.repair(series.name), books.size, books)
    }

    // endregion

    companion object {
        /** A page of the library as the screens ask for it. */
        const val PAGE_SIZE = 60

        /** What a book with no title is called; the screens have their own word for it. */
        private const val UNTITLED = "Untitled"

        fun progress(p: MediaProgress) = ListeningProgress(ms(p.currentTime), p.isFinished, Instant.ofEpochMilli(p.lastUpdate))

        fun bookmark(b: AbsBookmark) = Bookmark(
            id = timeKey(b.time), bookId = b.libraryItemId, positionMs = ms(b.time),
            label = b.title.ifEmpty { null }?.let { Mojibake.repair(it) }, createdAt = Instant.ofEpochMilli(b.createdAt),
        )

        /** A bookmark's id is its time in seconds, as the server matches it. */
        fun timeKey(seconds: Double): String =
            if (seconds == Math.rint(seconds) && !seconds.isInfinite()) seconds.toLong().toString() else seconds.toString()

        fun fileId(track: AudioTrack): String = track.ino ?: track.index.toString()

        fun ms(seconds: Double): Long = (seconds * 1000).roundToLong()

        fun seconds(ms: Long): Double = ms / 1000.0

        /** "A, B" on a list row; the detail has the records. */
        fun names(joined: String?): List<String> =
            joined.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** A list row's "Name #1, Other #2": the name is the only id the row has. */
        fun seriesRefs(seriesName: String?): List<SeriesRef> = names(seriesName).map { text ->
            val hash = text.lastIndexOf(" #")
            if (hash >= 0) {
                val name = text.substring(0, hash)
                SeriesRef(name, Mojibake.repair(name), text.substring(hash + 2))
            } else {
                SeriesRef(text, Mojibake.repair(text), null)
            }
        }

        internal suspend fun <T> translating(work: suspend () -> T): T = try {
            work()
        } catch (e: AudiobookshelfError) {
            throw sourceError(e)
        }

        fun sourceError(e: AudiobookshelfError): SourceException = when (e) {
            AudiobookshelfError.InvalidCredentials, AudiobookshelfError.SignedOut -> SourceException.SignInRejected
            is AudiobookshelfError.Http -> when (e.status) {
                401 -> SourceException.SignInRejected
                403 -> SourceException.NotAllowed
                404 -> SourceException.NotFound
                else -> e.serverMessage?.let { SourceException.Server(it) } ?: SourceException.Http(e.status)
            }
            is AudiobookshelfError.RateLimited -> SourceException.Http(429)
            AudiobookshelfError.Decoding -> SourceException.Unreadable
            is AudiobookshelfError.Network -> SourceException.Network
            AudiobookshelfError.Offline -> SourceException.Offline
        }
    }
}
