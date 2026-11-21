package audio.own.book.library

import audio.own.kit.ownaudio.OwnAudioException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The iOS app's own.audio mapping tests, against a catalogue filled by a snapshot. */
@RunWith(RobolectricTestRunner::class)
class OwnAudioMappingTest {
    private val records = listOf(
        Triple("contributor", "a1", """{"id":"a1","name":"Lewis Carroll","kind":"person","image":"https://s.example.com/api/v2/images/i1?e=1&s=sig"}"""),
        Triple("contributor", "n1", """{"id":"n1","name":"LibriVox volunteers","kind":"group"}"""),
        Triple("contributor", "m1", """{"id":"m1","name":"A Musician","kind":"person"}"""),
        Triple(
            "book", "b1",
            """{"id":"b1","title":"Alice","credits":[{"contributor_id":"a1","role":"author","position":0,"join_phrase":""},{"contributor_id":"n1","role":"narrator","position":0,"join_phrase":""}],"duration_ms":3000,"visibility":"family","owner_id":"u1","image":"https://s.example.com/api/v2/images/i2?e=1&s=sig","created_at":"2026-10-14T18:06:13.792347Z","updated_at":"2026-10-14T18:06:15Z","files":[{"id":"f2","position":2,"title":"ch 02","duration_ms":2000,"size_bytes":2,"content_type":"audio/mpeg"},{"id":"f1","position":1,"title":"ch 01","duration_ms":1000,"size_bytes":1,"content_type":"audio/mpeg"}],"chapters":[{"id":"c2","position":1,"title":null,"start_ms":1500},{"id":"c1","position":0,"title":"Down the Rabbit-Hole","start_ms":0}],"series":[{"series_id":"s1","name":"Alice books","position":1.5}]}""",
        ),
        Triple(
            "book", "b2",
            """{"id":"b2","title":"Looking-Glass","credits":[{"contributor_id":"a1","role":"author","position":0,"join_phrase":""}],"duration_ms":1000,"visibility":"private","owner_id":"u1","created_at":"2026-10-15T18:06:13Z","updated_at":"2026-10-15T18:06:15Z","files":[],"chapters":[],"series":[{"series_id":"s1","name":"Alice books","position":1}]}""",
        ),
        Triple("progress.book", "b1", """{"kind":"book","id":"b1","position_ms":1200,"finished":false,"updated_at":"2026-10-15T10:00:00Z"}"""),
        Triple("progress.episode", "e1", """{"kind":"episode","id":"e1","position_ms":5,"finished":false,"updated_at":"2026-10-15T10:00:00Z"}"""),
        Triple("bookmark", "bm1", """{"id":"bm1","kind":"book","item_id":"b1","position_ms":500,"label":"here","created_at":"2026-10-15T10:00:00Z"}"""),
        Triple("favourite.book", "b2", """{"kind":"book","id":"b2","created_at":"2026-10-15T10:00:00Z"}"""),
        Triple("favourite.track", "t1", """{"kind":"track","id":"t1","created_at":"2026-10-15T10:00:00Z"}"""),
    )

    private fun <T> withSource(block: suspend (OwnAudioBookSource) -> T): T = Stub { request ->
        when (request.url.encodedPath) {
            "/api/v2/sync/snapshot" -> ndjson("c0", records, "c1")
            "/api/v2/sync/changes" -> json("""{"changes":[],"cursor":"c1","has_more":false,"reset":false}""")
            else -> problem("not_found", 404)
        }
    }.use { stub ->
        runBlocking { block(OwnAudioBookSource("server-1", ownAudioClient(stub), "u1", memoryCatalogue(), java.util.Locale.ENGLISH)) }
    }

