package audio.own.bookplayer.playback

import audio.own.book.library.Book
import audio.own.book.library.BookId
import audio.own.book.library.BookSource
import audio.own.book.library.OfflineBookSource
import audio.own.core.servers.CompositeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Requests to play from outside the screens: the widget's and other apps' `ownbook://play/<id>`,
 * the "Continue Listening" shortcut, the assistant ("continue my book", "play ‹book›") and the
 * car. Ported from book-ios `BookIntents` and `AppModel.play(compositeId:)`. Nobody can answer
 * the resume question from a car or a voice request, so there the newer position wins; a link
 * opens the app, which can ask.
 */
class PlayRequests(private val model: PlaybackModel, private val library: Library) {
    interface Library {
        /** Waits for the saved servers and the download index. */
        suspend fun ready()

        val activeServerId: String?

        /** The library of [serverId] as the screens see it: the downloads alone while offline. */
        fun source(serverId: String): BookSource?

        /** The complete downloads of [serverId], newest first. */
        fun downloaded(serverId: String): List<Book>

        /** A downloaded book's record, complete or not. */
        fun downloadedRecord(compositeId: String): Book?
    }

    enum class LinkResult { PLAYING, NO_SERVER, NOT_FOUND }

    /** "Continue listening": the book in the player or the last one, else the first under way on the server shown. */
    suspend fun continueListening(): Boolean = withContext(Dispatchers.Main.immediate) {
        library.ready()
        if (model.resumeLast()) return@withContext true
        val serverId = library.activeServerId ?: return@withContext false
        val source = library.source(serverId) ?: return@withContext false
        val next = attempt { AutoContent.continueListening(source.shelves()) }.orEmpty().firstOrNull() ?: return@withContext false
        model.playNow(serverId, next)
    }

    /** "Play ‹book›": what [VoiceSearch] finds best; nothing named means continue. */
    suspend fun playFromSearch(query: String?): Boolean {
        if (VoiceSearch.isContinue(query)) return continueListening()
        return withContext(Dispatchers.Main.immediate) {
            library.ready()
            val serverId = library.activeServerId ?: return@withContext false
            val best = find(query.orEmpty()).firstOrNull() ?: return@withContext false
            model.playNow(serverId, best)
        }
    }

    /**
     * Candidates for [query] on the server shown: its downloads that match and, unless offline
     * with only the downloads listed, what its search finds; ten at most.
     */
    suspend fun find(query: String): List<Book> {
        library.ready()
        val serverId = library.activeServerId ?: return emptyList()
        val downloads = library.downloaded(serverId)
        val source = library.source(serverId)
        val limited = (source as? OfflineBookSource)?.isLimited() == true
        val bare = VoiceSearch.bare(query).ifBlank { query }
        val found = if (source == null || limited) emptyList() else attempt { source.search(bare).books }.orEmpty()
        return VoiceSearch.candidates(query, downloads, found)
    }

    /**
     * `ownbook://play/<id>`: the book from this device or its server, then play. A bare id (no
     * `<server>|`) means the server shown. [mayAsk] lets the app ask where to continue.
     */
    suspend fun playLink(id: String, mayAsk: Boolean = true): LinkResult = withContext(Dispatchers.Main.immediate) {
        library.ready()
        val composite = resolve(id, library.activeServerId) ?: return@withContext LinkResult.NO_SERVER
        val source = library.source(composite.serverId) ?: return@withContext LinkResult.NO_SERVER
        val book = library.downloadedRecord(composite.value)?.takeIf { !it.files.isNullOrEmpty() }
            ?: attempt { source.book(composite.bookId) }
            ?: return@withContext LinkResult.NOT_FOUND
        if (mayAsk) model.play(composite.serverId, book, null) else model.playNow(composite.serverId, book)
        LinkResult.PLAYING
    }

    /** A book the car listed (or any composite id), with the newer position. */
    suspend fun playInCar(compositeId: String, known: Book?): Boolean = withContext(Dispatchers.Main.immediate) {
        library.ready()
        val id = BookId(compositeId)
        val book = known ?: library.downloadedRecord(compositeId)
            ?: library.source(id.serverId)?.let { source -> attempt { source.book(id.bookId) } }
            ?: return@withContext false
        model.playNow(id.serverId, book)
    }

    companion object {
        /** A link's id as a book: `<server>|<book>` as it is, a bare id on the server shown; null with no server. */
        fun resolve(id: String, activeServerId: String?): BookId? = when {
            id.isBlank() -> null
            CompositeId.split(id) != null -> BookId(id)
            else -> activeServerId?.let { BookId.of(it, id) }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
