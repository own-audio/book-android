package audio.own.bookplayer.downloads

import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import audio.own.book.library.AudiobookshelfBookSource
import audio.own.book.library.Book
import audio.own.book.library.BookId
import audio.own.book.library.BookSource
import audio.own.book.library.EvictionPolicy
import audio.own.book.library.OwnAudioBookSource
import audio.own.book.library.SourceException
import audio.own.bookplayer.R
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.playback.PlaybackPrefs
import audio.own.core.downloads.DownloadEntity
import audio.own.core.downloads.DownloadFetcher
import audio.own.core.downloads.DownloadLinkResolver
import audio.own.core.downloads.DownloadNotifications
import audio.own.core.downloads.DownloadRequest
import audio.own.core.downloads.DownloadScheduler
import audio.own.core.downloads.DownloadWorkerFactory
import audio.own.core.downloads.Downloads
import audio.own.core.downloads.DownloadsDatabase
import audio.own.core.downloads.DownloadsSnapshot
import audio.own.core.downloads.WorkManagerScheduler
import audio.own.kit.ownaudio.HoldingRef
import audio.own.kit.ownaudio.PlaybackCapabilities
import audio.own.kit.ownaudio.playbackInfo
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.updateHoldings
import audio.own.player.core.PlaybackLink
import audio.own.player.core.ProxyMemory
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Where a book's download is. */
sealed interface DownloadState {
    data class Downloading(val fraction: Float) : DownloadState
    data object Downloaded : DownloadState

    /** [code] is the core's reason (`network`, `http_404`, `not_audio`…). */
    data class Failed(val code: String?) : DownloadState
}

/** A book on the phone, or on its way: its record as it was downloaded and how far it got. */
data class DownloadedBook(
    /** `<server>|<book>`. */
    val compositeId: String,
    val serverId: String,
    val book: Book,
    /** Bytes of the files already here. */
    val bytes: Long,
    val startedAt: Long,
    /** When its last file arrived, once complete. */
    val finishedAt: Long?,
    val lastPlayedAt: Long?,
    val state: DownloadState,
) {
    val isComplete: Boolean get() = state == DownloadState.Downloaded
}

/** Every book on the phone or coming, from every server. */
data class BookDownloadsSnapshot(val books: Map<String, DownloadedBook> = emptyMap(), val loaded: Boolean = false) {
    fun state(compositeId: String): DownloadState? = books[compositeId]?.state
    fun state(serverId: String, bookId: String): DownloadState? = state(BookId.of(serverId, bookId).value)
    fun isDownloaded(serverId: String, bookId: String) = state(serverId, bookId) == DownloadState.Downloaded

    /** Newest first: the last file's arrival, or when it was asked for. */
    val newestFirst: List<DownloadedBook> get() = books.values.sortedByDescending { it.finishedAt ?: it.startedAt }

    /** The complete books of one server, newest first. */
    fun downloadedBooks(serverId: String): List<Book> = newestFirst.filter { it.serverId == serverId && it.isComplete }.map { it.book }

    val totalBytes: Long get() = books.values.sumOf { it.bytes }
    val downloadingCount: Int get() = books.values.count { it.state is DownloadState.Downloading }
    val failedCount: Int get() = books.values.count { it.state is DownloadState.Failed }

