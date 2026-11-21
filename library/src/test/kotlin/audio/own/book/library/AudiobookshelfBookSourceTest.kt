package audio.own.book.library

import audio.own.kit.audiobookshelf.AudiobookshelfError
import audio.own.kit.audiobookshelf.Bookmark as AbsBookmark
import audio.own.kit.audiobookshelf.LibraryItem
import audio.own.kit.audiobookshelf.MediaProgress
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iOS app's Audiobookshelf mapping tests, and the source against a stub server. */
class AudiobookshelfBookSourceTest {
    private val source = absSource("http://books.example.com:13378/")
    private val reader = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun item(json: String): LibraryItem = reader.decodeFromString(LibraryItem.serializer(), json)

    private val listRow =
        """{"id":"li1","libraryId":"lib1","addedAt":1792000000000,"updatedAt":1,"isMissing":false,"isInvalid":false,"mediaType":"book","media":{"metadata":{"title":"Ender's Game","authorName":"Orson Scott Card, Someone Else","narratorName":"Stefan Rudnicki","seriesName":"Ender's Game Series #1, Enderverse #3.5","genres":["Sci-Fi"],"publishedYear":"1985","explicit":false,"abridged":false},"coverPath":"/c.jpg","tags":[],"duration":3780.85,"size":1}}"""

    @Test
    fun `a list row - names split from the strings, the series name parsed, the cover URL made`() {
        val book = source.book(item(listRow))
        assertEquals(listOf("Orson Scott Card", "Someone Else"), book.authors.map { it.name })
        assertEquals(listOf("Stefan Rudnicki"), book.narrators.map { it.id })
        assertEquals(listOf("Ender's Game Series", "Enderverse"), book.series.map { it.name })
        assertEquals(listOf("1", "3.5"), book.series.map { it.sequence })
        assertEquals(3.5, book.series[1].position!!, 0.0)
        assertEquals(3_780_850L, book.durationMs)
        assertEquals("http://books.example.com:13378/api/items/li1/cover?width=600&format=jpeg", book.coverUrl)
        assertNull(book.files)
        assertNull(book.chapters)
        assertEquals(Instant.ofEpochSecond(1_792_000_000), book.addedAt)
    }

    @Test
    fun `a detail - files from the tracks with their offsets, chapters in milliseconds, authors with ids, mojibake repaired`() {
        val detail = item(
            """{"id":"li1","libraryId":"lib1","addedAt":1,"updatedAt":1,"isMissing":false,"isInvalid":false,"mediaType":"book","media":{"metadata":{"title":"1984 - Ã¨Ã¡st 1","authors":[{"id":"a1","name":"George Orwell"}],"narrators":["N"],"series":[{"id":"s1","name":"Classics","sequence":"2"}],"genres":[],"explicit":false,"abridged":false},"coverPath":null,"tags":[],"duration":100.5,"chapters":[{"id":0,"start":0,"end":50.25,"title":"One"},{"id":1,"start":50.25,"end":100.5,"title":"Two"}],"tracks":[{"index":1,"startOffset":0,"duration":60,"title":"a.mp3","contentUrl":"/api/items/li1/file/11","mimeType":"audio/mpeg","ino":"11"},{"index":2,"startOffset":60,"duration":40.5,"title":"b.mp3","contentUrl":"/api/items/li1/file/12","mimeType":"audio/mpeg","ino":12}]}}""",
        )
        val book = source.book(detail)
        assertEquals("1984 - èást 1", book.title)
        assertEquals(listOf(Person("a1", "George Orwell")), book.authors)
        assertEquals("s1", book.series.first().id)
        assertEquals(2.0, book.series.first().position!!, 0.0)
        assertEquals(listOf("11", "12"), book.files!!.map { it.id })
        assertEquals(listOf(0L, 60_000L), book.files!!.map { it.startMs })
        assertEquals(40_500L, book.files!!.last().durationMs)
        assertEquals(listOf(50_250L, 100_500L), book.chapters!!.map { it.endMs })
        assertNull(book.coverUrl)
        assertEquals("12", book.fileAt(70_000)!!.first.id)
    }

