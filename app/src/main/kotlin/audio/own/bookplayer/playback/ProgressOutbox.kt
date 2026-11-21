package audio.own.bookplayer.playback

import audio.own.book.library.BatchResult
import audio.own.book.library.BookId
import audio.own.book.library.ListeningProgress
import audio.own.book.library.SourceException
import audio.own.bookplayer.app.AppLog
import audio.own.player.core.JsonFile
import audio.own.player.spoken.ReportReason
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Positions on their way to the servers (book-ios `PendingProgress.swift` and
 * `ProgressReporter.swift`). Every report is written to disk **before** anything is sent, merged
 * per server, book and play session (the newer position replaces, listened time adds up, a
 * closing report stays closing), and removed once the server has it. It goes at once on a pause,
 * seek, chapter, stop, the end or teardown; while a book just plays, at most every minute.
 * Oldest first; no network stops the pass until the next checkpoint. Also keeps where this phone
 * last was in each book, for the resume choice.
 */
class ProgressOutbox(
    file: File,
    /** Sends one entry; the server's answer, if it gives one. Throws [NoSource] when the server isn't set up now. */
    private val send: suspend (Entry) -> ListeningProgress?,
    private val log: AppLog,
    private val scope: CoroutineScope,
    private val isOffline: () -> Boolean = { false },
    /**
     * Several entries of one server in one request (own.audio's `/sync/push`); null when the
     * server takes them one at a time.
     */
    private val sendBatch: suspend (serverId: String, entries: List<Entry>) -> List<BatchResult>? = { _, _ -> null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val serverId: String,
        val bookId: String,
        val sessionId: String? = null,
        val positionMs: Long,
        val durationMs: Long,
        val listenedMs: Long,
        val finished: Boolean,
        val atMs: Long,
        val closes: Boolean = false,
    ) {
        val compositeId: String get() = BookId.of(serverId, bookId).value
    }

    @Serializable
    data class Position(val positionMs: Long, val finished: Boolean, val atMs: Long)

    @Serializable
    data class Contents(val pending: List<Entry> = emptyList(), val positions: Map<String, Position> = emptyMap())

    /** Thrown by `send` when the entry's server isn't set up right now: the entry stays queued. */
    class NoSource : Exception("no source")

    /** While playing, a report at most this often; Audiobookshelf's own apps sync about as often. */
    var intervalMs: Long = PERIODIC_MS

    /** What the server answered for an entry it took. */
    var onAnswer: (Entry, ListeningProgress?) -> Unit = { _, _ -> }

    /** The server had a newer position than the entry (sent in a batch): take it from the server. */
    var onStale: suspend (Entry) -> Unit = {}

    var onSignInRefused: (serverId: String) -> Unit = {}

    private val store = JsonFile(file, Contents.serializer())
    private val lock = Any()
    private var contents: Contents = store.read() ?: Contents()

    /**
     * The last checkpoint: when a position was last due. Kept whether the report reached the
     * server or not, so a dead server is tried again only at the next one.
     */
    @Volatile
    var lastCheckpointAt: Long? = null
        private set

    private var flushing = false
    private var flushAgain = false

    val pending: List<Entry> get() = synchronized(lock) { contents.pending }

    /** Where this phone last was in [compositeId], as a progress for [audio.own.book.library.ResumeChoice]. */
    fun local(compositeId: String): ListeningProgress? =
        synchronized(lock) { contents.positions[compositeId] }?.let { ListeningProgress(it.positionMs, it.finished, Instant.ofEpochMilli(it.atMs)) }

    /** The server's position becomes this phone's (another device was newer). */
    fun setLocal(compositeId: String, progress: ListeningProgress) = synchronized(lock) {
        contents = contents.copy(positions = contents.positions + (compositeId to Position(progress.positionMs, progress.finished, progress.updatedAt.toEpochMilli())))
        write()
    }

    /**
     * Stores a report on disk and says whether it is a checkpoint: due to be sent now. The
     * caller flushes when it is.
     */
    fun record(entry: Entry, reason: ReportReason, at: Long = now()): Boolean {
        enqueue(entry)
        if (!sendsNow(reason, lastCheckpointAt, at, intervalMs)) return false
        lastCheckpointAt = at
        return true
    }

    /** One entry per book and session: a server that was down for an hour gets one report, not 360. */
    private fun enqueue(entry: Entry) = synchronized(lock) {
        val merged = contents.pending.toMutableList()
        val index = merged.indexOfFirst { it.serverId == entry.serverId && it.bookId == entry.bookId && it.sessionId == entry.sessionId }
        if (index >= 0) {
            val old = merged[index]
            merged[index] = old.copy(
                positionMs = entry.positionMs, listenedMs = old.listenedMs + entry.listenedMs, finished = entry.finished,
                atMs = entry.atMs, closes = old.closes || entry.closes,
            )
        } else {
            merged += entry
        }
        contents = Contents(merged, contents.positions + (entry.compositeId to Position(entry.positionMs, entry.finished, entry.atMs)))
        write()
    }

    /** Everything queued for one book, and this phone's place in it, when its position is set by hand or forgotten. */
    fun drop(serverId: String, bookId: String) = synchronized(lock) {
        contents = Contents(
            pending = contents.pending.filterNot { it.serverId == serverId && it.bookId == bookId },
            positions = contents.positions - BookId.of(serverId, bookId).value,
        )
        write()
    }

    /** A removed server's queue and places leave with it. */
    fun forgetServer(serverId: String) = synchronized(lock) {
        contents = Contents(
            pending = contents.pending.filterNot { it.serverId == serverId },
            positions = contents.positions.filterKeys { BookId(it).serverId != serverId },
        )
        write()
    }

    fun flushSoon() {
        scope.launch { flush() }
    }

    /**
     * Sends what is on disk, oldest first: what the server refuses for good is dropped, what
     * could not reach it stays for the next try. Offline, nothing is tried. A report due while
     * another is on its way (a pause during a slow request) goes right after it.
     */
    suspend fun flush() {
        if (isOffline()) return
        synchronized(lock) {
            if (flushing) {
                flushAgain = true
                return
            }
            flushing = true
            flushAgain = false
        }
        try {
            while (sendQueued()) {
                val again = synchronized(lock) { flushAgain.also { flushAgain = false } }
                if (!again) return
            }
        } finally {
            synchronized(lock) { flushing = false }
        }
    }

    /** One pass over the queue; false when a server could not be reached. */
    private suspend fun sendQueued(): Boolean {
        val queue = pending.sortedBy { it.atMs }
        val batched = HashSet<String>()
        for (entry in queue) {
            if (entry.sessionId == null && entry.serverId in batched) continue
            val group = queue.filter { it.serverId == entry.serverId && it.sessionId == null }
            if (entry.sessionId == null && group.size > 1) {
                when (sendInBatches(entry.serverId, group)) {
                    true -> {
                        batched += entry.serverId
                        continue
                    }
                    false -> return false
                    null -> Unit
                }
            }
            if (pending.none { it.id == entry.id }) continue
            if (!sendOne(entry)) return false
        }
        return true
    }

    /** False when the server could not be reached. */
    private suspend fun sendOne(entry: Entry): Boolean = try {
        val answer = send(entry)
        markSent(entry)
        onAnswer(entry, answer)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        when {
            e is NoSource -> true
            unreachable(e) -> false
            e == SourceException.SignInRejected -> {
                onSignInRefused(entry.serverId)
                false
            }
            e == SourceException.NotFound -> {
                // The session is over or the book is gone: the position goes without a session.
                remove(entry.id)
                if (entry.sessionId != null) enqueue(entry.copy(id = UUID.randomUUID().toString(), sessionId = null, closes = false))
                true
            }
            else -> {
                log.error("progress", "a position was refused: ${e.message}")
                remove(entry.id)
                true
            }
        }
    }

    /**
     * True when the server took them all this way, false when it could not be reached, null
     * when they are to go one at a time (no batches there, or the batch was refused as a whole).
     */
    private suspend fun sendInBatches(serverId: String, group: List<Entry>): Boolean? {
        for (chunk in group.chunked(BATCH_LIMIT)) {
            val results = try {
                sendBatch(serverId, chunk) ?: return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return when {
                    e is NoSource -> true
                    unreachable(e) -> false
                    e == SourceException.SignInRejected -> {
                        onSignInRefused(serverId)
                        false
                    }
                    else -> null
                }
            }
            chunk.zip(results).forEach { (entry, result) ->
                when (result) {
                    BatchResult.SENT -> {
                        markSent(entry)
                        onAnswer(entry, null)
                    }
                    BatchResult.STALE -> {
                        markSent(entry)
                        try {
                            onStale(entry)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.error("progress", "couldn't read the newer position: ${e.message}")
                        }
                    }
                    BatchResult.REFUSED -> {
                        log.error("progress", "a position was refused in a batch")
                        remove(entry.id)
                    }
                }
            }
        }
        return true
    }

    private fun unreachable(e: Exception) = e is IOException || e == SourceException.Network || e == SourceException.Offline

    /**
     * After [sent] reached the server. A newer position recorded into it while it was on its way
     * stays, with only the listening not yet sent.
     */
    private fun markSent(sent: Entry) = synchronized(lock) {
        contents = contents.copy(
            pending = contents.pending.mapNotNull { current ->
                when {
                    current.id != sent.id -> current
                    current.atMs == sent.atMs -> null
                    else -> current.copy(listenedMs = (current.listenedMs - sent.listenedMs).coerceAtLeast(0))
                }
            },
        )
        write()
    }

    private fun remove(id: String) = synchronized(lock) {
        contents = contents.copy(pending = contents.pending.filterNot { it.id == id })
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
        const val BATCH_LIMIT = 500

        /** Whether a position for this reason goes to the server now rather than waiting. */
        fun sendsNow(reason: ReportReason, lastCheckpointAt: Long?, now: Long, intervalMs: Long = PERIODIC_MS): Boolean = when (reason) {
            ReportReason.PERIODIC -> lastCheckpointAt == null || now - lastCheckpointAt >= intervalMs
            ReportReason.PAUSE, ReportReason.SEEK, ReportReason.CHAPTER, ReportReason.STOP, ReportReason.FINISHED, ReportReason.TEARDOWN -> true
        }
    }
}