    companion object {
        /** The core's per-file index as books: a book's files carry its id and the whole record. */
        fun of(core: DownloadsSnapshot, progress: Map<String, Float>): BookDownloadsSnapshot {
            val books = core.items.values.groupBy { it.itemId }.mapNotNull { (id, files) ->
                val book = files.firstNotNullOfOrNull { BookRecordJson.decode(it.record) } ?: return@mapNotNull null
                val done = files.filter { it.state == DownloadEntity.DONE }
                val expected = book.files.orEmpty().size.coerceAtLeast(files.size)
                val state = when {
                    done.size >= expected -> DownloadState.Downloaded
                    files.none { it.state == DownloadEntity.QUEUED } -> DownloadState.Failed(files.firstNotNullOfOrNull { it.error })
                    else -> DownloadState.Downloading(fraction(book, files, progress))
                }
                DownloadedBook(
                    compositeId = id,
                    serverId = files.first().serverId,
                    book = book,
                    bytes = done.sumOf { it.bytes },
                    startedAt = files.minOf { it.queuedAt },
                    finishedAt = if (state == DownloadState.Downloaded) done.maxOfOrNull { it.downloadedAt ?: 0 } else null,
                    lastPlayedAt = files.mapNotNull { it.lastPlayedAt }.maxOrNull(),
                    state = state,
                )
            }
            return BookDownloadsSnapshot(books.associateBy { it.compositeId }, core.loaded)
        }

        /** By bytes where the record has sizes, else by files, with the one coming counted in part. */
        private fun fraction(book: Book, files: List<DownloadEntity>, progress: Map<String, Float>): Float {
            val sizes = book.files.orEmpty().associate { it.id to (it.sizeBytes ?: 0) }
            val expected = sizes.values.sum()
            val result = if (expected > 0) {
                files.sumOf { f ->
                    val size = sizes[f.fileId] ?: 0
                    if (f.state == DownloadEntity.DONE) size.toDouble() else size * (progress[f.key] ?: 0f).toDouble()
                } / expected
            } else {
                files.sumOf { f -> if (f.state == DownloadEntity.DONE) 1.0 else (progress[f.key] ?: 0f).toDouble() } / files.size.coerceAtLeast(1)
            }
            return result.toFloat().coerceIn(0f, 1f)
        }
    }
}

/**
 * Books kept on the phone (`:core:downloads`, docs/android-foundation.md §8): one work per file
 * (`<server>|<book>#<file>`), each entry with the whole book record. What a book is to the
 * screens ([state]), the player's local files, Keep at Most by book (finished books first, then
 * the least recently played, never the one playing), and the holdings own.audio is told of.
 */