    @Test
    fun `progress and bookmarks - seconds to milliseconds, the bookmark keyed by its time`() {
        val p = reader.decodeFromString(
            MediaProgress.serializer(),
            """{"id":"p1","userId":"u","libraryItemId":"li1","episodeId":null,"duration":100,"progress":0.5,"currentTime":50.25,"isFinished":false,"hideFromContinueListening":false,"lastUpdate":1792091696595,"startedAt":1,"finishedAt":null}""",
        )
        val progress = AudiobookshelfBookSource.progress(p)
        assertEquals(50_250L, progress.positionMs)
        assertFalse(progress.finished)
        assertEquals(Instant.ofEpochMilli(1_792_091_696_595), progress.updatedAt)
        val b = AudiobookshelfBookSource.bookmark(AbsBookmark("li1", 90.0, "", 1000))
        assertEquals("90", b.id)
        assertEquals(90_000L, b.positionMs)
        assertNull(b.label)
        assertEquals("90.5", AudiobookshelfBookSource.timeKey(90.5))
    }

    @Test
    fun `errors become the screens' errors`() {
        assertEquals(SourceException.SignInRejected, AudiobookshelfBookSource.sourceError(AudiobookshelfError.InvalidCredentials))
        assertEquals(SourceException.NotAllowed, AudiobookshelfBookSource.sourceError(AudiobookshelfError.Http(403, null)))
        assertEquals(SourceException.Server("boom"), AudiobookshelfBookSource.sourceError(AudiobookshelfError.Http(500, "boom")))
        assertEquals(SourceException.Offline, AudiobookshelfBookSource.sourceError(AudiobookshelfError.Offline))
        assertEquals(SourceException.Http(429), AudiobookshelfBookSource.sourceError(AudiobookshelfError.RateLimited(null)))
    }

    @Test
    fun `books come 60 to a page, filters and sorts through the kit, text through the server's search`() = runBlocking {
        Stub { request ->
            when (request.url.encodedPath) {
                "/api/libraries/lib1/items" -> {
                    val q = request.url
                    when (q.queryParameter("page")) {
                        "0" -> {
                            assertEquals("60", q.queryParameter("limit"))
                            json("""{"results":[$listRow],"total":61,"limit":60,"page":0}""")
                        }
                        else -> {
                            assertEquals("narrators." + b64("Stefan Rudnicki"), q.queryParameter("filter"))
                            assertEquals("media.duration", q.queryParameter("sort"))
                            assertEquals("1", q.queryParameter("desc"))
                            json("""{"results":[$listRow],"total":61,"limit":60,"page":1}""")
                        }
                    }
                }
                "/api/libraries/lib1/search" -> json("""{"book":[{"libraryItem":$listRow}],"authors":[],"series":[],"narrators":[]}""")
                else -> text("Not Found", 404)
            }
        }.use { stub ->
            val abs = absSource(stub.url)
            val first = abs.books()
            assertEquals("1", first.next)
            assertEquals(61, first.total)
            val second = abs.books(BookQuery(sort = BookSort.DURATION, descending = true, narratorId = "Stefan Rudnicki"), first.next)
            assertNull(second.next)
            assertEquals(listOf("li1"), abs.books(BookQuery(text = "ender")).books.map { it.id })
            assertEquals(emptyList<Book>(), abs.books(BookQuery(filter = BookFilter.FAVOURITES)).books)
            assertEquals(3, stub.requests.size)
        }
    }

    @Test
    fun `shelves - books, series and authors with the server's labels, other kinds left out`() = runBlocking {
        Stub {
            json(
                """[{"id":"continue-listening","label":"Continue Listening","type":"book","entities":[$listRow]},
                    {"id":"recent-series","label":"Recent Series","type":"series","entities":[{"id":"s1","name":"Classics","books":[$listRow]}]},
                    {"id":"newest-authors","label":"Newest Authors","type":"authors","entities":[{"id":"a1","name":"George Orwell","numBooks":2}]},
                    {"id":"newest-episodes","label":"Newest Episodes","type":"episode","entities":[]}]""",
            )
        }.use { stub ->
            val shelves = absSource(stub.url).shelves()
            assertEquals(listOf("continue-listening", "recent-series", "newest-authors"), shelves.map { it.id })
            assertEquals("Continue Listening", shelves[0].title)
            val series = (shelves[1].content as Shelf.Content.Series).series.single()
            assertEquals(1, series.bookCount)
            assertEquals("li1", series.books!!.single().id)
            assertEquals(2, (shelves[2].content as Shelf.Content.Authors).authors.single().bookCount)
        }
    }

