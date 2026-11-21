package audio.own.bookplayer.playback

import audio.own.book.library.BatchResult
import audio.own.book.library.ListeningProgress
import audio.own.book.library.SourceException
import audio.own.bookplayer.app.AppLog
import audio.own.player.spoken.ReportReason
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When positions reach the server, and that none is lost on the way: offline, with a dead
 * server, a refused sign-in, a lost session, a slow request (book-ios `ProgressReporterTests`).
 */
@RunWith(RobolectricTestRunner::class)
class ProgressOutboxTest {
    @get:Rule val folder = TemporaryFolder()

    private val server = "s1"
    private val start = 1_000_000_000L
    private val file get() = File(folder.root, "pending.json")
    private var offline = false

    private fun outbox(
        batch: suspend (String, List<ProgressOutbox.Entry>) -> List<BatchResult>? = { _, _ -> null },
        send: suspend (ProgressOutbox.Entry) -> ListeningProgress? = { null },
    ) = ProgressOutbox(
        file = file,
        send = send,
        log = AppLog(File(folder.root, "logs"), echo = false),
        // Nothing runs by itself: the tests flush when they mean to.
        scope = CoroutineScope(Dispatchers.Unconfined),
        isOffline = { offline },
        sendBatch = batch,
        now = { start },
    )

    private fun entry(positionMs: Long, at: Long, book: String = "b1", session: String? = "s1", listened: Long = 10_000, serverId: String = server) =
        ProgressOutbox.Entry(
            serverId = serverId, bookId = book, sessionId = session, positionMs = positionMs, durationMs = 3_600_000,
            listenedMs = listened, finished = false, atMs = start + at * 1000,
        )

    private fun ProgressOutbox.tick(
        positionMs: Long,
        second: Long,
        reason: ReportReason = ReportReason.PERIODIC,
        book: String = "b1",
        session: String? = "s1",
        listened: Long = 10_000,
    ) = record(entry(positionMs, second, book, session, listened), reason, start + second * 1000)

    // region Cadence

    @Test
    fun `while playing, a report at the first tick and then once a minute`() {
        val box = outbox()
        val due = (10..190 step 10).filter { box.tick(it * 1000L, it.toLong()) }
        assertEquals(listOf(10, 70, 130, 190), due)
        assertEquals("one entry per book and session, however many ticks", 1, box.pending.size)
        assertEquals(190_000L, box.pending.single().positionMs)
        assertEquals("the listening adds up", 19 * 10_000L, box.pending.single().listenedMs)
    }

    @Test
    fun `pause, seek, chapter, stop, the end and going away are sent at once`() {
        for (reason in listOf(ReportReason.PAUSE, ReportReason.SEEK, ReportReason.CHAPTER, ReportReason.STOP, ReportReason.FINISHED, ReportReason.TEARDOWN)) {
            file.delete()
            val box = outbox()
            assertTrue(box.tick(10_000, 10))
            assertFalse(box.tick(20_000, 20))
            assertTrue("$reason", box.tick(21_000, 21, reason))
            // The minute counts again from that report.
            assertFalse(box.tick(30_000, 30))
            assertTrue(box.tick(81_000, 81))
        }
    }

    @Test
    fun `a book change is a stop of the old book, sent at once`() {
        assertTrue(ProgressOutbox.sendsNow(ReportReason.STOP, start, start + 1_000))
        assertFalse(ProgressOutbox.sendsNow(ReportReason.PERIODIC, start, start + 59_000))
        assertTrue(ProgressOutbox.sendsNow(ReportReason.PERIODIC, null, start))
    }