class BookDownloads(
    db: DownloadsDatabase,
    root: File,
    scheduler: DownloadScheduler,
    fetcher: DownloadFetcher,
    private val sourceOf: (serverId: String) -> BookSource?,
    /** Waits until the saved servers are read: a worker can start before any screen. */
    private val serversLoaded: suspend () -> Unit,
    private val prefs: StateFlow<PlaybackPrefs>,
    /** The book playing now (composite id), never evicted. */
    private val playing: () -> String?,
    /** Whether the listener finished a book (composite id): those go first over the limit. */
    private val isFinished: (String) -> Boolean,
    private val log: AppLog,
    private val scope: CoroutineScope,
    linkResolver: DownloadLinkResolver? = null,
    holdingsDelayMs: Long = 3_000,
) {
    val core = Downloads(
        db = db,
        root = root,
        scheduler = scheduler,
        resolver = linkResolver ?: DownloadLinkResolver { item -> link(item) },
        fetcher = fetcher,
        settings = object : Downloads.Settings {
            override fun wifiOnly() = prefs.value.downloadsWifiOnly

            // Keep at Most is by book here ([enforceCap]); the core's is by file.
            override fun capBytes(): Long? = null
            override fun protectedKey(): String? = null
        },
        scope = scope,
        onHoldingsChanged = ::reportHoldings,
        isTransient = ::isTransient,
        log = { log.info("downloads", it) },
        holdingsDelayMs = holdingsDelayMs,
    )

    val state: StateFlow<BookDownloadsSnapshot> = combine(core.state, core.progress) { s, p -> BookDownloadsSnapshot.of(s, p) }
        .stateIn(scope, SharingStarted.Eagerly, BookDownloadsSnapshot())

    /** A file finished: the playing book can take it at its next pause or seek. */
    var onFileReady: ((compositeId: String) -> Unit)? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            prefs.map { it.downloadsWifiOnly }.distinctUntilChanged().drop(1).collect { core.wifiOnlyChanged() }
        }
        scope.launch {
            prefs.map { it.keepAtMostGb }.distinctUntilChanged().drop(1).collect { enforceCap() }
        }
        // A book that came in complete may push the others over the limit.
        scope.launch {
            state.map { s -> s.books.values.filter { it.isComplete }.map { it.compositeId }.toSet() }.distinctUntilChanged().drop(1).debounce(1_000).collect { enforceCap() }
        }
        scope.launch {
            var before: Map<String, Int>? = null
            core.state.collect { s ->
                if (!s.loaded) return@collect
                val done = s.items.values.filter { it.state == DownloadEntity.DONE }.groupingBy { it.itemId }.eachCount()
                before?.let { was -> done.forEach { (id, count) -> if (count > (was[id] ?: 0)) onFileReady?.invoke(id) } }
                before = done
            }
        }
    }

    // region Reading

    /** The local file of one file of a book, or null to stream it. Any thread. */
    fun localFile(compositeId: String, fileId: String): Uri? =
        core.file(Downloads.key(compositeId, fileId))?.takeIf { it.isFile }?.let(Uri::fromFile)

    /** A downloaded book's record, for a screen or a play that can't ask the server. */
    fun downloadedBook(compositeId: String): Book? = state.value.books[compositeId]?.takeIf { it.isComplete }?.book

    /** Any record of the book the index has, complete or not. */
    fun record(compositeId: String): Book? = state.value.books[compositeId]?.book

    // endregion

    // region Changing

    /** Every file of [book]; the detail is fetched first when the record came without files. */
    suspend fun download(serverId: String, book: Book) {
        val detail = if (book.files.isNullOrEmpty()) (sourceOf(serverId) ?: throw SourceException.NotFound).book(book.id) else book
        val files = detail.files.orEmpty()
        if (files.isEmpty()) throw SourceException.NotFound
        val composite = BookId.of(serverId, detail.id).value
        val record = BookRecordJson.encode(detail)
        core.download(
            files.map { file ->
                DownloadRequest(
                    itemId = composite, serverId = serverId, record = record, fileId = file.id,
                    extensionHint = extensionOf(file.contentType), title = detail.title,
                )
            },
        )
    }

    /** A whole set (a series, an author's books, a collection): the ones not here yet, one after the other. */
    suspend fun downloadAll(serverId: String, books: List<Book>) {
        for (book in books) {
            if (state.value.state(serverId, book.id) != null) continue
            try {
                download(serverId, book)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("downloads", "couldn't start ${book.id}: ${e.message}")
            }
        }
    }

    suspend fun remove(compositeId: String) {
        val keys = core.state.value.items.values.filter { it.itemId == compositeId }.map { it.key }
        for (key in keys) core.remove(key)
    }

    suspend fun remove(serverId: String, books: List<Book>) {
        for (book in books) remove(BookId.of(serverId, book.id).value)
    }

    suspend fun removeAll(serverId: String? = null) = core.removeAll(serverId)

    /** Retry N Failed. */
    suspend fun retryFailed() = core.retryFailed()

    /** A failed book once more: its failed files start again. */
    suspend fun retry(serverId: String, book: Book) {
        val composite = BookId.of(serverId, book.id).value
        val record = state.value.books[composite]?.book ?: book
        download(serverId, record)
    }

    /** The book played: it moves to the back of the line for Keep at Most. */
    suspend fun notePlayed(compositeId: String) {
        core.state.value.items.values.filter { it.itemId == compositeId }.forEach { core.notePlayed(it.key) }
    }

    /** At launch: what the index doesn't know goes, what waited (killed mid-download) starts again. */
    suspend fun reconcile() = core.reconcile()

    /** Lets go of whole books until the rest fits Keep at Most. */
    suspend fun enforceCap() {
        val cap = prefs.value.keepAtMostBytes ?: return
        val complete = state.value.books.values.filter { it.isComplete }
        val candidates = complete.map {
            EvictionPolicy.Candidate(it.compositeId, it.bytes, isFinished(it.compositeId), Instant.ofEpochMilli(it.lastPlayedAt ?: it.finishedAt ?: it.startedAt))
        }
        val out = EvictionPolicy(cap).evictions(candidates, playing())
        if (out.isEmpty()) return
        log.info("downloads", "${out.size} books over Keep at Most removed")
        for (id in out) remove(id)
    }

    // endregion

    /** The original file, whatever its format: a download is kept as the server has it. */
    private suspend fun link(item: DownloadEntity): PlaybackLink {
        serversLoaded()
        val fileId = item.fileId ?: throw SourceException.NotFound
        return when (val source = sourceOf(item.serverId)) {
            is OwnAudioBookSource -> try {
                val info = source.client.playbackInfo("book_file", fileId, PlaybackCapabilities(direct = emptyList(), transcode = emptyList()))
                PlaybackLink(info.url, info.expiresAt.toEpochMilli(), info.contentType)
            } catch (e: OwnAudioException) {
                if (e is OwnAudioException.Network || e is OwnAudioException.Offline) throw IOException(e.message, e)
                throw e
            }
            is AudiobookshelfBookSource -> {
                val client = source.client
                val token = client.freshToken()
                PlaybackLink(client.fileUrl(BookId(item.itemId).bookId, fileId, token).toString())
            }
            null -> throw IOException("server ${item.serverId} not ready")
            else -> throw SourceException.Unsupported
        }
    }

    /** What each own.audio server is told this phone keeps: the whole set, after changes. */
    private suspend fun reportHoldings(byServer: Map<String, List<String>>) {
        serversLoaded()
        for ((serverId, ids) in byServer) {
            val source = sourceOf(serverId) as? OwnAudioBookSource ?: continue
            if (source.client.tokens?.current == null) continue
            try {
                source.client.updateHoldings(items = ids.map { HoldingRef("book", BookId(it).bookId) })
            } catch (e: OwnAudioException) {
                log.error("downloads", "holdings not reported: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun isTransient(error: Throwable): Boolean = when (error) {
        is IOException, is OwnAudioException.Network, is OwnAudioException.Offline -> true
        SourceException.Network, SourceException.Offline -> true
        is SourceException.Http -> error.status >= 500 || error.status == 429
        else -> false
    }

    companion object {
        /** The real one: the database and files under the app's own folders, WorkManager for the work. */
        fun create(
            context: Context,
            bareHttp: OkHttpClient,
            userAgent: String,
            proxyMemory: ProxyMemory,
            sourceOf: (String) -> BookSource?,
            serversLoaded: suspend () -> Unit,
            prefs: StateFlow<PlaybackPrefs>,
            playing: () -> String?,
            isFinished: (String) -> Boolean,
            log: AppLog,
            scope: CoroutineScope,
        ): BookDownloads {
            val app = context.applicationContext
            return BookDownloads(
                db = DownloadsDatabase.open(app),
                root = File(app.filesDir, "downloads"),
                scheduler = WorkManagerScheduler { WorkManager.getInstance(app) },
                fetcher = DownloadFetcher(bareHttp, proxyMemory, userAgent),
                sourceOf = sourceOf, serversLoaded = serversLoaded, prefs = prefs, playing = playing, isFinished = isFinished,
                log = log, scope = scope,
            )
        }

        fun workerFactory(downloads: () -> BookDownloads) = DownloadWorkerFactory(
            downloads = { downloads().core },
            notifications = DownloadNotifications(
                channelName = { it.getString(R.string.tab_downloads) },
                text = { ctx, title -> if (title != null) ctx.getString(R.string.downloading_title, title) else ctx.getString(R.string.downloading_books) },
                smallIcon = R.drawable.ic_stat_download,
            ),
        )

        /** A file extension from the record's content type, for bytes that don't say. */
        fun extensionOf(contentType: String?): String? = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            null, "" -> null
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/x-m4b", "audio/m4b" -> "m4a"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "audio/vorbis" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> null
        }
    }
}
