package audio.own.book.library.catalogue

import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.SnapshotLine
import audio.own.kit.ownaudio.changes
import audio.own.kit.ownaudio.httpStatus
import audio.own.kit.ownaudio.snapshot
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One own.audio server's library on the phone, kept up to date from its change feed
 * (docs/android-foundation.md §9.1): a snapshot once, streamed into Room 500 records per
 * transaction, then `/sync/changes` page by page with the cursor committed in the same
 * transaction as the changes it brought. Shared by every source built for the server, so a new
 * address does not mean a new snapshot.
 */
class OwnAudioCatalogue(
    val database: BookCatalogueDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = database.dao()
    private val writer = CatalogueWriter(dao)
    private val syncing = Mutex()
    private val writes = AtomicLong()

    /** Changes whenever the catalogue was written, so a copy read from it knows it is stale. */
    val version: Long get() = writes.get()

    suspend fun feedState(): FeedStateRow? = dao.feedState()

    /** True once a snapshot has ended at least once. */
    suspend fun isLoaded(): Boolean = dao.feedState()?.cursor != null

    /**
     * Brings the catalogue up to date with [client]: changes since the cursor, or a snapshot
     * (fresh, or resuming one cut short) when there is no cursor or the server says to start
     * over. One sync at a time; a second caller waits and then finds little to do.
     */
    suspend fun sync(client: OwnAudioClient): Unit = syncing.withLock {
        val state = dao.feedState()
        when {
            state?.snapshotCursor != null -> snapshot(client, resume = true)
            state?.cursor == null -> snapshot(client, resume = false)
            !changes(client) -> snapshot(client, resume = false)
        }
    }

    /** Another household's library, or a sign-in as someone else: the next sync starts over. */
    suspend fun reset() {
        syncing.withLock {
            database.inTransaction { writer.clear() }
            writes.incrementAndGet()
        }
    }

    // region Snapshot

    private suspend fun snapshot(client: OwnAudioClient, resume: Boolean) {
        val before = dao.feedState()
        val gen = if (resume && before?.snapshotGen != null) before.snapshotGen else maxOf(before?.gen ?: 0, before?.snapshotGen ?: 0) + 1
        var cursor = if (resume) before?.snapshotCursor else null
        var after = if (resume) before?.snapshotAfter else null
        var resumes = 0
        while (true) {
            val outcome = try {
                readSnapshot(client, gen, cursor, after)
            } catch (e: OwnAudioException.Problem) {
                // The cursor a resume names has expired: start over with a new generation.
                if (cursor != null && isExpiredCursor(e)) return snapshot(client, resume = false)
                throw e
            }
            when (outcome) {
                is SnapshotOutcome.Ended -> break
                is SnapshotOutcome.CutShort -> {
                    cursor = outcome.cursor
                    after = outcome.after
                    if (++resumes > MAX_RESUMES) throw OwnAudioException.Network(null)
                }
            }
        }
        changes(client)
    }

    private sealed interface SnapshotOutcome {
        data object Ended : SnapshotOutcome
        data class CutShort(val cursor: String?, val after: String?) : SnapshotOutcome
    }

    private suspend fun readSnapshot(client: OwnAudioClient, gen: Long, startCursor: String?, startAfter: String?): SnapshotOutcome {
        val batch = mutableListOf<SnapshotLine.Record>()
        var cursor = startCursor
        var after = startAfter
        var end: String? = null

        suspend fun flush() {
            val records = batch.toList()
            batch.clear()
            database.inTransaction {
                records.forEach { writer.upsert(it.kind, it.record, gen) }
                dao.setFeedState(currentState().copy(snapshotGen = gen, snapshotCursor = cursor, snapshotAfter = after))
            }
            writes.incrementAndGet()
        }

        try {
            client.snapshot(kinds = SNAPSHOT_KINDS, after = startAfter, cursor = startCursor).collect { line ->
                when (line) {
                    is SnapshotLine.Begin -> if (cursor == null) {
                        cursor = line.cursor
                        // Kept at once, so a snapshot cut short before its first batch resumes too.
                        flush()
                    }
                    is SnapshotLine.Record -> {
                        batch += line
                        after = "${line.kind}:${line.id}"
                        if (batch.size >= BATCH) flush()
                    }
                    is SnapshotLine.End -> end = line.cursor
                    is SnapshotLine.Id -> Unit
                }
            }
        } catch (e: OwnAudioException.SnapshotTruncated) {
            // What arrived is good: keep it and go on from the last record written.
            flush()
            return SnapshotOutcome.CutShort(cursor ?: e.cursor, after ?: e.after)
        }
        val endCursor = end ?: cursor ?: throw OwnAudioException.SnapshotTruncated(after, cursor)
        val records = batch.toList()
        database.inTransaction {
            records.forEach { writer.upsert(it.kind, it.record, gen) }
            writer.dropOlderThan(gen)
            dao.setFeedState(
                FeedStateRow(cursor = endCursor, gen = gen, snapshotGen = null, snapshotCursor = null, snapshotAfter = null, syncedAt = clock.millis()),
            )
        }
        writes.incrementAndGet()
        return SnapshotOutcome.Ended
    }

    // endregion

    // region Changes

    /** Every page of changes since the cursor; false when the server says to take a snapshot instead. */
    private suspend fun changes(client: OwnAudioClient): Boolean {
        var cursor = dao.feedState()?.cursor ?: return false
        while (true) {
            val page = try {
                client.changes(cursor, kinds = SNAPSHOT_KINDS, limit = BATCH)
            } catch (e: OwnAudioException) {
                if (isExpiredCursor(e)) return false
                throw e
            }
            if (page.reset) return false
            database.inTransaction {
                val gen = activeGen(currentState())
                for (change in page.changes) {
                    val record = change.record
                    if (change.op == "upsert" && record != null) writer.upsert(change.kind, record, gen) else writer.delete(change.kind, change.id)
                }
                dao.setFeedState(currentState().copy(cursor = page.cursor, syncedAt = clock.millis()))
            }
            writes.incrementAndGet()
            if (!page.hasMore) return true
            cursor = page.cursor
        }
    }

    private fun isExpiredCursor(e: OwnAudioException): Boolean =
        e.httpStatus == 410 || (e is OwnAudioException.Problem && (e.code == "sync_cursor_expired" || e.code == "invalid_cursor"))

    // endregion

    // region What the source learns between syncs

    /**
     * Writes what a call to the server answered (a saved position, a new bookmark) so the copy
     * agrees with the server before the feed brings the same change.
     */
    internal suspend fun remember(block: suspend CatalogueWriter.(gen: Long) -> Unit) {
        database.inTransaction { writer.block(activeGen(currentState())) }
        writes.incrementAndGet()
    }

    internal suspend fun forget(kind: String, id: String) {
        database.inTransaction { writer.delete(kind, id) }
        writes.incrementAndGet()
    }

    // endregion

    private suspend fun currentState(): FeedStateRow =
        dao.feedState() ?: FeedStateRow(cursor = null, gen = 0, snapshotGen = null, snapshotCursor = null, snapshotAfter = null, syncedAt = null)

    /** A row written now belongs to the snapshot under way, if there is one, so it isn't dropped when that ends. */
    private fun activeGen(state: FeedStateRow): Long = state.snapshotGen ?: state.gen

    companion object {
        /** What the snapshot holds for a book library. */
        val SNAPSHOT_KINDS = listOf("contributor", "book", "series", "collection", "favourite", "progress", "bookmark")

        const val BATCH = 500

        /** Resumes of one snapshot within one sync; the next sync goes on from where this one stopped. */
        const val MAX_RESUMES = 3
    }
}