    @Test
    fun `a position is on disk before it is sent, and a closing report stays closing`() {
        val box = outbox()
        box.record(entry(1_000, 1, listened = 500).copy(closes = true), ReportReason.STOP)
        box.record(entry(2_000, 2, listened = 700), ReportReason.PAUSE)
        // A new process reads what the last one wrote.
        val again = outbox()
        val merged = again.pending.single()
        assertEquals(2_000L, merged.positionMs)
        assertEquals(1_200L, merged.listenedMs)
        assertTrue(merged.closes)
        assertEquals(2_000L, again.local("s1|b1")!!.positionMs)
    }

    // endregion

    // region Sending

    @Test
    fun `what the server takes leaves the queue, with its answer passed on`() = runBlocking {
        val sent = mutableListOf<Long>()
        var answers = 0
        val box = outbox { e -> sent += e.positionMs; ListeningProgress(e.positionMs, false, Instant.ofEpochMilli(e.atMs)) }
        box.onAnswer = { _, answer -> if (answer != null) answers++ }
        box.tick(10_000, 10, ReportReason.PAUSE, book = "b1")
        box.tick(5_000, 5, ReportReason.PAUSE, book = "b2")
        box.flush()
        assertEquals("oldest first", listOf(5_000L, 10_000L), sent)
        assertEquals(2, answers)
        assertTrue(box.pending.isEmpty())
    }

    @Test
    fun `offline, nothing is tried and everything stays on disk`() = runBlocking {
        offline = true
        var tries = 0
        val box = outbox { tries++; null }
        for (second in 10L..300L step 10) {
            if (box.tick(second * 1000, second)) box.flush()
        }
        assertEquals(0, tries)
        val onDisk = outbox().pending.single()
        assertEquals(300_000L, onDisk.positionMs)
        assertEquals(30 * 10_000L, onDisk.listenedMs)
    }

    @Test
    fun `a dead server is tried once a minute, not every tick, and the position stays`() = runBlocking {
        var tries = 0
        val box = outbox { tries++; throw SourceException.Network }
        for (second in 10L..180L step 10) {
            if (box.tick(second * 1000, second)) box.flush()
        }
        assertEquals("at 10, 70 and 130 s", 3, tries)
        assertEquals(180_000L, outbox().pending.single().positionMs)
    }

    @Test
    fun `an unreachable server stops the pass, later entries wait too`() = runBlocking {
        var tries = 0
        val box = outbox { tries++; throw SourceException.Offline }
        box.tick(1_000, 1, ReportReason.PAUSE, book = "b1")
        box.tick(2_000, 2, ReportReason.PAUSE, book = "b2")
        box.flush()
        assertEquals(1, tries)
        assertEquals(2, box.pending.size)
    }

    @Test
    fun `a refused sign-in is reported and the position kept`() = runBlocking {
        val refused = mutableListOf<String>()
        val box = outbox { throw SourceException.SignInRejected }
        box.onSignInRefused = { refused += it }
        box.tick(1_000, 1, ReportReason.PAUSE)
        box.flush()
        assertEquals(listOf(server), refused)
        assertEquals(1, box.pending.size)
    }

    @Test
    fun `a server that is not set up keeps its entries, the others are sent`() = runBlocking {
        val box = outbox { e -> if (e.serverId == "gone") throw ProgressOutbox.NoSource() else null }
        box.record(entry(1_000, 1, book = "x", session = null, serverId = "gone"), ReportReason.PAUSE)
        box.tick(2_000, 2, ReportReason.PAUSE)
        box.flush()
        assertEquals(listOf("gone"), box.pending.map { it.serverId })
    }

    @Test
    fun `a play session the server no longer knows, the position goes again without it`() = runBlocking {
        val sessions = mutableListOf<String?>()
        val box = outbox { e ->
            sessions += e.sessionId
            if (e.sessionId != null) throw SourceException.NotFound
            null
        }
        box.tick(7_000, 7, ReportReason.PAUSE, session = "old")
        box.flush()
        assertEquals(listOf<String?>("old"), sessions)
        assertEquals(listOf<String?>(null), box.pending.map { it.sessionId })
        assertEquals(7_000L, box.pending.single().positionMs)
        box.flush()
        assertTrue(box.pending.isEmpty())
    }

