package audio.own.bookplayer.playback

import audio.own.book.library.BookId
import audio.own.book.library.ListeningProgress
import audio.own.book.library.PlaybackReport
import audio.own.book.library.SourceException
import audio.own.bookplayer.app.AppLog
import audio.own.player.core.JsonFile
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Positions on their way to the servers (book-ios `PendingProgress.swift`, the minimum B4 needs):
 * every report is written to disk **before** it is sent, merged per server, book and play session
 * (the newer position replaces, listened time adds up); sent at once on a pause, seek, chapter,
 * stop, the end or teardown, otherwise at most every 60 s; oldest first; a network error stops
 * the flush until the next one. Also keeps where this phone last was in each book, for resuming.
 */
class ProgressOutbox(
    file: File,
    private val send: suspend (serverId: String, report: PlaybackReport) -> ListeningProgress?,
    private val scope: CoroutineScope,
    private val log: AppLog,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Entry(
        val serverId: String,
        val bookId: String,
        val sessionId: String? = null,
        val positionMs: Long,
        val durationMs: Long,
        val listenedMs: Long,
        val finished: Boolean,
        val atMs: Long,
        val closes: Boolean = false,
    )

    @Serializable
    data class Position(val positionMs: Long, val finished: Boolean, val atMs: Long)

    @Serializable
    data class Contents(val pending: List<Entry> = emptyList(), val positions: Map<String, Position> = emptyMap())

    private val store = JsonFile(file, Contents.serializer())
    private val lock = Any()
    private var contents: Contents = store.read() ?: Contents()
    private val flushing = Mutex()
    private var lastFlushAt = 0L

    val pending: List<Entry> get() = synchronized(lock) { contents.pending }

    /** Where this phone last was in [bookId] (composite), as a progress for [audio.own.book.library.ResumeChoice]. */
    fun local(bookId: String): ListeningProgress? =
        synchronized(lock) { contents.positions[bookId] }?.let { ListeningProgress(it.positionMs, it.finished, Instant.ofEpochMilli(it.atMs)) }

    /** Stores a report, then sends what is queued when [immediate] or a minute has passed. */
    fun record(entry: Entry, immediate: Boolean) {
        synchronized(lock) {
            val key = BookId.of(entry.serverId, entry.bookId).value
            val merged = contents.pending.toMutableList()
            val index = merged.indexOfFirst { it.serverId == entry.serverId && it.bookId == entry.bookId && it.sessionId == entry.sessionId }
            if (index >= 0) {
                val old = merged[index]
                merged[index] = entry.copy(listenedMs = old.listenedMs + entry.listenedMs, closes = old.closes || entry.closes)
            } else {
                merged += entry
            }
            contents = Contents(merged, contents.positions + (key to Position(entry.positionMs, entry.finished, entry.atMs)))
            write()
        }
        if (immediate || now() - lastFlushAt >= PERIODIC_MS) flushSoon()
    }

    /** Forgets a book's queued positions (Mark as Finished, Reset). */
    fun drop(serverId: String, bookId: String) = synchronized(lock) {
        contents = contents.copy(pending = contents.pending.filterNot { it.serverId == serverId && it.bookId == bookId })
        write()
    }

    fun flushSoon() {
        scope.launch { flush() }
    }

    /** Sends the queue, oldest first. A pause during a slow flush is sent by the flush after it. */
    suspend fun flush() = flushing.withLock {
        lastFlushAt = now()
        for (entry in pending.sortedBy { it.atMs }) {
            val report = PlaybackReport(
                bookId = entry.bookId, sessionId = entry.sessionId, positionMs = entry.positionMs, listenedMs = entry.listenedMs,
                durationMs = entry.durationMs, finished = entry.finished, at = Instant.ofEpochMilli(entry.atMs), closes = entry.closes,
            )
            try {
                send(entry.serverId, report)
                remove(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    e is IOException || e == SourceException.Network || e == SourceException.Offline -> return@withLock
                    e == SourceException.SignInRejected -> return@withLock
                    e == SourceException.NotFound && entry.sessionId != null -> replace(entry, entry.copy(sessionId = null))
                    e is NoSource -> return@withLock
                    else -> {
                        log.error("progress", "a position was refused: ${e.message}")
                        remove(entry)
                    }
                }
            }
        }
    }

    /** The server isn't loaded (yet): the entry waits. */
    class NoSource : Exception("no source")

    private fun remove(sent: Entry) = synchronized(lock) {
        // A newer report merged in while this one was on its way stays queued, without the
        // listening time already sent.
        contents = contents.copy(
            pending = contents.pending.mapNotNull { current ->
                when {
                    current == sent -> null
                    current.serverId == sent.serverId && current.bookId == sent.bookId && current.sessionId == sent.sessionId ->
                        current.copy(listenedMs = (current.listenedMs - sent.listenedMs).coerceAtLeast(0))
                    else -> current
                }
            },
        )
        write()
    }

    private fun replace(old: Entry, new: Entry) = synchronized(lock) {
        contents = contents.copy(pending = contents.pending.map { if (it == old) new else it })
        write()
    }

    private fun write() {
        try {
            store.write(contents)
        } catch (e: IOException) {
            log.error("progress", "couldn't save the position: ${e.message}")
        }
    }

    companion object {
        const val PERIODIC_MS = 60_000L
    }
}
