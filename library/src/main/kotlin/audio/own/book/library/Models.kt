package audio.own.book.library

import audio.own.core.servers.CompositeId
import audio.own.player.spoken.SpokenChapter
import audio.own.player.spoken.SpokenPart
import java.time.Instant

// The app's records, whatever the server. Positions and durations are milliseconds of book
// time: from the start of the book, across its files. The engine converts to a file and an
// offset at the edge. Ids are the server's own and opaque; where several servers meet the app
// names a book with [BookId].

/**
 * A book named across servers: `"<server id>|<book id>"`. Queues, downloads, covers and
 * positions key on it, so a removed server's data can be found and deleted.
 */
@JvmInline
value class BookId(val value: String) {
    val serverId: String get() = CompositeId.split(value)?.first ?: ""
    val bookId: String get() = CompositeId.split(value)?.second ?: value

    override fun toString(): String = value

    companion object {
        fun of(serverId: String, bookId: String) = BookId(CompositeId.make(serverId, bookId))
    }
}

data class Person(
    /** The server's id, or the name where a server has none (Audiobookshelf's narrators). */
    val id: String,
    val name: String,
    val bookCount: Int? = null,
    val imageUrl: String? = null,
)

/**
 * A book's place in a series. [sequence] is as the server has it ("1", "1.5", "1-3");
 * [position] is the number in it, for sorting.
 */
data class SeriesRef(val id: String, val name: String, val sequence: String?) {
    val position: Double?
        get() {
            val head = sequence?.takeWhile { it.isDigit() || it == '.' || it == ',' }?.replace(',', '.') ?: return null
            return head.toDoubleOrNull()
        }
}

/** One file of a book, in playing order; [startMs] is where it begins in book time. */
data class BookFile(
    val id: String,
    val title: String?,
    val durationMs: Long,
    val startMs: Long,
    val sizeBytes: Long? = null,
    val contentType: String? = null,
) {
    val endMs: Long get() = startMs + durationMs

    /** What a file is before it has a place in the book. */
    data class Entry(val id: String, val title: String?, val durationMs: Long, val sizeBytes: Long? = null, val contentType: String? = null)

    companion object {
        /** Files laid end to end from their durations. */
        fun timeline(files: List<Entry>): List<BookFile> {
            var start = 0L
            return files.map { f -> BookFile(f.id, f.title, f.durationMs, start, f.sizeBytes, f.contentType).also { start += f.durationMs } }
        }
    }
}

