package audio.own.bookplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import audio.own.book.library.Book
import audio.own.book.library.BookFile
import audio.own.book.library.BookId
import audio.own.book.library.BookSource
import audio.own.book.library.ChangeFeedCatalogue
import audio.own.book.library.Chapter
import audio.own.book.library.ListeningProgress
import audio.own.book.library.Person
import audio.own.book.library.PlaybackReport
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.browse.FakeBookSource
import audio.own.bookplayer.library.ActiveLibrary
import audio.own.bookplayer.library.PlaybackSources
import audio.own.core.servers.MobileData
import audio.own.core.servers.NetworkState
import audio.own.core.servers.OfflineSwitch
import audio.own.player.spoken.SpokenPlayer
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * book-ios `MisuseTests`, for the player: things a listener isn't meant to do, done anyway —
 * buttons hammered, Stop while the book is still loading, the server removed or the network gone
 * mid-book. Nothing here checks a feature; each checks the player is still usable after, and
 * that no position is lost. A real ExoPlayer plays a silent WAV for every part.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackMisuseTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val network = MutableStateFlow(WIFI)
    private val offline = OfflineSwitch()
    private val source = MisuseSource()
    private val known = Collections.synchronizedMap(mutableMapOf<String, BookSource>("s1" to source))
    private val sources = object : PlaybackSources {
        override val active = MutableStateFlow<ActiveLibrary?>(null)
        override fun source(serverId: String): BookSource? = known[serverId]
        override suspend fun sync(source: ChangeFeedCatalogue) = Unit
    }
    private var clock = 1_000_000L
    private lateinit var exo: ExoPlayer
    private lateinit var spoken: SpokenPlayer
    private lateinit var model: PlaybackModel

    @Before
    fun setUp() {
        val wav = silentWav(File(folder.root, "part.wav"), PART_MS)
        // Every part's placeholder link plays the same file: the links are not what is tested.
        val files = ResolvingDataSource.Factory(DefaultDataSource.Factory(context)) { spec -> spec.withUri(Uri.fromFile(wav)) }
        exo = TestExoPlayerBuilder(context).setMediaSourceFactory(DefaultMediaSourceFactory(files)).build()
        spoken = SpokenPlayer(exo, scope, now = { clock })
        model = PlaybackModel(
            context = context, sources = sources, servers = null, settings = MemoryPlaybackSettings(), network = network,
            offline = offline, log = AppLog(null, echo = false), filesDir = folder.newFolder("files"),
            engineFactory = { PlaybackEngine(spoken, null) }, downloads = null, connectsService = false,
        )
    }

    @After
    fun tearDown() {
        spoken.release()
        scope.cancel()
    }

    private val composite = BookId.of("s1", BOOK.id).value

    private fun ready() = run(exo).withTimeoutMs(RUN_TIMEOUT_MS).untilState(Player.STATE_READY)

    /** Lets the main thread and the player work until [condition] holds. */
    private fun until(what: String, timeoutMs: Long = RUN_TIMEOUT_MS, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            Thread.sleep(2)
        }
    }

    private fun startPlaying(at: Long = 0) {
        model.play("s1", BOOK, at)
        until("the book in the player") { spoken.bookId == composite }
        ready()
        until("playing") { model.state.value.isPlaying }
    }

    /** Plays on and checks the position moves: the player is usable. */
    private fun assertStillPlays() {
        if (!model.state.value.isPlaying) model.toggle()
        until("playing again") { model.state.value.isPlaying }
        val before = spoken.positionMs()
        until("the position moving from $before") { spoken.positionMs() > before + 200 }
        assertNull(model.state.value.error)
    }

    @Test
    fun `a hammered player still plays, and the position stays in the book`() {
        startPlaying(at = 5_000)
        repeat(40) { i ->
            when (i % 7) {
                0 -> model.toggle()
                1 -> model.skipForward()
                2 -> model.skipBack()
                3 -> model.nextChapter()
                4 -> model.previousChapter()
                5 -> model.seek((i * 7_919L) % BOOK.durationMs)
                else -> model.setSpeed(if (i % 2 == 0) 3f else 0.5f)
            }
        }
        // A seek that lands past the end is the end, never beyond it.
        model.seek(BOOK.durationMs * 2)
        model.seek(-5_000)
        until("the commands handled") { spoken.positionMs() in 0..BOOK.durationMs }
        assertStillPlays()
        val position = spoken.positionMs()
        assertTrue("at $position", position in 0..BOOK.durationMs)
        until("the screens showing it") { kotlin.math.abs(model.state.value.positionMs - spoken.positionMs()) < 1_500 }
        val saved = model.outbox.local(composite)
        assertTrue("a position on disk", saved != null && saved.positionMs in 0..BOOK.durationMs)
    }

    @Test
    fun `stop while the book is still loading, nothing starts when the load lands, and playing again works`() {
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        // Without its files the book is asked for first; that answer is held back.
        model.play("s1", BOOK.copy(files = null), null)
        until("loading") { model.state.value.isLoading }
        model.stop()
        gate.complete(Unit)
        repeat(50) { ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS) }
        assertNull("nothing loaded", spoken.bookId)
        assertFalse(exo.playWhenReady)
        assertFalse(model.state.value.hasBook)

        source.gate = null
        startPlaying()
        assertStillPlays()
    }

    @Test
    fun `play pressed again and again while loading starts the book once`() {
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        repeat(10) { model.play("s1", BOOK.copy(files = null), null) }
        gate.complete(Unit)
        until("the book in the player") { spoken.bookId == composite }
        ready()
        assertEquals(BOOK.files!!.size, exo.mediaItemCount)
        assertStillPlays()
    }

    @Test
    fun `a pause's position reaching the server tells the screens to read again`() = runBlocking {
        startPlaying(at = 5_000)
        val changed = CompletableDeferred<Unit>()
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { model.progressChanged.first(); changed.complete(Unit) }
        val sentBefore = source.reports.size
        model.pause()
        until("the pause sent") { source.reports.size > sentBefore }
        until("the screens told") { changed.isCompleted }
        watcher.cancel()
    }

    @Test
    fun `the server removed mid-book stops the book and nothing is sent to it afterwards`() {
        startPlaying()
        model.pause()
        until("paused") { !model.state.value.isPlaying }
        known.remove("s1")
        model.forgetServer("s1")
        until("the player empty") { spoken.bookId == null && !model.state.value.hasBook }
        assertFalse(exo.playWhenReady)
        assertNull("the mini player forgets it", model.lastPlayed)
        until("the queue empty") { model.outbox.pending.none { it.serverId == "s1" } }
        // Play on a headset now has nothing to resume, and says so instead of failing.
        model.toggle()
        repeat(20) { ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS) }
        assertNull(spoken.bookId)
    }

    @Test
    fun `ten minutes without a network mid-book keep every position, and they go out when it is back`() {
        startPlaying(at = 3_000)
        val sentBefore = source.reports.size
        network.value = NO_NETWORK
        offline.update(offlineMode = false, network = NO_NETWORK, mobileData = MobileData.ORIGINAL)
        assertTrue(offline.isOffline)
        until("some listening") { spoken.positionMs() > 4_000 }
        clock += 10 * 60_000L
        model.pause()
        until("paused") { !model.state.value.isPlaying }
        val kept = spoken.positionMs()
        assertEquals("on disk while offline", kept, model.outbox.local(composite)!!.positionMs, 100.0)
        assertEquals("nothing sent while offline", sentBefore, source.reports.size)
        assertTrue(model.outbox.pending.isNotEmpty())

        network.value = WIFI
        offline.update(offlineMode = false, network = WIFI, mobileData = MobileData.ORIGINAL)
        until("the queue sent") { model.outbox.pending.isEmpty() }
        val last = source.reports.last()
        assertEquals(kept, last.positionMs, 100.0)
        assertStillPlays()
    }

    @Test
    fun `a book whose server answers nothing says so and the next book still plays`() {
        source.failBook = audio.own.book.library.SourceException.Network
        model.play("s1", BOOK.copy(files = null), null)
        until("the failure shown") { model.state.value.error != null }
        assertFalse(model.state.value.isLoading)
        source.failBook = null
        startPlaying()
        assertStillPlays()
    }

    private fun assertEquals(message: String, expected: Long, actual: Long, delta: Double) =
        org.junit.Assert.assertEquals(message, expected.toDouble(), actual.toDouble(), delta)

    private fun assertEquals(expected: Long, actual: Long, delta: Double) = assertEquals("", expected, actual, delta)

    /** A source whose book answer can be held back or refused, and that keeps what it was told. */
    private class MisuseSource : FakeBookSource(books = listOf(BOOK)) {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failBook: Exception? = null
        val reports: MutableList<PlaybackReport> = Collections.synchronizedList(mutableListOf())

        override suspend fun book(id: String): Book {
            gate?.await()
            failBook?.let { throw it }
            return super.book(id)
        }

        override suspend fun progress(bookId: String): ListeningProgress? = null

        override suspend fun report(report: PlaybackReport): ListeningProgress? {
            reports += report
            return null
        }
    }

    private companion object {
        const val RUN_TIMEOUT_MS = 60_000L
        const val PART_MS = 8_000L
        val WIFI = NetworkState(connected = true, metered = false, dataSaver = false, signature = "wifi")
        val NO_NETWORK = NetworkState(connected = false, metered = false, dataSaver = false, signature = "none")

        val BOOK = Book(
            id = "m1", title = "A Book Played Badly", authors = listOf(Person("a1", "An Author")), narrators = emptyList(), series = emptyList(),
            durationMs = 3 * PART_MS, addedAt = Instant.EPOCH,
            files = BookFile.timeline((1..3).map { BookFile.Entry("f$it", "Part $it", PART_MS, 1_000) }),
            chapters = listOf(Chapter("One", 0, 5_000), Chapter("Two", 5_000, 14_000), Chapter("Three", 14_000, 3 * PART_MS)),
        )

        /** [ms] of 8 kHz mono silence: ExoPlayer plays WAV with no decoder of its own. */
        fun silentWav(file: File, ms: Long): File {
            val rate = 8_000
            val dataBytes = (rate * ms / 1000 * 2).toInt()
            RandomAccessFile(file, "rw").use { out ->
                fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
                fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
                out.write("RIFF".toByteArray()); int(36 + dataBytes); out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
                out.write("data".toByteArray()); int(dataBytes)
                out.write(ByteArray(dataBytes))
            }
            return file
        }
    }
}
