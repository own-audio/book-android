package audio.own.book.library

import audio.own.book.library.catalogue.BookRecord
import audio.own.book.library.catalogue.CatalogueLibrary
import audio.own.book.library.catalogue.CatalogueWriter
import audio.own.book.library.catalogue.CollectionBookRow
import audio.own.book.library.catalogue.OwnAudioCatalogue
import audio.own.book.library.catalogue.toBookmark
import audio.own.book.library.catalogue.toProgress
import audio.own.kit.ownaudio.ItemKind
import audio.own.kit.ownaudio.OfflineChange
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.OwnAudioImages
import audio.own.kit.ownaudio.PlaybackCapabilities
import audio.own.kit.ownaudio.PlaybackInfo
import audio.own.kit.ownaudio.book
import audio.own.kit.ownaudio.bookmarks
import audio.own.kit.ownaudio.collectionBooks
import audio.own.kit.ownaudio.collections
import audio.own.kit.ownaudio.createBookmark
import audio.own.kit.ownaudio.deleteBookmark
import audio.own.kit.ownaudio.deleteProgress
import audio.own.kit.ownaudio.events
import audio.own.kit.ownaudio.favourites
import audio.own.kit.ownaudio.me
import audio.own.kit.ownaudio.pageAll
import audio.own.kit.ownaudio.playbackInfo
import audio.own.kit.ownaudio.progress
import audio.own.kit.ownaudio.push
import audio.own.kit.ownaudio.saveProgress
import audio.own.kit.ownaudio.setBookVisibility
import audio.own.kit.ownaudio.setFavourite
import audio.own.kit.ownaudio.signOut
import audio.own.kit.ownaudio.updateBookmark
import audio.own.kit.ownaudio.NarrationJob
import audio.own.kit.ownaudio.NarrationOutput
import audio.own.kit.ownaudio.NarrationQuote
import audio.own.kit.ownaudio.NewBook
import audio.own.kit.ownaudio.UploadKinds
import audio.own.kit.ownaudio.UploadLink
import audio.own.kit.ownaudio.createBook
import audio.own.kit.ownaudio.createNarration
import audio.own.kit.ownaudio.createUpload
import audio.own.kit.ownaudio.narration
import audio.own.kit.ownaudio.narrationEstimate
import audio.own.kit.ownaudio.narrationLanguages
import audio.own.kit.ownaudio.narrationQuote
import audio.own.kit.ownaudio.narrationVoices
import audio.own.kit.ownaudio.putUpload
import java.io.File
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onEach
import okhttp3.RequestBody
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An own.audio server through its own API (`/api/v2`): the library on the phone from its
 * snapshot and change feed (books with their files, chapters and series, the contributors,
 * collections, the listener's progress, bookmarks and favourites), browsing and search over
 * that copy, per-device positions, and streams signed per file.
 */
class OwnAudioBookSource(
    override val serverId: String,
    val client: OwnAudioClient,
    val userId: String?,
    val catalogue: OwnAudioCatalogue,
    private val locale: Locale = Locale.getDefault(),
) : ChangeFeedCatalogue, LiveLibraryUpdates, ProgressBatch, BookSharing, BookUploader, BookNarrator {

    override val capabilities: Set<Capability> = setOf(
        Capability.SERIES, Capability.COLLECTIONS, Capability.FAVOURITES, Capability.PER_DEVICE_POSITIONS,
        Capability.FAMILY_SHARING, Capability.UPLOAD, Capability.LIVE_UPDATES, Capability.CHANGE_FEED,
        Capability.LOWER_BITRATE, Capability.NARRATOR_IDS,
    )

    private val order = NaturalOrder(locale)
    private val dao = catalogue.database.dao()

    override suspend fun checkConnection(): ConnectionStatus {
        if (client.tokens?.current == null) return ConnectionStatus.REJECTED
        return try {
            client.me()
            ConnectionStatus.OK
        } catch (e: OwnAudioException) {
            when (sourceError(e)) {
                SourceException.SignInRejected -> ConnectionStatus.REJECTED
                else -> ConnectionStatus.UNREACHABLE
            }
        }
    }

    // region Browsing, from the copy on the phone

    override suspend fun shelves(): List<Shelf> {
        val library = synced()
        val started = library.progress.entries.sortedByDescending { it.value.updatedAt }
        val continueListening = started.filter { !it.value.finished }.mapNotNull { library.byId[it.key] }
        val listenAgain = started.filter { it.value.finished }.mapNotNull { library.byId[it.key] }
        val recent = library.books.sortedByDescending { it.addedAt }
        return listOf(
            Shelf(CONTINUE_LISTENING, "Continue Listening", Shelf.Content.Books(continueListening.take(SHELF_SIZE))),
            Shelf(RECENTLY_ADDED, "Recently Added", Shelf.Content.Books(recent.take(SHELF_SIZE))),
            Shelf(LISTEN_AGAIN, "Listen Again", Shelf.Content.Books(listenAgain.take(SHELF_SIZE))),
        ).filterNot { it.isEmpty }
    }

    override suspend fun books(query: BookQuery, cursor: String?, limit: Int): BooksPage {
        val library = synced()
        var books = library.books
        query.authorId?.let { id -> books = books.filter { b -> b.authors.any { it.id == id } } }
        query.narratorId?.let { id -> books = books.filter { b -> b.narrators.any { it.id == id } } }
        query.seriesId?.let { id -> books = books.filter { b -> b.series.any { it.id == id } } }
        query.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text -> books = books.filter { matches(it, text) } }
        books = when (query.filter) {
            BookFilter.ALL -> books
            BookFilter.NOT_STARTED -> books.filter { it.id !in library.progress }
            BookFilter.IN_PROGRESS -> books.filter { library.progress[it.id]?.finished == false }
            BookFilter.FINISHED -> books.filter { library.progress[it.id]?.finished == true }
            BookFilter.FAVOURITES -> books.filter { it.id in library.favourites }
        }
        val seriesId = query.seriesId
        books = if (seriesId != null) {
            books.sortedWith(CatalogueLibrary.inSeries(seriesId, order))
        } else {
            val comparator: Comparator<Book> = when (query.sort) {
                BookSort.TITLE -> compareBy(order) { it.title }
                BookSort.AUTHOR -> compareBy<Book, String>(order) { it.authorLine }.thenBy(order) { it.title }
                BookSort.ADDED -> compareBy { it.addedAt }
                BookSort.DURATION -> compareBy { it.durationMs }
            }
            books.sortedWith(if (query.descending) comparator.reversed() else comparator)
        }
        return page(books, cursor, limit)
    }

    /** From the catalogue with files and chapters; from the server when the record came without them. */
    override suspend fun book(id: String): Book {
        synced()
        CatalogueLibrary.detail(dao, id)?.takeIf { it.files != null }?.let { return it }
        return translating {
            val answer = client.book(id)
            val contributors = answer.included?.contributors.orEmpty().associate { it.id to BookRecord.contributor(it) }
            BookRecord.of(answer.record, 0).toBook(contributors)
        }
    }

    /**
     * Straight from the server: a record in the catalogue keeps the image link it was sent with,
     * and that link is signed for a week or two and built for the address the server had then.
     */
    override suspend fun currentCoverUrl(bookId: String): String? = try {
        translating { client.book(bookId).record.image?.let { OwnAudioImages.sized(it, 600) } }
    } catch (_: SourceException) {
        null
    }

    override suspend fun authors(): List<Person> = synced().authors

    override suspend fun narrators(): List<Person> = synced().narrators

    override suspend fun series(): List<SeriesSummary> = synced().series

    override suspend fun series(id: String): SeriesSummary = synced().series.firstOrNull { it.id == id } ?: throw SourceException.NotFound

    /**
     * From the server, with their books, kept in the catalogue; the kept copy when the server
     * can't be reached. The feed's collection records don't list their books, so this is where
     * the catalogue learns them.
     */
    override suspend fun collections(): List<BookCollection> {
        val library = synced()
        return try {
            translating { fetchCollections(library) }
        } catch (e: SourceException) {
            if (e == SourceException.Network || e == SourceException.Offline) synced().collections else throw e
        }
    }

    private suspend fun fetchCollections(library: CatalogueLibrary): List<BookCollection> = coroutineScope {
        val collections = pageAll { client.collections(cursor = it, limit = 500) }
        val entries = collections.map { c -> async { c to pageAll { client.collectionBooks(c.id, cursor = it, limit = 500) } } }.awaitAll()
        catalogue.remember { gen ->
            for ((c, books) in entries) {
                collection(c, gen)
                collectionBooks(c.id, books.map { CollectionBookRow(c.id, it.book.id, it.position) })
            }
        }
        val contributors = dao.contributors().associateBy { it.id }
        entries.map { (c, books) ->
            BookCollection(
                id = c.id, name = c.name, description = c.description,
                books = books.sortedBy { it.position }.map { entry ->
                    library.byId[entry.book.id] ?: BookRecord.of(entry.book, 0).toBook(contributors, withDetail = false)
                },
            )
        }
    }

    override suspend fun search(text: String): SearchResult {
        val library = synced()
        val needle = text.trim()
        if (needle.isEmpty()) return SearchResult()
        return SearchResult(
            books = library.books.filter { matches(it, needle) }.sortedWith(compareBy(order) { it.title }),
            authors = library.authors.filter { it.name.containsText(needle, locale) },
            narrators = library.narrators.filter { it.name.containsText(needle, locale) },
            series = library.series.filter { it.name.containsText(needle, locale) },
        )
    }

    // endregion

    // region The listener's state

    /** From the server, with every device's position; the copy on the phone when it can't be reached. */
    override suspend fun progress(bookId: String): ListeningProgress? = offlineFallback({ dao.progress(bookId)?.toProgress() }) {
        client.progress(ItemKind.BOOK, bookId)?.toProgress()
    }

    override suspend fun allProgress(): Map<String, ListeningProgress> = synced().progress

    override suspend fun saveProgress(bookId: String, positionMs: Long, durationMs: Long, finished: Boolean, at: Instant): ListeningProgress = translating {
        val answer = client.saveProgress(ItemKind.BOOK, bookId, positionMs, finished, at)
        catalogue.remember { gen -> progress(answer.progress, gen) }
        answer.progress.toProgress().copy(applied = answer.applied)
    }

    /**
     * Through `POST /sync/push`: each position with when it was reached, so a newer one from
     * another device stays (`stale`). The feed brings what was stored to the catalogue.
     */
    override suspend fun reportAll(reports: List<PlaybackReport>): List<BatchResult> = translating {
        val changes = reports.map {
            OfflineChange.progress(ItemKind.BOOK, it.bookId, it.positionMs, it.finished, it.at, clientId = "${it.bookId}@${it.at.toEpochMilli()}")
        }
        val results = client.push(changes).associateBy { it.clientId }
        changes.map { change ->
            when (results[change.clientId]?.status) {
                "ok", "duplicate" -> BatchResult.SENT
                "stale" -> BatchResult.STALE
                else -> BatchResult.REFUSED
            }
        }
    }

    override fun canShare(book: Book): Boolean = userId != null && book.ownerId == userId

    override suspend fun setShared(bookId: String, shared: Boolean) = translating {
        client.setBookVisibility(bookId, if (shared) Visibility.FAMILY.wire else Visibility.PRIVATE.wire)
        Unit
    }

    override suspend fun clearProgress(bookId: String) = translating {
        client.deleteProgress(ItemKind.BOOK, bookId)
        catalogue.forget(CatalogueWriter.PROGRESS, bookId)
    }

    override suspend fun bookmarks(bookId: String): List<Bookmark> = offlineFallback({ dao.bookmarksOf(bookId).map { it.toBookmark() } }) {
        pageAll { client.bookmarks(ItemKind.BOOK, itemId = bookId, cursor = it) }.map { it.toBookmark() }.sortedBy { it.positionMs }
    }

    override suspend fun addBookmark(bookId: String, positionMs: Long, label: String?): Bookmark = translating {
        val made = client.createBookmark(ItemKind.BOOK, bookId, positionMs = positionMs, label = label)
        catalogue.remember { gen -> bookmark(made, gen) }
        made.toBookmark()
    }

    /** A null label clears it: the kit's empty string, as its PATCH takes null for "keep". */
    override suspend fun renameBookmark(bookmark: Bookmark, label: String?): Bookmark = translating {
        val renamed = client.updateBookmark(bookmark.id, label = label ?: "")
        catalogue.remember { gen -> bookmark(renamed, gen) }
        renamed.toBookmark()
    }

    override suspend fun deleteBookmark(bookmark: Bookmark) = translating {
        client.deleteBookmark(bookmark.id)
        catalogue.forget("bookmark", bookmark.id)
    }

    override suspend fun favourites(): Set<String> = offlineFallback({ synced().favourites }) {
        pageAll { client.favourites(ItemKind.BOOK, cursor = it) }.mapTo(HashSet()) { it.id }
    }

    override suspend fun setFavourite(bookId: String, on: Boolean) = translating {
        client.setFavourite(ItemKind.BOOK, bookId, on)
        if (on) {
            catalogue.remember { gen -> favourite(bookId, Instant.now(), gen) }
        } else {
            catalogue.forget(CatalogueWriter.FAVOURITE, bookId)
        }
    }

    // endregion

    // region Playing

    /**
     * One signed link per file, asked for at once; links last hours and are renewed with
     * [partUrl] when they run out.
     */
    override suspend fun playPlan(book: Book, maxBitrateKbps: Int?): PlayPlan {
        val full = if (book.files == null) book(book.id) else book
        val files = full.files?.takeIf { it.isNotEmpty() } ?: throw SourceException.NotFound
        return translating {
            val infos = coroutineScope {
                files.map { file -> async { client.playbackInfo(BOOK_FILE, file.id, playbackCapabilities(maxBitrateKbps)) } }.awaitAll()
            }
            PlayPlan(
                bookId = full.id,
                parts = files.zip(infos) { file, info -> part(file, info) },
                sessionId = null,
                expiresAt = infos.minOfOrNull { it.expiresAt },
                startPositionMs = null,
            )
        }
    }

    override suspend fun report(report: PlaybackReport): ListeningProgress? =
        saveProgress(report.bookId, report.positionMs, report.durationMs, report.finished, report.at)

    override suspend fun partUrl(book: Book, fileId: String, startMs: Long?, maxBitrateKbps: Int?): PlayPlan.Part {
        val file = book.files?.firstOrNull { it.id == fileId } ?: throw SourceException.NotFound
        return translating {
            part(file, client.playbackInfo(BOOK_FILE, fileId, playbackCapabilities(maxBitrateKbps), startMs = startMs?.let { it - file.startMs }))
        }
    }

    private fun part(file: BookFile, info: PlaybackInfo) = PlayPlan.Part(
        fileId = file.id, url = info.url, startMs = file.startMs, durationMs = file.durationMs,
        contentType = info.contentType ?: file.contentType, isHls = false, streamsByRange = info.decision == "direct",
    )

    override suspend fun signOut() {
        client.signOut()
    }

    // endregion

    // region The library on the phone

    private val loading = Mutex()

    @Volatile
    private var loaded: Pair<Long, CatalogueLibrary>? = null

    /** The library, synced first when the phone never had it. */
    internal suspend fun synced(): CatalogueLibrary {
        if (!catalogue.isLoaded()) syncCatalogue()
        return loading.withLock {
            val version = catalogue.version
            loaded?.takeIf { it.first == version }?.second
                ?: CatalogueLibrary.load(dao, order).also { loaded = version to it }
        }
    }

    override suspend fun syncCatalogue(): CatalogueSummary {
        if (client.tokens?.current == null) throw SourceException.SignInRejected
        translating { catalogue.sync(client) }
        return catalogueSummary() ?: throw SourceException.Unreadable
    }

    override suspend fun catalogueSummary(): CatalogueSummary? {
        val state = catalogue.feedState()?.takeIf { it.cursor != null } ?: return null
        return CatalogueSummary(dao.bookCount(), dao.authorCount(), state.syncedAt?.let(Instant::ofEpochMilli))
    }

    override suspend fun resetCatalogue() = catalogue.reset()

    override fun libraryChanges(): Flow<Unit> = client.events()
        .onEach { if (it.type == "narration") narrationChanges.tryEmit(Unit) }
        .filter { it.type == "library" }
        .map { }
        .catch { e -> throw if (e is OwnAudioException) sourceError(e) else e }

    // endregion

    // region Books from the phone, narration

    override suspend fun uploadTarget(kind: UploadKind, fileName: String, contentType: String, sizeBytes: Long): UploadTarget = translating {
        val link = client.createUpload(if (kind == UploadKind.COVER) UploadKinds.BOOK_COVER else UploadKinds.BOOK_FILE, fileName, contentType, sizeBytes)
        UploadTarget(link.uploadId, link.url, link.method, link.headers, link.expiresAt)
    }

    override suspend fun upload(target: UploadTarget, body: RequestBody) = translating {
        client.putUpload(UploadLink(target.uploadId, target.url, target.method, target.headers, target.expiresAt), body)
    }

    override suspend fun createBook(details: NewBookDetails, coverUploadId: String?, files: List<ImportedFile>, importId: String): Book = translating {
        val made = client.createBook(
            NewBook(
                title = details.title,
                author = details.authors.takeIf { it.isNotEmpty() }?.joinToString("; "),
                narrator = details.narrators.takeIf { it.isNotEmpty() }?.joinToString("; "),
                description = details.description,
                visibility = details.visibility.wire,
                coverUploadId = coverUploadId,
                files = files.mapIndexed { index, file -> NewBook.File(file.uploadId, ImportMetadata.relativePath(index, file.fileName), file.title) },
            ),
            idempotencyKey = importId,
        )
        // The new book joins the copy on the phone at the next feed sync; until then the record
        // is returned as the server made it.
        BookRecord.of(made, 0).toBook(emptyMap())
    }

    private val narrationChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val narrationEvents: Flow<Unit> = narrationChanges.asSharedFlow()

    override suspend fun narrationOptions(): NarrationOptions = translating {
        coroutineScope {
            val languages = async { client.narrationLanguages() }
            val voices = client.narrationVoices()
            NarrationOptions(languages.await(), voices)
        }
    }

    override suspend fun narrationCharacterCount(file: File): Int = translating { client.narrationEstimate(file).estimatedCharCount }

    override suspend fun narrationQuote(charCount: Int, sourceLanguage: String, targetLanguage: String, voiceId: String): NarrationQuote =
        translating { client.narrationQuote(charCount, sourceLanguage, targetLanguage, voiceId) }

    override suspend fun startNarration(file: File, title: String, sourceLanguage: String, targetLanguage: String, voiceId: String, output: NarrationOutput, idempotencyKey: String): NarrationJob =
        translating { client.createNarration(file, title, sourceLanguage, targetLanguage, voiceId, output, idempotencyKey) }

    override suspend fun narration(id: String): NarrationJob = translating { client.narration(id) }

    // endregion

    private fun matches(book: Book, text: String): Boolean =
        book.title.containsText(text, locale) ||
            book.authors.any { it.name.containsText(text, locale) } ||
            book.narrators.any { it.name.containsText(text, locale) } ||
            book.series.any { it.name.containsText(text, locale) }

    /** The server's answer; the copy on the phone when the server can't be reached. */
    private suspend fun <T> offlineFallback(kept: suspend () -> T, work: suspend () -> T): T = try {
        work()
    } catch (e: OwnAudioException) {
        when (val error = sourceError(e)) {
            SourceException.Network, SourceException.Offline -> kept()
            else -> throw error
        }
    }

    companion object {
        const val CONTINUE_LISTENING = "continue-listening"
        const val RECENTLY_ADDED = "recently-added"
        const val LISTEN_AGAIN = "listen-again"
        const val SHELF_SIZE = 10
        private const val BOOK_FILE = "book_file"

        /** What Media3 plays as it is; AIFF is left to the server, which makes AAC or MP3 of it. */
        val DIRECT_TYPES = listOf(
            "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/x-m4b", "audio/aac", "audio/flac", "audio/x-flac",
            "audio/wav", "audio/x-wav", "audio/ogg", "audio/opus", "audio/webm",
        )

        fun playbackCapabilities(maxBitrateKbps: Int?) = PlaybackCapabilities(direct = DIRECT_TYPES, transcode = listOf("aac", "mp3"), maxBitrateKbps = maxBitrateKbps)

        /** An offset cursor over a list already in order. */
        internal fun page(books: List<Book>, cursor: String?, limit: Int): BooksPage {
            val offset = cursor?.toIntOrNull() ?: 0
            val page = books.drop(offset).take(limit)
            val next = if (offset + limit < books.size) (offset + limit).toString() else null
            return BooksPage(page, next, books.size)
        }

        internal suspend fun <T> translating(work: suspend () -> T): T = try {
            work()
        } catch (e: OwnAudioException) {
            throw sourceError(e)
        }

        fun sourceError(e: OwnAudioException): SourceException = when (e) {
            OwnAudioException.SignedOut, OwnAudioException.SignInRefused -> SourceException.SignInRejected
            is OwnAudioException.Problem -> when {
                e.status == 400 && e.code == "invalid_grant" -> SourceException.SignInRejected
                e.status == 401 -> SourceException.SignInRejected
                // No room or no credit left: the screens say so in their own words.
                e.status == 402 -> SourceException.Http(402)
                e.status == 403 -> SourceException.NotAllowed
                e.status == 404 -> SourceException.NotFound
                e.status == 501 -> SourceException.Unsupported
                else -> e.detail?.let { SourceException.Server(it) } ?: SourceException.Http(e.status)
            }
            is OwnAudioException.Http -> when (e.status) {
                401 -> SourceException.SignInRejected
                403 -> SourceException.NotAllowed
                404 -> SourceException.NotFound
                else -> SourceException.Http(e.status)
            }
            is OwnAudioException.Decoding -> SourceException.Unreadable
            is OwnAudioException.Network, is OwnAudioException.SnapshotTruncated -> SourceException.Network
            OwnAudioException.Offline -> SourceException.Offline
            is OwnAudioException.TooOld -> SourceException.Unsupported
        }
    }
}
