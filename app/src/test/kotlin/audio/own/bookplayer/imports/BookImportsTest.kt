package audio.own.bookplayer.imports

import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import audio.own.book.library.OwnAudioBookSource
import audio.own.book.library.Visibility
import audio.own.book.library.catalogue.BookCatalogueDatabase
import audio.own.book.library.catalogue.OwnAudioCatalogue
import audio.own.bookplayer.R
import audio.own.bookplayer.app.AppLog
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.Tokens
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The import's work against a pretend own.audio server and storage: a link per file with the exact
 * size, the bytes with exactly the link's headers, the book with the import's key; a kill
 * mid-upload goes on with the same links and key; refusals become the row's sentences.
 */
@RunWith(RobolectricTestRunner::class)
class BookImportsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dir = File(context.filesDir, "imports-${System.nanoTime()}")
    private val server = MockWebServer()
    private val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()

    /** Answers for this test; null falls through to the default server. */
    private var override: (RecordedRequest) -> MockResponse? = { null }
    private var uploads = 0

    private val documents = mapOf(
        "content://docs/1" to "first file".encodeToByteArray(),
        "content://docs/2" to "the second, longer file".encodeToByteArray(),
        "content://docs/3" to "third".encodeToByteArray(),
    )
    private val released = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val access = object : ImportFileAccess {
        override fun open(uri: String): InputStream? =
            documents[uri]?.let(::ByteArrayInputStream) ?: if (uri.startsWith("file:")) File(java.net.URI(uri)).inputStream() else null

        override fun release(grant: String) {
            released += grant
        }
    }
    private val scheduler = object : ImportScheduler {
        val queued = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
        override fun enqueue(importId: String, replace: Boolean) {
            queued += importId to replace
        }
        override fun cancel(importId: String) {
            queued.removeAll { it.first == importId }
        }
    }
    private val made = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val refused = java.util.concurrent.CopyOnWriteArrayList<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return override(request) ?: answer(request)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun answer(request: RecordedRequest): MockResponse = when ("${request.method} ${request.url.encodedPath}") {
        "POST /api/v2/uploads" -> {
            val n = synchronized(this) { ++uploads }
            json(
                """{"upload_id":"up$n","url":"${server.url("/storage/$n")}?X-Amz-Signature=s$n","method":"PUT",""" +
                    """"headers":{"Content-Type":"${Json.parseToJsonElement(request.body!!.utf8()).jsonObject["content_type"]!!.jsonPrimitive.content}","x-amz-meta-upload":"up$n"},"expires_at":"2099-01-01T00:00:00Z"}""",
            )
        }
        "POST /api/v2/books" -> json(BOOK, 201)
        else -> if (request.url.encodedPath.startsWith("/storage/")) MockResponse.Builder().code(200).build() else json("""{"code":"not_found","status":404}""", 404)
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").body(body).build()

    private fun problem(code: String, status: Int) = json("""{"type":"urn:own-audio:problem:$code","title":"x","status":$status,"code":"$code"}""", status)

    private val source = OwnAudioBookSource(
        "s1",
        OwnAudioClient(server.url("/").toString(), OkHttpClient(), "own.audio Books/0.0 (Android test)", TokenSession(Tokens("token", "r", Instant.now().plusSeconds(3600), ""))),
        "u1",
        OwnAudioCatalogue(Room.inMemoryDatabaseBuilder(context, BookCatalogueDatabase::class.java).allowMainThreadQueries().build()),
    )

    /** A fresh manager over the same folder: what a restarted app has. */
    private fun imports() = BookImports(dir, { if (it == "s1") source else null }, access, scheduler, AppLog(null, echo = false), onImported = { _, book -> made += book.id }, onRefused = { refused += it })

    private fun picked(n: Int, title: String? = null) = PickedFile("content://docs/$n", "$n - Part.mp3", title, null, null, 1_000L * n, documents["content://docs/$n"]!!.size.toLong(), "audio/mpeg", grant = "content://tree/folder")

    private fun draft(files: List<PickedFile>, cover: ByteArray? = null) =
        ImportDraft("s1", "Emma", listOf("Jane Austen"), listOf("Kim Bell", "Ann Lee"), Visibility.FAMILY, cover, files)

    private fun of(path: String) = requests.filter { "${it.method} ${it.url.encodedPath}" == path }

    @Test
    fun `every file goes up with exactly the link's headers, then the book with the import's key`() = runBlocking {
        val imports = imports()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val record = imports.start(draft(listOf(picked(1, "Opening"), picked(2)), cover = jpeg))
        assertEquals(listOf(record.id to false), scheduler.queued)

        assertEquals(BookImports.Outcome.DONE, imports.run(record.id))

        val links = of("POST /api/v2/uploads").map { Json.parseToJsonElement(it.body!!.utf8()).jsonObject }
        assertEquals(listOf("book_file", "book_file", "book_cover"), links.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals(listOf("1 - Part.mp3", "2 - Part.mp3", "cover.jpg"), links.map { it["filename"]!!.jsonPrimitive.content })
        assertEquals(listOf(10L, 23L, 6L), links.map { it["size_bytes"]!!.jsonPrimitive.content.toLong() })
        assertEquals(listOf("audio/mpeg", "audio/mpeg", "image/jpeg"), links.map { it["content_type"]!!.jsonPrimitive.content })
        for (n in 1..3) {
            val put = of("PUT /storage/$n").single()
            assertEquals("s$n", put.url.queryParameter("X-Amz-Signature"))
            assertEquals(if (n == 3) "image/jpeg" else "audio/mpeg", put.headers["Content-Type"])
            assertEquals("up$n", put.headers["x-amz-meta-upload"])
            assertNull("storage links are signed: no token", put.headers["Authorization"])
            assertEquals(put.body!!.size.toString(), put.headers["Content-Length"])
        }
        assertEquals("first file", of("PUT /storage/1").single().body!!.utf8())
        val book = of("POST /api/v2/books").single()
        assertEquals(record.id, book.headers["Idempotency-Key"])
        assertEquals(
            """{"author":"Jane Austen","cover_upload_id":"up3","files":[{"relative_path":"001 - 1 - Part.mp3","title":"Opening","upload_id":"up1"},""" +
                """{"relative_path":"002 - 2 - Part.mp3","upload_id":"up2"}],"narrator":"Kim Bell; Ann Lee","title":"Emma","visibility":"family"}""",
            book.body!!.utf8(),
        )
        // Done: the row goes, the folder and the folder's permission with it, and the catalogue catches up.
        assertTrue(imports.state.value.records.isEmpty())
        assertFalse(File(dir, record.id).exists())
        assertEquals(listOf("content://tree/folder"), released)
        assertEquals(listOf("b9"), made)
        assertTrue(imports().state.value.records.isEmpty())
    }

    @Test
    fun `killed mid-upload, the next run reuses the links and the key`() = runBlocking {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        var firstPut = true
        override = { request ->
            if (request.url.encodedPath == "/storage/2" && firstPut) {
                firstPut = false
                blocked.countDown()
                release.await(10, TimeUnit.SECONDS)
                MockResponse.Builder().code(200).build()
            } else {
                null
            }
        }
        val first = imports()
        val record = first.start(draft(listOf(picked(1), picked(2), picked(3))))
        val run = async(Dispatchers.IO) { first.run(record.id) }
        assertTrue(blocked.await(10, TimeUnit.SECONDS))
        run.cancelAndJoin()
        release.countDown()

        // What the disk says after the kill: file 1 up, file 2 with its link, file 3 not asked for yet.
        val restarted = imports()
        val kept = restarted.state.value.records.getValue(record.id)
        assertEquals(listOf(true, false, false), kept.files.map { it.uploaded })
        assertEquals("up2", kept.files[1].uploadId)
        assertNull(kept.files[2].uploadId)

        assertEquals(BookImports.Outcome.DONE, restarted.run(record.id, attempt = 1))
        assertEquals("one link per file, none asked for twice", 3, of("POST /api/v2/uploads").size)
        assertEquals(1, of("PUT /storage/1").size)
        assertEquals(2, of("PUT /storage/2").size)
        assertEquals(1, of("PUT /storage/3").size)
        assertEquals(listOf(record.id), of("POST /api/v2/books").map { it.headers["Idempotency-Key"] })
    }

    @Test
    fun `killed while the book was being made, the retry sends the same key and body`() = runBlocking {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        var firstBook = true
        override = { request ->
            if (request.url.encodedPath == "/api/v2/books" && firstBook) {
                firstBook = false
                blocked.countDown()
                release.await(10, TimeUnit.SECONDS)
                json(BOOK, 201)
            } else {
                null
            }
        }
        val first = imports()
        val record = first.start(draft(listOf(picked(1), picked(2))))
        val run = async(Dispatchers.IO) { first.run(record.id) }
        assertTrue(blocked.await(10, TimeUnit.SECONDS))
        run.cancelAndJoin()
        release.countDown()

        assertEquals(BookImports.Outcome.DONE, imports().run(record.id, attempt = 1))
        val books = of("POST /api/v2/books")
        assertEquals(2, books.size)
        assertEquals(setOf(record.id), books.map { it.headers["Idempotency-Key"] }.toSet())
        assertEquals(books[0].body!!.utf8(), books[1].body!!.utf8())
        assertEquals("no file went up twice", 2, of("POST /api/v2/uploads").size)
    }

    @Test
    fun `no room or credit stops the import with its sentence, and Try Again goes on`() = runBlocking {
        var full = true
        override = { if (full && it.url.encodedPath == "/api/v2/uploads") problem("payment_required", 402) else null }
        val imports = imports()
        val record = imports.start(draft(listOf(picked(1))))
        assertEquals(BookImports.Outcome.FAILED, imports.run(record.id))
        val failure = imports.state.value.records.getValue(record.id).failure!!
        assertEquals(ImportFailure.NO_ROOM, failure.code)
        assertEquals(R.string.import_no_room, failure.message().id)
        assertTrue(imports.state.value.active("s1").isNotEmpty())

        full = false
        imports.retry(record.id)
        assertNull(imports.state.value.records.getValue(record.id).failure)
        assertEquals(record.id to true, scheduler.queued.last())
        assertEquals(BookImports.Outcome.DONE, imports.run(record.id))
    }

    @Test
    fun `no right to upload stops the import and takes the + away`() = runBlocking {
        override = { if (it.url.encodedPath == "/api/v2/uploads") problem("forbidden", 403) else null }
        val imports = imports()
        val record = imports.start(draft(listOf(picked(1))))
        assertEquals(BookImports.Outcome.FAILED, imports.run(record.id))
        assertEquals(ImportFailure.NOT_ALLOWED, imports.state.value.records.getValue(record.id).failure!!.code)
        assertEquals(listOf("s1"), refused)
    }

    @Test
    fun `storage refusing a file asks for a new link next time`() = runBlocking {
        var refuse = true
        override = { if (refuse && it.url.encodedPath == "/storage/1") MockResponse.Builder().code(403).build() else null }
        val imports = imports()
        val record = imports.start(draft(listOf(picked(1))))
        assertEquals(BookImports.Outcome.FAILED, imports.run(record.id))
        val stopped = imports.state.value.records.getValue(record.id)
        assertEquals(ImportFailure(ImportFailure.STORAGE_REFUSED, 403), stopped.failure)
        assertNull(stopped.files.single().uploadUrl)
        refuse = false
        imports.retry(record.id)
        assertEquals(BookImports.Outcome.DONE, imports.run(record.id))
        assertEquals(2, of("POST /api/v2/uploads").size)
    }

    @Test
    fun `a passing failure is tried again a few times, then shown`() = runBlocking {
        override = { if (it.url.encodedPath == "/api/v2/uploads") problem("unavailable", 503) else null }
        val imports = imports()
        val record = imports.start(draft(listOf(picked(1))))
        assertEquals(BookImports.Outcome.RETRY, imports.run(record.id, attempt = 0))
        assertNull(imports.state.value.records.getValue(record.id).failure)
        assertEquals(BookImports.Outcome.FAILED, imports.run(record.id, attempt = BookImports.MAX_ATTEMPTS))
        assertEquals(ImportFailure.NETWORK, imports.state.value.records.getValue(record.id).failure!!.code)
    }

    @Test
    fun `a document that can no longer be read says so`() = runBlocking {
        val imports = imports()
        val record = imports.start(draft(listOf(picked(1).copy(uri = "content://docs/gone"))))
        assertEquals(BookImports.Outcome.FAILED, imports.run(record.id))
        assertEquals(ImportFailure.FILE_UNREADABLE, imports.state.value.records.getValue(record.id).failure!!.code)
        assertTrue(of("PUT /storage/1").isEmpty())
    }

    @Test
    fun `cancel forgets the import, its folder and its permissions`() = runBlocking {
        val imports = imports()
        val record = imports.start(draft(listOf(picked(1)), cover = byteArrayOf(1, 2, 3)))
        assertTrue(File(dir, record.id).isDirectory)
        imports.cancel(record.id)
        assertTrue(imports.state.value.records.isEmpty())
        assertFalse(File(dir, record.id).exists())
        assertEquals(listOf("content://tree/folder"), released)
        assertTrue(scheduler.queued.isEmpty())
        assertEquals(BookImports.Outcome.DONE, imports.run(record.id))
        assertTrue(of("POST /api/v2/uploads").isEmpty())
    }

    @Test
    fun `the worker runs the import and succeeds`() = runBlocking {
        val imports = imports()
        val record = imports.start(draft(listOf(picked(1))))
        val worker = TestListenableWorkerBuilder<ImportWorker>(context)
            .setInputData(workDataOf(ImportWorker.KEY to record.id))
            .setWorkerFactory(ImportWorker.factory { imports })
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, of("POST /api/v2/books").size)
    }

    @Test
    fun `progress counts the bytes of the files up and on their way`() {
        val record = ImportRecord(
            "i1", "s1",
            files = listOf(
                ImportFile("a", "a.mp3", null, "audio/mpeg", 100, "x", uploaded = true),
                ImportFile("b", "b.mp3", null, "audio/mpeg", 300, "y"),
            ),
            startedAtMs = 0,
        )
        assertEquals(0.5f, ImportsSnapshot(mapOf("i1" to record), sent = mapOf("b" to 100L)).fraction(record), 0.001f)
        assertEquals(listOf("i1"), ImportsSnapshot(mapOf("i1" to record)).active("s1").map { it.id })
        assertTrue(ImportsSnapshot(mapOf("i1" to record.copy(bookId = "b"))).active("s1").isEmpty())
    }

    private companion object {
        const val BOOK = """{"id":"b9","title":"Emma","credits":[],"duration_ms":0,"visibility":"family","owner_id":"u1","created_at":"2026-11-22T10:00:00Z","updated_at":"2026-11-22T10:00:00Z","files":[],"chapters":[],"series":[]}"""
    }
}
