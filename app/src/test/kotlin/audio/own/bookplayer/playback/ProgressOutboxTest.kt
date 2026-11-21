package audio.own.bookplayer.playback

import audio.own.book.library.PlaybackReport
import audio.own.book.library.SourceException
import audio.own.bookplayer.app.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProgressOutboxTest {
    @get:Rule val folder = TemporaryFolder()

    private val sent = mutableListOf<PlaybackReport>()
    private var failWith: Exception? = null
    private val file get() = File(folder.root, "pending.json")

    private fun outbox() = ProgressOutbox(
        file = file,
        send = { _, report -> failWith?.let { throw it }; sent += report; null },
        // Nothing runs by itself: the tests flush when they mean to.
        scope = CoroutineScope(Dispatchers.Unconfined),
        log = AppLog(File(folder.root, "logs"), echo = false),
        now = { 0 },
    )

    private fun entry(position: Long, listened: Long, at: Long, session: String? = null, closes: Boolean = false) =
        ProgressOutbox.Entry("s1", "b1", session, position, 100_000, listened, finished = false, atMs = at, closes = closes)

    @Test
    fun `a position is on disk before it is sent, and merged per book and session`() {
        failWith = SourceException.Network
        val box = outbox()
        box.record(entry(1_000, 500, at = 1), immediate = true)
        box.record(entry(2_000, 700, at = 2, closes = true), immediate = true)
        // A new process reads what the last one wrote.
        val again = outbox()
        assertEquals(1, again.pending.size)
        val merged = again.pending.single()
        assertEquals(2_000L, merged.positionMs)
        assertEquals(1_200L, merged.listenedMs)
        assertTrue(merged.closes)
        assertEquals(2_000L, again.local("s1|b1")!!.positionMs)
    }

    @Test
    fun `a flush sends oldest first and empties the queue`() = runBlocking {
        failWith = SourceException.Network
        val box = outbox()
        box.record(entry(5_000, 100, at = 5, session = "new"), immediate = false)
        box.record(entry(1_000, 100, at = 1, session = "old"), immediate = false)
        failWith = null
        box.flush()
        assertEquals(listOf(1_000L, 5_000L), sent.map { it.positionMs })
        assertTrue(box.pending.isEmpty())
    }

    @Test
    fun `a network error keeps the entry, a refusal drops it, a lost session goes without it`() = runBlocking {
        failWith = SourceException.Network
        val box = outbox()
        box.record(entry(1_000, 100, at = 1, session = "gone"), immediate = false)
        box.flush()
        assertEquals(1, box.pending.size)
        failWith = SourceException.NotFound
        box.flush()
        assertNull(box.pending.single().sessionId)
        failWith = SourceException.Server("no")
        box.flush()
        assertTrue(box.pending.isEmpty())
    }
}