data class Chapter(val title: String, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

enum class Visibility(val wire: String) {
    PRIVATE("private"),
    FAMILY("family"),
    ;

    companion object {
        fun of(wire: String?): Visibility? = entries.firstOrNull { it.wire == wire }
    }
}

data class Book(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<Person> = emptyList(),
    val narrators: List<Person> = emptyList(),
    val series: List<SeriesRef> = emptyList(),
    val description: String? = null,
    val durationMs: Long,
    val addedAt: Instant,
    val publishedYear: String? = null,
    val publisher: String? = null,
    val genres: List<String> = emptyList(),
    val language: String? = null,
    val coverUrl: String? = null,
    /** Only from the detail (and own.audio's feed): null means not loaded, empty none. */
    val files: List<BookFile>? = null,
    val chapters: List<Chapter>? = null,
    /** own.audio: whose it is and who sees it. */
    val ownerId: String? = null,
    val visibility: Visibility? = null,
    /** Audiobookshelf: the server lost the folder. */
    val isMissing: Boolean = false,
) {
    val authorLine: String get() = authors.joinToString(", ") { it.name }
    val narratorLine: String get() = narrators.joinToString(", ") { it.name }

    /** The file playing at [positionMs] and the offset in it; the last file when past the end. */
    fun fileAt(positionMs: Long): Pair<BookFile, Long>? {
        val files = files?.takeIf { it.isNotEmpty() } ?: return null
        val file = files.lastOrNull { it.startMs <= positionMs } ?: files.first()
        return file to (positionMs - file.startMs).coerceIn(0, file.durationMs)
    }

    /** Book time of an offset in a file. */
    fun position(fileId: String, offsetMs: Long): Long? = files?.firstOrNull { it.id == fileId }?.let { it.startMs + offsetMs }

    /** The chapters as the spoken engine takes them. */
    fun spokenChapters(): List<SpokenChapter> = chapters.orEmpty().map { SpokenChapter(it.title, it.startMs, it.endMs) }
}

/** Where one listener is in one book. */
data class ListeningProgress(
    val positionMs: Long,
    val finished: Boolean,
    val updatedAt: Instant,
    /** Where other devices left off, where the server keeps that (own.audio). */
    val devices: List<DevicePosition> = emptyList(),
    /**
     * In the answer to a save: false when the server kept a newer position it already had
     * (another device's) instead of the one sent; this progress is then that newer one.
     */
    val applied: Boolean = true,
)

data class DevicePosition(
    val id: String,
    val name: String?,
    val kind: String,
    val positionMs: Long,
    val finished: Boolean,
    val at: Instant,
    val isThisDevice: Boolean,
)

data class Bookmark(
    /** own.audio's id; Audiobookshelf keys bookmarks by time, so the time in seconds. */
    val id: String,
    val bookId: String,
    val positionMs: Long,
    val label: String?,
    val createdAt: Instant,
)

data class SeriesSummary(
    val id: String,
    val name: String,
    val bookCount: Int,
    /** In series order, when loaded. */
    val books: List<Book>? = null,
)

/** Named so it doesn't hide Kotlin's own `Collection`. */
data class BookCollection(
    val id: String,
    val name: String,
    val description: String?,
    val books: List<Book>,
)

/** A row of the home screen. */
data class Shelf(
    /** `continue-listening`, `recently-added`, `listen-again`, `continue-series`, `discover`… */
    val id: String,
    /** The server's label; for the shelves a source builds itself, English, to be replaced by the screen's own words for [id]. */
    val title: String,
    val content: Content,
) {
    sealed interface Content {
        data class Books(val books: List<Book>) : Content
        data class Series(val series: List<SeriesSummary>) : Content
        data class Authors(val authors: List<Person>) : Content
    }

    val isEmpty: Boolean
        get() = when (content) {
            is Content.Books -> content.books.isEmpty()
            is Content.Series -> content.series.isEmpty()
            is Content.Authors -> content.authors.isEmpty()
        }
}

enum class BookSort { TITLE, AUTHOR, ADDED, DURATION }

enum class BookFilter {
    ALL,
    NOT_STARTED,
    IN_PROGRESS,
    FINISHED,

    /** Only where the source has them ([Capability.FAVOURITES]). */
    FAVOURITES,
}

/** What to list: a filter, and at most one of author, narrator or series. */
data class BookQuery(
    val sort: BookSort = BookSort.TITLE,
    val descending: Boolean = false,
    val filter: BookFilter = BookFilter.ALL,
    val authorId: String? = null,
    val narratorId: String? = null,
    val seriesId: String? = null,
    val text: String? = null,
)

data class BooksPage(
    val books: List<Book>,
    /** Opaque; null on the last page. */
    val next: String?,
    val total: Int?,
)

data class SearchResult(
    val books: List<Book> = emptyList(),
    val authors: List<Person> = emptyList(),
    val narrators: List<Person> = emptyList(),
    val series: List<SeriesSummary> = emptyList(),
)

/**
 * How to play a book right now: one part per file (or one HLS playlist), with links that
 * expire; [sessionId] is the server's play session where it keeps one.
 */
data class PlayPlan(
    val bookId: String,
    val parts: List<Part>,
    val sessionId: String?,
    val expiresAt: Instant?,
    /** Where the server says the listener is, for sessions that carry it. */
    val startPositionMs: Long?,
) {
    data class Part(
        val fileId: String,
        val url: String,
        val startMs: Long,
        val durationMs: Long,
        val contentType: String?,
        val isHls: Boolean,
        /** The original file, which answers Range requests; a transcode made as it goes doesn't. */
        val streamsByRange: Boolean = !isHls,
    ) {
        fun spokenPart() = SpokenPart(
            id = fileId, uri = url, durationMs = durationMs, isHls = isHls, streamsByRange = streamsByRange, contentType = contentType,
        )
    }

    /** The parts as the spoken engine's timeline takes them. */
    fun spokenParts(): List<SpokenPart> = parts.map { it.spokenPart() }
}

/** What the player tells the server while and after playing. */
data class PlaybackReport(
    val bookId: String,
    val sessionId: String?,
    val positionMs: Long,
    /** Milliseconds listened since the last report. */
    val listenedMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    /** The device's clock when the position was reached. */
    val at: Instant = Instant.now(),
    /** The last report of this session. */
    val closes: Boolean = false,
)

enum class ConnectionStatus {
    OK,

    /** Reachable, but the sign-in is refused. */
    REJECTED,
    UNREACHABLE,
}

/** The errors screens show, whatever the server. */
sealed class SourceException(message: String) : Exception(message) {
    data object SignInRejected : SourceException("sign-in rejected")
    data object NotAllowed : SourceException("not allowed")
    data object NotFound : SourceException("not found")
    data class Http(val status: Int) : SourceException("HTTP $status")

    /** The answer couldn't be read. */
    data object Unreadable : SourceException("unreadable answer")
    data object Network : SourceException("network")

    /** Offline, and this wasn't saved from an earlier visit. */
    data object Offline : SourceException("offline")

    /** The server doesn't have this feature. */
    data object Unsupported : SourceException("unsupported")

    /** The server's own message. */
    data class Server(val text: String) : SourceException(text)
}
