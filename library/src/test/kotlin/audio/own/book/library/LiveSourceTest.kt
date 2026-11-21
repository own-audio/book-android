package audio.own.book.library

import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.AudiobookshelfHttp
import audio.own.kit.audiobookshelf.TokenSession as AbsTokenSession
import audio.own.kit.audiobookshelf.bookLibraries
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.signIn
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Both sources against real servers, through the same [BookSource] interface; skipped unless the
 * environment names a server (from the gitignored `test-servers.local.env`):
 *
 * ```
 * OWNAUDIO_HOST=… OWNAUDIO_EMAIL=… OWNAUDIO_PASSWORD=… \
 * AUDIOBOOKSHELF_HOST=… AUDIOBOOKSHELF_USERNAME=… AUDIOBOOKSHELF_PASSWORD=… ./gradlew :library:testDebugUnitTest
 * ```
 *
 * The accounts should be throwaways: the tests play, and write and then clear a position on the
 * shortest book. Audiobookshelf limits sign-ins, so its test signs in once.
 */
@RunWith(RobolectricTestRunner::class)
class LiveSourceTest {
    private val env = System.getenv()
    private val userAgent = "own.audio Books/0.0 (Android live test; JVM)"

    private suspend fun exercise(source: BookSource) {
        assertEquals(ConnectionStatus.OK, source.checkConnection())
        assertTrue(source.shelves().none { it.isEmpty })
        assertTrue(source.books(BookQuery(sort = BookSort.DURATION), null, 3).books.isNotEmpty())
        // The shortest real book; an import test elsewhere may leave a book with no duration.
        val shortest = source.allBooks(BookQuery(sort = BookSort.DURATION)).first { it.durationMs > 0 }
        val book = source.book(shortest.id)
        val files = book.files!!
        assertTrue(files.isNotEmpty() && files.last().endMs > 0)
        assertTrue(abs(files.last().endMs - book.durationMs) <= 2000)
        book.chapters?.takeIf { it.isNotEmpty() }?.let { assertTrue(it.first().startMs == 0L && it.last().endMs <= book.durationMs + 1000) }
        source.authors()
        source.narrators()
        if (Capability.SERIES in source.capabilities) {
            source.series().firstOrNull()?.let { assertFalse(source.series(it.id).books.isNullOrEmpty()) }
        }
        if (Capability.COLLECTIONS in source.capabilities) source.collections()
        val prefix = book.title.take(4)
        assertTrue(source.search(prefix).books.any { it.id == book.id })
        assertTrue(source.books(BookQuery(text = prefix), null, 10).books.any { it.id == book.id })

        val saved = source.saveProgress(book.id, 42_500, book.durationMs, finished = false, at = Instant.now())
        assertEquals(42_500L, saved.positionMs)
        val read = source.progress(book.id)!!
        assertTrue(abs(read.positionMs - 42_500) < 1000 && !read.finished)
        if (source is ChangeFeedCatalogue) source.syncCatalogue()
        assertNotNull(source.allProgress()[book.id])
        assertTrue(source.books(BookQuery(filter = BookFilter.IN_PROGRESS), null, 500).books.any { it.id == book.id })
        if (Capability.PER_DEVICE_POSITIONS in source.capabilities) assertTrue(read.devices.any { it.isThisDevice })

        val bookmark = source.addBookmark(book.id, 33_000, "live test")
        assertEquals(33_000L, bookmark.positionMs)
        assertEquals("live test", bookmark.label)
        val renamed = source.renameBookmark(bookmark, "renamed")
        assertEquals("renamed", renamed.label)
        assertTrue(source.bookmarks(book.id).any { it.positionMs == 33_000L })
        source.deleteBookmark(renamed)
        assertFalse(source.bookmarks(book.id).any { it.positionMs == 33_000L })

        if (Capability.FAVOURITES in source.capabilities) {
            source.setFavourite(book.id, true)
            assertTrue(book.id in source.favourites())
            source.setFavourite(book.id, false)
            assertFalse(book.id in source.favourites())
        }

        // A plan: one part per file, the first answers a range request.
        val plan = source.playPlan(book)
        assertEquals(files.size, plan.parts.size)
        assertEquals(files.map { it.startMs }, plan.parts.map { it.startMs })
        http.newCall(Request.Builder().url(plan.parts[0].url).header("Range", "bytes=0-99").header("User-Agent", userAgent).build()).execute().use {
            assertEquals(206, it.code)
            assertEquals(100, it.body.bytes().size)
        }
        assertEquals(50_000L, source.report(PlaybackReport(book.id, plan.sessionId, 50_000, 7_500, book.durationMs, false))?.positionMs)
        source.report(PlaybackReport(book.id, plan.sessionId, 51_000, 1_000, book.durationMs, false, closes = true))
        assertTrue(abs((source.progress(book.id)?.positionMs ?: 0) - 51_000) < 1000)
        assertEquals(files[0].id, source.partUrl(book, files[0].id).fileId)

        if (Capability.LOWER_BITRATE in source.capabilities) {
            val small = source.playPlan(book, maxBitrateKbps = 64)
            assertTrue(small.parts.isNotEmpty())
            if (small.parts[0].isHls) {
                delay(3000)
                http.newCall(Request.Builder().url(small.parts[0].url).header("User-Agent", userAgent).build()).execute().use {
                    assertEquals(200, it.code)
                    assertTrue(it.body.string().startsWith("#EXTM3U"))
                }
            }
            small.sessionId?.let { source.report(PlaybackReport(book.id, it, 51_000, 0, book.durationMs, false, closes = true)) }
        }

        source.clearProgress(book.id)
        assertNull(source.progress(book.id))
        source.signOut()
    }

