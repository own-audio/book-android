package audio.own.book.library

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * What a server can do beyond the basics every source has (shelves, books, progress, bookmarks,
 * play). Screens ask before offering a feature, so a missing one is hidden, not broken.
 */
enum class Capability {
    SERIES,
    COLLECTIONS,

    /** Search runs on the server; otherwise over the catalogue on the phone. */
    SERVER_SEARCH,
    FAVOURITES,

    /** The server keeps where each device left off, so "continue from the car" is possible. */
    PER_DEVICE_POSITIONS,

    /** Books have an owner and are private or shared with the household. */
    FAMILY_SHARING,
    UPLOAD,

    /** The server says when something changed ([LiveLibraryUpdates]). */
    LIVE_UPDATES,

    /** The whole library comes from a snapshot and a change feed ([ChangeFeedCatalogue]). */
    CHANGE_FEED,

    /** The server makes a smaller stream on request. */
    LOWER_BITRATE,

    /** Narrators are records with ids, not just names. */
    NARRATOR_IDS,
}

/** One server, whatever its kind. Every call may throw [SourceException]. */
interface BookSource {
    /** The [audio.own.core.servers.ServerRecord.id] this source speaks for. */
    val serverId: String
    val capabilities: Set<Capability>

    /** How many books a list asks for at once when the caller doesn't say. */
    val pageSize: Int get() = 100

    suspend fun checkConnection(): ConnectionStatus

    // Browsing
    suspend fun shelves(): List<Shelf>
    suspend fun books(query: BookQuery = BookQuery(), cursor: String? = null, limit: Int = pageSize): BooksPage

    /** With files and chapters. */
    suspend fun book(id: String): Book

    /**
     * The cover's address as the server gives it now, never from a kept copy of the record: what
     * to try when the cover link in hand no longer loads.
     */
    suspend fun currentCoverUrl(bookId: String): String? = runCatching { book(bookId).coverUrl }.getOrNull()
    suspend fun authors(): List<Person>
    suspend fun narrators(): List<Person>
    suspend fun series(): List<SeriesSummary>

    /** With its books in order. */
    suspend fun series(id: String): SeriesSummary
    suspend fun collections(): List<BookCollection>
    suspend fun search(text: String): SearchResult

    // The listener's state
    suspend fun progress(bookId: String): ListeningProgress?

    /** Every book the listener started, by id. */
    suspend fun allProgress(): Map<String, ListeningProgress>

    /** What the server holds after the save: another device's newer position wins. */
    suspend fun saveProgress(bookId: String, positionMs: Long, durationMs: Long, finished: Boolean, at: Instant): ListeningProgress
    suspend fun clearProgress(bookId: String)
    suspend fun bookmarks(bookId: String): List<Bookmark>
    suspend fun addBookmark(bookId: String, positionMs: Long, label: String?): Bookmark
    suspend fun renameBookmark(bookmark: Bookmark, label: String?): Bookmark
    suspend fun deleteBookmark(bookmark: Bookmark)
    suspend fun favourites(): Set<String>
    suspend fun setFavourite(bookId: String, on: Boolean)

    // Playing
    /** [maxBitrateKbps] asks for a smaller stream where the server makes one. */
    suspend fun playPlan(book: Book, maxBitrateKbps: Int? = null): PlayPlan

    /** A position reached while playing; what the server holds afterwards. */
    suspend fun report(report: PlaybackReport): ListeningProgress?

    /** The link for one file again, after the plan's links ran out. */
    suspend fun partUrl(book: Book, fileId: String, startMs: Long? = null, maxBitrateKbps: Int? = null): PlayPlan.Part

    suspend fun signOut()

    fun bookId(id: String): BookId = BookId.of(serverId, id)
}

/** Every page of a list. */
suspend fun BookSource.allBooks(query: BookQuery = BookQuery()): List<Book> {
    val out = mutableListOf<Book>()
    var cursor: String? = null
    do {
        val page = books(query, cursor, 200)
        out += page.books
        cursor = page.next
    } while (cursor != null)
    return out
}

/**
 * A source that keeps the library on the phone up to date from a change feed: the whole library
 * once, then only what changed. Sources without one are browsed page by page on the server.
 */
interface ChangeFeedCatalogue : BookSource {
    /**
     * Brings the copy on the phone up to date: from scratch when there is none, or when the server
     * says the changes since it are too many or too old.
     */
    suspend fun syncCatalogue(): CatalogueSummary

    /** What is on the phone; null before the first sync. */
    suspend fun catalogueSummary(): CatalogueSummary?

    /** Another household's library: the next sync starts from a fresh snapshot. */
    suspend fun resetCatalogue()
}

/** For "Library on This Device": the counts and when it was last brought up to date. */
data class CatalogueSummary(val books: Int, val authors: Int, val syncedAt: Instant?)

/**
 * A source that says when the library changed elsewhere, so the copy on the phone can catch up
 * at once rather than at the next scheduled sync.
 */
interface LiveLibraryUpdates : BookSource {
    /** One element per change, until the connection ends (normally or with an error). */
    fun libraryChanges(): Flow<Unit>
}

/**
 * A source that takes many positions in one request: what piled up while offline goes in one
 * call instead of one per book.
 */
interface ProgressBatch : BookSource {
    /** One result per report, in the same order. Throws [SourceException] when the call fails as a whole. */
    suspend fun reportAll(reports: List<PlaybackReport>): List<BatchResult>

    /** At most this many in one call. */
    val batchLimit: Int get() = 500
}

enum class BatchResult {
    /** Stored (or stored before: a repeat counts once). */
    SENT,

    /** The server had a newer position; ask for [BookSource.progress] to take it. */
    STALE,

    /** Refused for good: dropped. */
    REFUSED,
}

/** A source whose books have owners who can share them with their household. */
interface BookSharing : BookSource {
    /** Whether the signed-in listener owns [book], and so may share it or keep it to themselves. */
    fun canShare(book: Book): Boolean

    /** Shared with the household, or kept to the owner. */
    suspend fun setShared(bookId: String, shared: Boolean)
}
