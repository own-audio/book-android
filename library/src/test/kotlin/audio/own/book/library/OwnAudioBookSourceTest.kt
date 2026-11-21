package audio.own.book.library

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.RecordedRequest
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OwnAudioBookSourceTest {
    private val threeFiles = Triple(
        "book", "b1",
        """{"id":"b1","title":"Three","credits":[],"duration_ms":6000,"visibility":"family","owner_id":"u1","created_at":"2026-10-14T18:06:13Z","updated_at":"2026-10-14T18:06:15Z","files":[{"id":"f1","position":0,"duration_ms":1000,"content_type":"audio/mpeg"},{"id":"f2","position":1,"duration_ms":2000,"content_type":"audio/mpeg"},{"id":"f3","position":2,"duration_ms":3000,"content_type":"audio/flac"}],"chapters":[],"series":[]}""",
    )
    private val progressRecord = Triple("progress.book", "b1", """{"kind":"book","id":"b1","position_ms":1500,"finished":false,"updated_at":"2026-10-15T10:00:00Z"}""")

    private fun feed(request: RecordedRequest): MockResponse? = when (request.url.encodedPath) {
        "/api/v2/sync/snapshot" -> ndjson("s1", listOf(threeFiles, progressRecord), "s1")
        "/api/v2/sync/changes" -> json("""{"changes":[],"cursor":"s1","has_more":false,"reset":false}""")
        else -> null
    }

    @Test
    fun `a plan asks for every file's link at once, in book order`() = runBlocking {
        val arrived = CountDownLatch(3)
        val together = AtomicBoolean(true)
        Stub { request ->
            feed(request) ?: when (request.url.encodedPath) {
                "/api/v2/playback-info" -> {
                    val body = request.bodyJson()
                    assertEquals("book_file", body["kind"]!!.jsonPrimitive.content)
                    val direct = body["capabilities"]!!.jsonObject["direct"]!!.jsonArray.map { it.jsonPrimitive.content }
                    assertTrue("audio/mpeg" in direct && "audio/ogg" in direct)
                    arrived.countDown()
                    // Asked one after another, the first would wait here alone.
                    if (!arrived.await(5, TimeUnit.SECONDS)) together.set(false)
                    val id = body["id"]!!.jsonPrimitive.content
                    val decision = if (id == "f3") "transcode" else "direct"
                    val expires = if (id == "f2") "2026-11-21T10:00:00Z" else "2026-11-21T12:00:00Z"
                    json("""{"decision":"$decision","url":"https://cdn.example.com/$id?sig=x","expires_at":"$expires","content_type":${if (id == "f3") "\"audio/aac\"" else "null"}}""")
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub), "u1", memoryCatalogue())
            val book = source.book("b1")
            val plan = source.playPlan(book)
            assertTrue("the links were asked for together", together.get())
            assertEquals(listOf("f1", "f2", "f3"), plan.parts.map { it.fileId })
            assertEquals(listOf(0L, 1000L, 3000L), plan.parts.map { it.startMs })
            assertEquals(listOf("audio/mpeg", "audio/mpeg", "audio/aac"), plan.parts.map { it.contentType })
            assertEquals(listOf(true, true, false), plan.parts.map { it.streamsByRange })
            assertEquals(Instant.parse("2026-11-21T10:00:00Z"), plan.expiresAt)
            assertNull(plan.sessionId)
            assertEquals(6000L, plan.spokenParts().sumOf { it.durationMs })
        }
    }

    @Test
    fun `a renewed link names the offset in its file`() = runBlocking {
        Stub { request ->
            feed(request) ?: when (request.url.encodedPath) {
                "/api/v2/playback-info" -> {
                    assertEquals(500L, request.bodyJson()["start_ms"]!!.jsonPrimitive.long)
                    json("""{"decision":"transcode","url":"https://cdn.example.com/f2?sig=y","expires_at":"2026-11-21T12:00:00Z"}""")
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub), "u1", memoryCatalogue())
            val part = source.partUrl(source.book("b1"), "f2", startMs = 1500, maxBitrateKbps = 64)
            assertEquals("https://cdn.example.com/f2?sig=y", part.url)
            assertEquals(1000L, part.startMs)
        }
    }

    @Test
    fun `a book the catalogue lacks comes from its detail, with its contributors`() = runBlocking {
        Stub { request ->
            feed(request) ?: when (request.url.encodedPath) {
                "/api/v2/books/b9" -> {
                    assertEquals("contributors", request.url.queryParameter("include"))
                    json(
                        """{"id":"b9","title":"New","credits":[{"contributor_id":"a9","role":"author","position":0}],"duration_ms":null,"visibility":"private","owner_id":"u1","created_at":"2026-10-14T18:06:13Z","updated_at":"2026-10-14T18:06:15Z","files":[{"id":"x1","position":0,"duration_ms":4000}],"chapters":[{"id":"c","position":0,"title":"One","start_ms":0}],"series":[],"included":{"contributors":[{"id":"a9","name":"Someone"}]}}""",
                    )
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub), "u1", memoryCatalogue())
            val book = source.book("b9")
            assertEquals(listOf("Someone"), book.authors.map { it.name })
            assertEquals(4000L, book.durationMs)
            assertEquals(listOf(Chapter("One", 0, 4000)), book.chapters)
            assertEquals(Visibility.PRIVATE, book.visibility)
        }
    }

    @Test
    fun `a position comes with every device, offline it is the kept one`() = runBlocking {
        var offline = false
        Stub { request ->
            feed(request) ?: when (request.method to request.url.encodedPath) {
                "GET" to "/api/v2/me/progress/book/b1" -> json(
                    """{"kind":"book","id":"b1","position_ms":400000,"finished":false,"updated_at":"2026-10-16T10:00:00Z","devices":[{"device_id":"d1","device_name":"Car","device_kind":"android","position_ms":400000,"at":"2026-10-16T10:00:00Z"},{"device_id":"d2","device_name":"Pixel","device_kind":"android","position_ms":1500,"at":"2026-10-15T10:00:00Z","current":true}]}""",
                )
                "PUT" to "/api/v2/me/progress/book/b1" -> {
                    assertEquals(42_500L, request.bodyJson()["position_ms"]!!.jsonPrimitive.long)
                    // Another device is newer: the server keeps its position.
                    json("""{"progress":{"kind":"book","id":"b1","position_ms":400000,"finished":false,"updated_at":"2026-10-16T10:00:00Z"},"applied":false}""")
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub, isOffline = { offline }), "u1", memoryCatalogue())
            source.syncCatalogue()
            val online = source.progress("b1")!!
            assertEquals(400_000L, online.positionMs)
            assertEquals(listOf("Car", "Pixel"), online.devices.map { it.name })
            assertEquals(listOf(false, true), online.devices.map { it.isThisDevice })
            assertEquals(ResumeChoice.Ask(1500, 400_000, "Car"), ResumeChoice.decide(source.allProgress()["b1"], online))

            val saved = source.saveProgress("b1", 42_500, 6000, finished = false, at = Instant.parse("2026-10-15T11:00:00Z"))
            assertEquals(400_000L, saved.positionMs)
            assertFalse("the server kept another device's newer position", saved.applied)
            assertEquals(400_000L, source.allProgress()["b1"]!!.positionMs)

            offline = true
            val kept = source.progress("b1")!!
            assertEquals(400_000L, kept.positionMs)
            assertTrue(kept.devices.isEmpty())
            assertEquals(listOf("b1"), source.books().books.map { it.id })
        }
    }

    @Test
    fun `collections come from the server with their books and are kept for offline`() = runBlocking {
        var offline = false
        Stub { request ->
            feed(request) ?: when (request.url.encodedPath) {
                "/api/v2/collections" -> json(
                    """{"items":[{"id":"k1","name":"Bedtime","visibility":"family","owner_id":"u1","book_count":2,"created_at":"2026-10-14T18:06:13Z","updated_at":"2026-10-14T18:06:13Z"}]}""",
                )
                "/api/v2/collections/k1/books" -> json(
                    """{"items":[{"position":1,"added_at":"2026-10-14T18:06:13Z","book":{"id":"b5","title":"Not synced yet","duration_ms":1000,"created_at":"2026-10-14T18:06:13Z","updated_at":"2026-10-14T18:06:13Z"}},{"position":0,"added_at":"2026-10-14T18:06:13Z","book":{"id":"b1","title":"Three","created_at":"2026-10-14T18:06:13Z","updated_at":"2026-10-14T18:06:13Z"}}]}""",
                )
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub, isOffline = { offline }), "u1", memoryCatalogue())
            val online = source.collections()
            assertEquals(listOf("Bedtime"), online.map { it.name })
            assertEquals(listOf("b1", "b5"), online.first().books.map { it.id })
            assertEquals(6000L, online.first().books.first().durationMs)
            offline = true
            // Offline only the books the catalogue has are listed.
            assertEquals(listOf("b1"), source.collections().first().books.map { it.id })
            assertTrue(Capability.COLLECTIONS in source.capabilities)
            assertFalse(Capability.SERVER_SEARCH in source.capabilities)
        }
    }

    @Test
    fun `positions from offline go in one push, each with when it was reached`() = runBlocking {
        Stub { request ->
            feed(request) ?: when (request.method to request.url.encodedPath) {
                "POST" to "/api/v2/sync/push" -> {
                    val changes = request.bodyJson()["changes"]!!.jsonArray.map { it.jsonObject }
                    assertEquals(listOf("progress", "progress", "progress"), changes.map { it["op"]!!.jsonPrimitive.content })
                    assertEquals(listOf(1_000L, 2_000L, 3_000L), changes.map { it["position_ms"]!!.jsonPrimitive.long })
                    assertEquals("2026-11-20T10:00:00Z", changes.first()["at"]!!.jsonPrimitive.content)
                    val ids = changes.map { it["client_id"]!!.jsonPrimitive.content }
                    json("""[{"client_id":"${ids[2]}","status":"bad_item"},{"client_id":"${ids[0]}","status":"ok"},{"client_id":"${ids[1]}","status":"stale"}]""")
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub), "u1", memoryCatalogue())
            val at = Instant.parse("2026-11-20T10:00:00Z")
            val reports = listOf("b1", "b2", "b3").mapIndexed { i, id ->
                PlaybackReport(id, null, (i + 1) * 1_000L, 0, 6000, finished = false, at = at.plusSeconds(i.toLong()))
            }
            assertEquals(listOf(BatchResult.SENT, BatchResult.STALE, BatchResult.REFUSED), source.reportAll(reports))
        }
    }

    @Test
    fun `the owner shares a book with the family and keeps it again`() = runBlocking {
        val sent = mutableListOf<String>()
        Stub { request ->
            feed(request) ?: when (request.method to request.url.encodedPath) {
                "PUT" to "/api/v2/books/b1/visibility" -> {
                    val value = request.bodyJson()["visibility"]!!.jsonPrimitive.content
                    sent += value
                    json("""{"visibility":"$value"}""")
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub), "u1", memoryCatalogue())
            val book = source.book("b1")
            assertTrue(source.canShare(book))
            assertFalse(source.canShare(book.copy(ownerId = "someone-else")))
            source.setShared("b1", true)
            source.setShared("b1", false)
            assertEquals(listOf("family", "private"), sent)
        }
    }
}
