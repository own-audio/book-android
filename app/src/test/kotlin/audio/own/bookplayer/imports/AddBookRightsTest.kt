package audio.own.bookplayer.imports

import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.Tokens
import java.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the Library's + offers: upload and narration rights from `/me` and discovery. */
class AddBookRightsTest {
    private val server = MockWebServer()
    private var canUpload = true
    private var canGenerate = true
    private var narration = true

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/v2/me" -> json(
                    """{"id":"u1","email":"a@example.com","display_name":"A","family":{"id":"f1","role":"member","can_upload":$canUpload,"can_generate":$canGenerate}}""",
                )
                "/.well-known/own-audio" -> json("""{"name":"Home","modules":{"narration":$narration,"uploads":{"presigned":true}}}""")
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private val model = AddBookRightsModel {
        OwnAudioClient(server.url("/").toString(), OkHttpClient(), "own.audio Books/0.0 (test)", TokenSession(Tokens("t", "r", Instant.now().plusSeconds(3600), "")))
    }

    @Test
    fun `both rights and the module give the menu`() = runBlocking {
        model.refresh("s1")
        assertEquals(AddBookRights(canImport = true, canNarrate = true), model.rights.value["s1"])
    }

    @Test
    fun `narration needs the module and the right to generate`() = runBlocking {
        narration = false
        model.refresh("s1")
        assertEquals(AddBookRights(canImport = true, canNarrate = false), model.rights.value["s1"])
        narration = true
        canGenerate = false
        model.refresh("s1", force = true)
        assertEquals(AddBookRights(canImport = true, canNarrate = false), model.rights.value["s1"])
    }

    @Test
    fun `a member without the upload right gets no import`() = runBlocking {
        canUpload = false
        canGenerate = false
        model.refresh("s1")
        assertEquals(false, model.rights.value.getValue("s1").any)
    }

    @Test
    fun `a refused upload holds for the run, whatever the account says`() = runBlocking {
        model.refresh("s1")
        model.refused("s1")
        assertEquals(AddBookRights(canImport = false, canNarrate = true), model.rights.value["s1"])
        model.refresh("s1", force = true)
        assertEquals(false, model.rights.value.getValue("s1").canImport)
    }
}