    @Test
    fun `a snapshot becomes the library - books with files in order, chapters with ends, people by role, the listener's state`() = withSource { source ->
        val alice = source.book("b1")
        assertEquals(listOf(Person("a1", "Lewis Carroll", imageUrl = "https://s.example.com/api/v2/images/i1?e=1&s=sig&w=256")), alice.authors)
        assertEquals(listOf("LibriVox volunteers"), alice.narrators.map { it.name })
        assertEquals(listOf("f1", "f2"), alice.files!!.map { it.id })
        assertEquals(listOf(0L, 1000L), alice.files!!.map { it.startMs })
        assertEquals(listOf(Chapter("Down the Rabbit-Hole", 0, 1500), Chapter("Chapter 2", 1500, 3000)), alice.chapters)
        assertEquals("1.5", alice.series.first().sequence)
        assertEquals(Visibility.FAMILY, alice.visibility)
        assertTrue(alice.coverUrl!!.contains("w=1024"))
        assertEquals(listOf("Lewis Carroll"), source.authors().map { it.name })
        assertEquals(2, source.authors().first().bookCount)
        assertEquals(listOf("n1"), source.narrators().map { it.id })
        assertEquals(1200L, source.allProgress()["b1"]?.positionMs)
        assertEquals(setOf("b1"), source.allProgress().keys)
        assertEquals(listOf("here"), source.synced().bookmarks.map { it.label })
        assertEquals(setOf("b2"), source.synced().favourites)
        val series = source.series()
        assertEquals(listOf("Alice books"), series.map { it.name })
        assertEquals(listOf("b2", "b1"), series.first().books!!.map { it.id })
        // An empty list of files is "none", not "not loaded".
        assertEquals(emptyList<BookFile>(), source.book("b2").files)
    }

    @Test
    fun `browsing the copy - sort, filter, series order, text, paging, shelves, search`() = withSource { source ->
        suspend fun ids(query: BookQuery) = source.books(query).books.map { it.id }
        assertEquals(listOf("b1", "b2"), ids(BookQuery(sort = BookSort.TITLE)))
        assertEquals(listOf("b2", "b1"), ids(BookQuery(sort = BookSort.ADDED, descending = true)))
        assertEquals(listOf("b1"), ids(BookQuery(filter = BookFilter.IN_PROGRESS)))
        assertEquals(listOf("b2"), ids(BookQuery(filter = BookFilter.NOT_STARTED)))
        assertEquals(listOf("b2"), ids(BookQuery(filter = BookFilter.FAVOURITES)))
        assertEquals(emptyList<String>(), ids(BookQuery(filter = BookFilter.FINISHED)))
        assertEquals(listOf("b2", "b1"), ids(BookQuery(seriesId = "s1")))
        assertEquals(listOf("b2"), ids(BookQuery(text = "GLASS")))
        assertEquals(listOf("b1"), ids(BookQuery(narratorId = "n1")))
        assertEquals(listOf("b1", "b2"), ids(BookQuery(authorId = "a1")))
        val first = source.books(BookQuery(), null, 1)
        assertEquals(listOf("b1"), first.books.map { it.id })
        assertEquals("1", first.next)
        assertEquals(2, first.total)
        val second = source.books(BookQuery(), first.next, 1)
        assertEquals(listOf("b2"), second.books.map { it.id })
        assertEquals(null, second.next)
        assertEquals(listOf("continue-listening", "recently-added"), source.shelves().map { it.id })
        assertEquals(listOf("b2", "b1"), (source.shelves()[1].content as Shelf.Content.Books).books.map { it.id })
        val found = source.search("carroll")
        assertEquals(2, found.books.size)
        assertEquals(1, found.authors.size)
        assertEquals(2, source.series().first().bookCount)
        assertEquals(CatalogueSummary(2, 1, source.catalogueSummary()!!.syncedAt), source.catalogueSummary())
    }

    @Test
    fun `errors become the screens' errors`() {
        assertEquals(SourceException.SignInRejected, OwnAudioBookSource.sourceError(OwnAudioException.SignedOut))
        assertEquals(SourceException.SignInRejected, OwnAudioBookSource.sourceError(OwnAudioException.SignInRefused))
        assertEquals(SourceException.Network, OwnAudioBookSource.sourceError(OwnAudioException.Network()))
        assertEquals(SourceException.NotAllowed, OwnAudioBookSource.sourceError(OwnAudioException.Problem(403, "forbidden")))
        assertEquals(SourceException.Server("boom"), OwnAudioBookSource.sourceError(OwnAudioException.Problem(500, "internal", detail = "boom")))
        assertEquals(SourceException.Unsupported, OwnAudioBookSource.sourceError(OwnAudioException.Problem(501, "feature_unavailable")))
    }
}
