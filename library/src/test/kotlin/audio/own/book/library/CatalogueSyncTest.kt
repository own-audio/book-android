package audio.own.book.library

import audio.own.book.library.catalogue.OwnAudioCatalogue
import audio.own.kit.ownaudio.OwnAudioException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The catalogue's change feed into Room (docs/android-foundation.md §9.1). */
@RunWith(RobolectricTestRunner::class)
class CatalogueSyncTest {
    private fun contributor(id: String) = Triple("contributor", id, """{"id":"$id","name":"Person $id","kind":"person"}""")

    private fun book(id: String, title: String = id) = Triple(
        "book", id,
        """{"id":"$id","title":"$title","credits":[{"contributor_id":"a1","role":"author","position":0}],"duration_ms":1000,"visibility":"family","owner_id":"u1","created_at":"2026-10-14T18:06:13Z","updated_at":"2026-10-14T18:06:15Z","files":[{"id":"f-$id","position":0,"duration_ms":1000}],"chapters":[],"series":[{"series_id":"s1","name":"S","position":1}]}""",
    )

    private val noChanges = """{"changes":[],"cursor":"c1","has_more":false,"reset":false}"""

    @Test
    fun `a snapshot cut short keeps what came, resumes with after and the cursor, then reads the changes`() = runBlocking {
        val people = (0 until 1200).map { contributor("p%04d".format(it)) }
        val snapshots = AtomicInteger()
        Stub { request ->
            when (request.url.encodedPath) {
                "/api/v2/sync/snapshot" -> when (snapshots.incrementAndGet()) {
                    // 700 records, then the connection ends with no `end` line.
                    1 -> ndjson("c0", people.take(700), end = null)
                    // The resume within the same sync fails outright: the sync gives up for now.
                    2 -> problem("unavailable", 503)
                    else -> {
                        assertEquals("contributor:p0699", request.url.queryParameter("after"))
                        assertEquals("c0", request.url.queryParameter("cursor"))
                        ndjson("c0", people.drop(700) + book("b1"), end = "c0")
                    }
                }
                "/api/v2/sync/changes" -> {
                    assertEquals("c0", request.url.queryParameter("cursor"))
                    assertEquals("contributor,book,series,collection,favourite,progress,bookmark", request.url.queryParameter("kinds"))
                    json(noChanges)
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val catalogue = memoryCatalogue()
            val dao = catalogue.database.dao()
            val client = ownAudioClient(stub)
            try {
                catalogue.sync(client)
                fail("the second attempt was refused")
            } catch (_: OwnAudioException.Problem) {
            }
            assertEquals(700, dao.contributors().size)
            val halfway = dao.feedState()!!
            assertNull(halfway.cursor)
            assertEquals("c0", halfway.snapshotCursor)
            assertEquals("contributor:p0699", halfway.snapshotAfter)

            catalogue.sync(client)
            assertEquals(1200, dao.contributors().size)
            assertEquals(1, dao.books().size)
            val state = dao.feedState()!!
            assertEquals("c1", state.cursor)
            assertNull(state.snapshotCursor)
            assertNotNull(state.syncedAt)
            assertEquals(3, snapshots.get())
        }
    }

    @Test
    fun `changes apply upserts and deletes with their cursor, a reset takes a new snapshot and drops what it didn't send`() = runBlocking {
        val changes = AtomicInteger()
        val snapshots = AtomicInteger()
        Stub { request ->
            when (request.url.encodedPath) {
                "/api/v2/sync/snapshot" -> if (snapshots.incrementAndGet() == 1) {
                    ndjson("s1", listOf(contributor("a1"), book("b1"), book("b2")), "s1")
                } else {
                    ndjson("s2", listOf(contributor("a2"), book("b1", "Alice again")), "s2")
                }
                "/api/v2/sync/changes" -> when (request.url.queryParameter("cursor")) {
                    "s1" -> json(
                        """{"changes":[{"kind":"book","id":"b2","op":"delete"},{"kind":"progress.book","id":"b1","op":"upsert","record":{"kind":"book","id":"b1","position_ms":4000,"finished":false,"updated_at":"2026-10-15T10:00:00Z"}}],"cursor":"c2","has_more":true,"reset":false}""",
                    )
                    "c2" -> json(
                        """{"changes":[{"kind":"bookmark","id":"bm1","op":"upsert","record":{"id":"bm1","kind":"book","item_id":"b1","position_ms":500,"created_at":"2026-10-15T10:00:00Z"}},{"kind":"queue","id":"q","op":"upsert","record":{}}],"cursor":"c3","has_more":false,"reset":false}""",
                    )
                    "c3" -> { changes.incrementAndGet(); json("""{"changes":[],"cursor":"c4","has_more":false,"reset":true}""") }
                    "s2" -> json("""{"changes":[],"cursor":"s2b","has_more":false,"reset":false}""")
                    else -> problem("invalid_cursor", 400)
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val catalogue = memoryCatalogue()
            val dao = catalogue.database.dao()
            val client = ownAudioClient(stub)
            catalogue.sync(client)
            assertEquals(listOf("b1"), dao.books().map { it.id })
            assertEquals(emptyList<Any>(), dao.filesOf("b2"))
            assertEquals(4000L, dao.progress("b1")?.positionMs)
            assertEquals(listOf("bm1"), dao.bookmarks().map { it.id })
            assertEquals("c3", dao.feedState()!!.cursor)

            catalogue.sync(client)
            assertEquals(1, changes.get())
            assertEquals(2, snapshots.get())
            assertEquals(listOf("Alice again"), dao.books().map { it.title })
            assertEquals(listOf("a2"), dao.contributors().map { it.id })
            // Not in the new snapshot, so gone.
            assertNull(dao.progress("b1"))
            assertTrue(dao.bookmarks().isEmpty())
            assertEquals(listOf("f-b1"), dao.filesOf("b1").map { it.id })
            assertEquals(1, dao.seriesBooks().size)
            assertEquals("s2b", dao.feedState()!!.cursor)
        }
    }

    @Test
    fun `an expired cursor (410) takes a fresh snapshot`() = runBlocking {
        val snapshots = AtomicInteger()
        val changeCalls = AtomicInteger()
        Stub { request ->
            when (request.url.encodedPath) {
                "/api/v2/sync/snapshot" -> {
                    snapshots.incrementAndGet()
                    ndjson("s1", listOf(book("b1")), "s1")
                }
                "/api/v2/sync/changes" -> if (changeCalls.incrementAndGet() == 2) {
                    problem("sync_cursor_expired", 410)
                } else {
                    json("""{"changes":[],"cursor":"s1","has_more":false,"reset":false}""")
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val catalogue = memoryCatalogue()
            catalogue.sync(ownAudioClient(stub))
            catalogue.sync(ownAudioClient(stub))
            assertEquals(2, snapshots.get())
            assertEquals(3, changeCalls.get())
            assertEquals(listOf("b1"), catalogue.database.dao().books().map { it.id })
        }
    }

    @Test
    fun `a page of changes that fails leaves Room as it was`() = runBlocking {
        val failing = AtomicInteger()
        Stub { request ->
            when (request.url.encodedPath) {
                "/api/v2/sync/snapshot" -> ndjson("s1", listOf(contributor("a1"), book("b1")), "s1")
                "/api/v2/sync/changes" -> if (failing.getAndIncrement() == 0) {
                    json(noChanges.replace("c1", "s1"))
                } else {
                    // The first change decodes, the answer as a whole doesn't: nothing may land.
                    json("""{"changes":[{"kind":"book","id":"b1","op":"delete"}],"cursor":""")
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val catalogue: OwnAudioCatalogue = memoryCatalogue()
            val dao = catalogue.database.dao()
            catalogue.sync(ownAudioClient(stub))
            try {
                catalogue.sync(ownAudioClient(stub))
                fail("the answer was unreadable")
            } catch (_: OwnAudioException.Decoding) {
            }
            assertEquals(listOf("b1"), dao.books().map { it.id })
            assertEquals("s1", dao.feedState()!!.cursor)
        }
    }

    @Test
    fun `a source never synced syncs before it answers, and a reset empties the catalogue`() = runBlocking {
        Stub { request ->
            when (request.url.encodedPath) {
                "/api/v2/sync/snapshot" -> ndjson("s1", listOf(contributor("a1"), book("b1")), "s1")
                "/api/v2/sync/changes" -> json(noChanges)
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub), "u1", memoryCatalogue())
            assertNull(source.catalogueSummary())
            assertEquals(listOf("b1"), source.books().books.map { it.id })
            assertEquals(1, source.catalogueSummary()!!.books)
            source.resetCatalogue()
            assertNull(source.catalogueSummary())
            assertTrue(source.catalogue.database.dao().books().isEmpty())
        }
    }
}