    @Test
    fun `one play session per book - a report naming an older one goes in as a plain position`() = runBlocking {
        var sessions = 0
        Stub { request ->
            when (request.method to request.url.encodedPath) {
                "POST" to "/api/items/li1/play" -> {
                    sessions++
                    assertEquals(sessions == 2, request.bodyJson()["forceTranscode"]!!.jsonPrimitive.boolean)
                    val hls = if (sessions == 2) 2 else 0
                    val track = if (sessions == 2) "/hls/s2/output.m3u8" else "/api/items/li1/file/11"
                    json("""{"id":"s$sessions","libraryItemId":"li1","playMethod":$hls,"duration":100,"currentTime":61.5,"audioTracks":[{"index":1,"startOffset":0,"duration":100,"contentUrl":"$track","mimeType":"audio/mpeg","ino":"11"}]}""")
                }
                "POST" to "/api/session/s2/sync" -> {
                    assertEquals(70.0, request.bodyJson()["currentTime"]!!.jsonPrimitive.double, 0.0)
                    assertEquals(8.5, request.bodyJson()["timeListened"]!!.jsonPrimitive.double, 0.0)
                    text("OK")
                }
                "POST" to "/api/session/s2/close" -> text("OK")
                "PATCH" to "/api/me/progress/li1" -> {
                    assertEquals(42.5, request.bodyJson()["currentTime"]!!.jsonPrimitive.double, 0.0)
                    text("OK")
                }
                else -> text("Not Found", 404)
            }
        }.use { stub ->
            val abs = absSource(stub.url)
            val book = abs.book(item(listRow))
            val first = abs.playPlan(book)
            assertEquals(61_500L, first.startPositionMs)
            assertFalse(first.parts.single().isHls)
            assertTrue(first.parts.single().url.endsWith("/api/items/li1/file/11?token=t"))
            val second = abs.playPlan(book, maxBitrateKbps = 64)
            assertTrue(second.parts.single().isHls)
            assertFalse(second.parts.single().streamsByRange)

            abs.report(PlaybackReport("li1", "s1", 42_500, 1000, 100_000, finished = false))
            abs.report(PlaybackReport("li1", "s2", 70_000, 8_500, 100_000, finished = false))
            abs.report(PlaybackReport("li1", "s2", 70_000, 0, 100_000, finished = false, closes = true))
            // Closed: the next report is a plain position.
            abs.report(PlaybackReport("li1", "s2", 42_500, 0, 100_000, finished = false))
            assertEquals(
                listOf(
                    "POST /api/items/li1/play", "POST /api/items/li1/play", "PATCH /api/me/progress/li1",
                    "POST /api/session/s2/sync", "POST /api/session/s2/close", "PATCH /api/me/progress/li1",
                ),
                stub.paths(),
            )
        }
    }

    @Test
    fun `positions in seconds, bookmarks by their time`() = runBlocking {
        Stub { request ->
            when (request.method to request.url.encodedPath) {
                "PATCH" to "/api/me/progress/li1" -> {
                    val b = request.bodyJson()
                    assertEquals(42.5, b["currentTime"]!!.jsonPrimitive.double, 0.0)
                    assertEquals(100.0, b["duration"]!!.jsonPrimitive.double, 0.0)
                    assertTrue(b["isFinished"]!!.jsonPrimitive.boolean)
                    text("OK")
                }
                "POST" to "/api/me/item/li1/bookmark" -> {
                    assertEquals(33.0, request.bodyJson()["time"]!!.jsonPrimitive.double, 0.0)
                    json("""{"libraryItemId":"li1","time":33,"title":"here","createdAt":1}""")
                }
                "DELETE" to "/api/me/item/li1/bookmark/33" -> text("OK")
                else -> text("Not Found", 404)
            }
        }.use { stub ->
            val abs = absSource(stub.url)
            val saved = abs.saveProgress("li1", 42_500, 100_000, finished = true, at = Instant.EPOCH)
            assertEquals(42_500L, saved.positionMs)
            val bookmark = abs.addBookmark("li1", 33_000, "here")
            assertEquals("33", bookmark.id)
            assertEquals("here", bookmark.label)
            abs.deleteBookmark(bookmark)
            try {
                abs.setFavourite("li1", true)
            } catch (e: SourceException) {
                assertEquals(SourceException.Unsupported, e)
            }
            assertEquals(emptySet<String>(), abs.favourites())
        }
    }

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
}