    @Test
    fun `another refusal drops the entry`() = runBlocking {
        val box = outbox { throw SourceException.Server("no") }
        box.tick(1_000, 1, ReportReason.PAUSE)
        box.flush()
        assertTrue(box.pending.isEmpty())
    }

    @Test
    fun `a pause while a report is on its way is sent right after it, not lost`() = runBlocking {
        val sent = mutableListOf<Pair<Long, Long>>()
        var paused = false
        lateinit var box: ProgressOutbox
        box = outbox { e ->
            sent += e.positionMs to e.listenedMs
            if (!paused) {
                // The listener pauses while the first report is still out.
                paused = true
                box.tick(15_000, 15, ReportReason.PAUSE, listened = 5_000)
                box.flush()
            }
            null
        }
        box.tick(10_000, 10)
        box.flush()
        assertEquals(listOf(10_000L, 15_000L), sent.map { it.first })
        assertEquals("the listening already sent is not sent twice", listOf(10_000L, 5_000L), sent.map { it.second })
        assertTrue(box.pending.isEmpty())
    }

    // endregion

    // region Batches and the server's newer position

    @Test
    fun `several positions of one server go in one batch, a stale one takes the server's`() = runBlocking {
        val batches = mutableListOf<List<Long>>()
        var singles = 0
        val stale = mutableListOf<String>()
        val box = outbox(
            batch = { _, entries ->
                batches += entries.map { it.positionMs }
                listOf(BatchResult.SENT, BatchResult.STALE, BatchResult.REFUSED)
            },
            send = { singles++; null },
        )
        box.onStale = { stale += it.bookId }
        box.tick(3_000, 3, ReportReason.PAUSE, book = "b3", session = null)
        box.tick(1_000, 1, ReportReason.PAUSE, book = "b1", session = null)
        box.tick(2_000, 2, ReportReason.PAUSE, book = "b2", session = null)
        box.flush()
        assertEquals("oldest first, in one request", listOf(listOf(1_000L, 2_000L, 3_000L)), batches)
        assertEquals(0, singles)
        assertEquals(listOf("b2"), stale)
        assertTrue(box.pending.isEmpty())
    }

    @Test
    fun `a server without batches, or one entry alone, goes one at a time`() = runBlocking {
        var batches = 0
        val sent = mutableListOf<String>()
        val box = outbox(batch = { _, _ -> batches++; null }, send = { sent += it.bookId; null })
        box.tick(1_000, 1, ReportReason.PAUSE, book = "b1", session = null)
        box.tick(2_000, 2, ReportReason.PAUSE, book = "b2", session = null)
        box.flush()
        assertEquals(listOf("b1", "b2"), sent)

        batches = 0
        box.tick(3_000, 3, ReportReason.PAUSE, book = "b3", session = null)
        box.flush()
        assertEquals("one entry needs no batch", 0, batches)
    }

    @Test
    fun `a batch that can't reach the server keeps everything`() = runBlocking {
        val box = outbox(batch = { _, _ -> throw SourceException.Network })
        box.tick(1_000, 1, ReportReason.PAUSE, book = "b1", session = null)
        box.tick(2_000, 2, ReportReason.PAUSE, book = "b2", session = null)
        box.flush()
        assertEquals(2, box.pending.size)
    }

    @Test
    fun `the server's newer position becomes this phone's, and a dropped book forgets both`() {
        val box = outbox()
        box.tick(1_000, 1, ReportReason.PAUSE)
        box.setLocal("s1|b1", ListeningProgress(400_000, false, Instant.ofEpochMilli(start + 50_000)))
        assertEquals(400_000L, box.local("s1|b1")!!.positionMs)
        box.drop("s1", "b1")
        assertTrue(box.pending.isEmpty())
        assertNull(box.local("s1|b1"))
    }

    // endregion
}