    @Test
    fun `Audiobookshelf - sign in once, browse, progress, bookmarks, play, report`() = runBlocking {
        val host = env["AUDIOBOOKSHELF_HOST"]?.let { AudiobookshelfClient.urlFromTyped(it) ?: it.toHttpUrlOrNull() }
        val user = env["AUDIOBOOKSHELF_USERNAME"] ?: env["AUDIOBOOKSHELF_USER"]
        val password = env["AUDIOBOOKSHELF_PASSWORD"]
        assumeTrue("set AUDIOBOOKSHELF_HOST, _USERNAME and _PASSWORD", host != null && user != null && password != null)
        val absHttp = AudiobookshelfHttp(http, userAgent)
        val signIn = AudiobookshelfClient.signIn(host!!, user!!, password!!, absHttp)
        val client = AudiobookshelfClient(host, AbsTokenSession(signIn.tokens), absHttp)
        val library = client.bookLibraries().let { libs -> libs.firstOrNull { it.id == signIn.defaultLibraryId } ?: libs.first() }
        exercise(AudiobookshelfBookSource("live-abs", client, library.id, signIn.user.id, testDevice.copy(deviceId = "book-library-live-tests")))
    }

    private fun ownAudio(): Triple<String, String, String>? {
        val host = env["OWNAUDIO_HOST"] ?: return null
        val email = env["OWNAUDIO_EMAIL"] ?: return null
        val password = env["OWNAUDIO_PASSWORD"] ?: env["OWNAUDIO_ACCOUNT_PASSWORD"] ?: return null
        return Triple(host, email, password)
    }

    private suspend fun ownAudioSource(deviceName: String): OwnAudioBookSource {
        val (host, email, password) = ownAudio()!!
        val tokens = OwnAudioClient(host, http, userAgent).signIn(email, password, OwnAudioClient.Device(deviceName))
        return OwnAudioBookSource("live-own", OwnAudioClient(host, http, userAgent, TokenSession(tokens)), null, memoryCatalogue())
    }

    @Test
    fun `own audio - sync the catalogue, browse, progress, bookmarks, favourites, play, report`() = runBlocking {
        assumeTrue("set OWNAUDIO_HOST, OWNAUDIO_EMAIL and OWNAUDIO_PASSWORD", ownAudio() != null)
        val source = ownAudioSource("Live tests")
        val first = source.syncCatalogue()
        assertTrue(first.books > 0 && first.authors > 0)
        assertEquals(first.books, source.syncCatalogue().books)
        exercise(source)
    }

    @Test
    fun `own audio - a position saved by another device shows with its name, and the resume rule asks`() = runBlocking {
        assumeTrue(ownAudio() != null)
        val phone = ownAudioSource("Live test phone")
        val car = ownAudioSource("Live test car")
        val book = phone.books(BookQuery(sort = BookSort.DURATION), null, 1).books.first()
        val t0 = Instant.now()
        phone.saveProgress(book.id, 100_000, book.durationMs, false, t0)
        delay(1000)
        car.saveProgress(book.id, 400_000, book.durationMs, false, Instant.now())
        val seen = phone.progress(book.id)!!
        assertEquals(400_000L, seen.positionMs)
        assertTrue(seen.devices.any { !it.isThisDevice && it.name == "Live test car" && it.positionMs == 400_000L })
        val local = ListeningProgress(100_000, false, t0)
        assertEquals(ResumeChoice.Ask(100_000, 400_000, "Live test car"), ResumeChoice.decide(local, seen))
        phone.clearProgress(book.id)
        car.signOut()
        phone.signOut()
    }
}
