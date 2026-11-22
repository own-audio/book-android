package audio.own.book.library

import audio.own.kit.ownaudio.NarrationLanguage
import audio.own.kit.ownaudio.NarrationVoice
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookImportTest {
    private val made = """{"id":"b9","title":"Emma","credits":[],"duration_ms":0,"visibility":"private","owner_id":"u1","created_at":"2026-11-22T10:00:00Z","updated_at":"2026-11-22T10:00:00Z","files":[],"chapters":[],"series":[]}"""

    @Test
    fun `types come from the extension as iOS sends them`() {
        assertEquals("audio/mpeg", ImportMetadata.contentType("01 Intro.MP3"))
        assertEquals("audio/mp4", ImportMetadata.contentType("book.m4b"))
        assertEquals("audio/ogg", ImportMetadata.contentType("x.opus"))
        assertEquals("audio/x-weird", ImportMetadata.contentType("noext", "audio/x-weird"))
        assertEquals("application/octet-stream", ImportMetadata.contentType("notes"))
        assertTrue(ImportMetadata.isAudio("a.flac"))
        assertTrue(ImportMetadata.isAudio("track", "audio/mpeg"))
        assertFalse(ImportMetadata.isAudio("cover.jpg", "image/jpeg"))
    }

    @Test
    fun `names split on semicolons, files are numbered, folders sort as people read`() {
        assertEquals(listOf("Jane Austen", "Anne Bell"), ImportMetadata.names(" Jane Austen ; Anne Bell;; "))
        assertEquals("001 - Chapter one.mp3", ImportMetadata.relativePath(0, "Chapter one.mp3"))
        assertEquals("012 - x.mp3", ImportMetadata.relativePath(11, "x.mp3"))
        assertEquals(listOf("2.mp3", "10.mp3", "Part b.mp3"), ImportMetadata.inNameOrder(listOf("Part b.mp3", "10.mp3", "2.mp3"), Locale.ENGLISH) { it })
    }

    @Test
    fun `a picture's type is read from its bytes`() {
        assertEquals("image/png", ImportMetadata.imageType(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 0)))
        assertEquals("image/jpeg", ImportMetadata.imageType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `only languages with a voice can be read in`() {
        val options = NarrationOptions(
            listOf(NarrationLanguage("en", "English"), NarrationLanguage("cs", "Czech"), NarrationLanguage("de", "German")),
            listOf(NarrationVoice("v1", "Ava", "en", "female"), NarrationVoice("v2", "Jan", "cs", "male")),
        )
        assertEquals(listOf("en", "cs"), options.targetLanguages.map { it.code })
        assertEquals(listOf("v2"), options.voices("cs").map { it.id })
    }

    @Test
    fun `a link per file, the bytes with exactly its headers and no token, the book with the import's key`() = runBlocking {
        Stub { request ->
            when ("${request.method} ${request.url.encodedPath}") {
                "POST /api/v2/uploads" -> {
                    val body = request.body!!.utf8()
                    assertEquals("""{"content_type":"audio/mpeg","filename":"01.mp3","kind":"book_file","size_bytes":5}""", body)
                    json("""{"upload_id":"up1","url":"${request.url.newBuilder().encodedPath("/storage/up1").query("sig=abc").build()}","method":"PUT","headers":{"Content-Type":"audio/mpeg","x-amz-meta-id":"up1"},"expires_at":"2099-01-01T00:00:00Z"}""")
                }
                "PUT /storage/up1" -> {
                    assertEquals(null, request.headers["Authorization"])
                    assertEquals("up1", request.headers["x-amz-meta-id"])
                    assertEquals("audio/mpeg", request.headers["Content-Type"])
                    assertEquals("hello", request.body!!.utf8())
                    json("", 200)
                }
                "POST /api/v2/books" -> {
                    assertEquals("imp-1", request.headers["Idempotency-Key"])
                    assertEquals(
                        """{"author":"Jane Austen; Anne Bell","files":[{"relative_path":"001 - 01.mp3","title":"Opening","upload_id":"up1"}],"narrator":"Kim","title":"Emma","visibility":"family"}""",
                        request.body!!.utf8(),
                    )
                    json(made, 201)
                }
                else -> problem("not_found", 404)
            }
        }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub), "u1", memoryCatalogue())
            val target = source.uploadTarget(UploadKind.AUDIO, "01.mp3", "audio/mpeg", 5)
            source.upload(target, "hello".encodeToByteArray().toRequestBody("audio/mpeg".toMediaType()))
            val book = source.createBook(
                NewBookDetails("Emma", listOf("Jane Austen", "Anne Bell"), listOf("Kim"), null, Visibility.FAMILY),
                coverUploadId = null,
                files = listOf(ImportedFile("up1", "01.mp3", "Opening")),
                importId = "imp-1",
            )
            assertEquals("b9", book.id)
        }
    }

    @Test
    fun `no room or credit keeps its status`() = runBlocking {
        Stub { problem("payment_required", 402) }.use { stub ->
            val source = OwnAudioBookSource("server-1", ownAudioClient(stub), "u1", memoryCatalogue())
            try {
                source.uploadTarget(UploadKind.AUDIO, "01.mp3", "audio/mpeg", 5)
                fail()
            } catch (e: SourceException.Http) {
                assertEquals(402, e.status)
            }
        }
    }
}
