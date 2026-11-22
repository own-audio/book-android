package audio.own.bookplayer.narration

import audio.own.bookplayer.closeMain

import androidx.room.Room
import audio.own.book.library.OwnAudioBookSource
import audio.own.book.library.catalogue.BookCatalogueDatabase
import audio.own.book.library.catalogue.OwnAudioCatalogue
import audio.own.bookplayer.R
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.eventually
import audio.own.bookplayer.mainThread
import audio.own.kit.ownaudio.NarrationJob
import audio.own.kit.ownaudio.NarrationOutput
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.Tokens
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Narrate an Ebook against a pretend hosted server (never a real one: no test starts a paid job),
 * and the jobs' rows: kept on disk, polled less often while nothing moves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NarrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val main = mainThread()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "narration-${System.nanoTime()}").apply { mkdirs() }
    private val server = MockWebServer()
    private val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()
    private var quoteStatus = 200
    private var stage = "narrating"
    private var chaptersDone = 2

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when ("${request.method} ${request.url.encodedPath}") {
                    "GET /api/v2/narration/languages" -> json("""[{"code":"de","label":"German"},{"code":"en","label":"English"},{"code":"cs","label":"Czech"}]""")
                    "GET /api/v2/narration/voices" -> json(
                        """[{"id":"v1","display_name":"Ava","language":"en","gender":"female"},{"id":"v2","display_name":"Jan","language":"cs","gender":"male"},{"id":"v3","display_name":"Eva","language":"cs","gender":"female"}]""",
                    )
                    "POST /api/v2/narration/estimate" -> json("""{"estimated_char_count":12345}""")
                    "POST /api/v2/narration/quote" ->
                        if (quoteStatus == 200) {
                            json("""{"char_count":12345,"translation_cost_cents":40,"tts_cost_cents":110,"quoted_price_cents":150,"currency":"USD"}""")
                        } else {
                            json("""{"type":"x","title":"x","status":$quoteStatus,"code":"insufficient_credit"}""", quoteStatus)
                        }
                    "POST /api/v2/narration/jobs" -> json(job("queued", 0), 201)
                    "GET /api/v2/narration/jobs/j1" -> json(job(stage, chaptersDone))
                    else -> json("""{"type":"x","title":"x","status":404,"code":"not_found"}""", 404)
                }
            }
        }
        server.start()
    }

    private fun job(stage: String, done: Int) =
        """{"id":"j1","title":"Emma","stages":["queued","extracting","narrating","assembling"],"stage":"$stage",""" +
            """"chapters":[${(1..4).joinToString(",") { """{"id":"c$it","title":"Ch $it","status":"${if (it <= done) "complete" else "pending"}"}""" }}],""" +
            """"book_id":${if (stage == "complete") "\"b7\"" else "null"},"quoted_price_cents":150,"currency":"USD"}"""

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").body(body).build()

    private val source = OwnAudioBookSource(
        "s1",
        OwnAudioClient(server.url("/").toString(), OkHttpClient(), "own.audio Books/0.0 (Android test)", TokenSession(Tokens("token", "r", Instant.now().plusSeconds(3600), ""))),
        "u1",
        OwnAudioCatalogue(Room.inMemoryDatabaseBuilder(context, BookCatalogueDatabase::class.java).allowMainThreadQueries().build()),
    )
    private val completed = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun narrations() = BookNarrations(File(dir, "narrations.json"), { if (it == "s1") source else null }, scope, AppLog(null, echo = false), onCompleted = { completed += it.id })
    private val ebook = File(dir, "emma.epub").apply { writeText("an ebook") }

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        closeMain(main)
        scope.cancel()
        server.close()
        dir.deleteRecursively()
    }

    private fun form(credit: NarrationCredit? = NarrationCredit(BigDecimal("4.50"), "USD", false), store: BookNarrations = narrations()) =
        NarrateBookViewModel("s1", source, { credit }, { PickedEbook(ebook, "Emma.epub") }, store)

    private fun count(path: String) = requests.count { "${it.method} ${it.url.encodedPath}" == path }

    @Test
    fun `only languages with a voice are offered to read in, with their first voice`() = runBlocking {
        val vm = form()
        eventually { vm.state.value.options != null && vm.state.value.credit != null }
        val s = vm.state.value
        assertEquals(listOf("en", "cs"), s.options!!.targetLanguages.map { it.code })
        assertEquals("en", s.targetLanguage)
        assertEquals("v1", s.voiceId)
        vm.setTargetLanguage("cs")
        assertEquals("v2", vm.state.value.voiceId)
        assertEquals(listOf("v2", "v3"), vm.state.value.voices.map { it.id })
        assertFalse("nothing to price before an ebook", vm.state.value.canPrice)
    }

    @Test
    fun `the count is asked once per file, the quote for every voice`() = runBlocking {
        val vm = form()
        eventually { vm.state.value.options != null }
        vm.take("content://docs/emma")
        eventually { vm.state.value.ebook != null }
        assertEquals("the title from the file's name", "Emma", vm.state.value.title)
        vm.price()
        eventually { vm.state.value.quote != null }
        assertEquals(12345, vm.state.value.charCount)
        assertEquals(150, vm.state.value.quote!!.quotedPriceCents)
        assertTrue(vm.state.value.canStart)

        vm.setTargetLanguage("cs")
        assertNull("another voice, another price", vm.state.value.quote)
        vm.price()
        eventually { vm.state.value.quote != null }
        assertEquals(1, count("POST /api/v2/narration/estimate"))
        assertEquals(2, count("POST /api/v2/narration/quote"))
        assertEquals(0, count("POST /api/v2/narration/jobs"))
    }

    @Test
    fun `a family without credit can see the price but not start`() = runBlocking {
        val vm = form(credit = NarrationCredit(BigDecimal.ZERO, "USD", true))
        eventually { vm.state.value.options != null && vm.state.value.credit != null }
        vm.take("content://docs/emma")
        eventually { vm.state.value.ebook != null }
        vm.price()
        eventually { vm.state.value.quote != null }
        assertFalse(vm.state.value.canStart)
        vm.start()
        assertEquals(0, count("POST /api/v2/narration/jobs"))
    }

    @Test
    fun `a refusal for credit says so`() = runBlocking {
        quoteStatus = 402
        val vm = form()
        eventually { vm.state.value.options != null }
        vm.take("content://docs/emma")
        eventually { vm.state.value.ebook != null }
        vm.price()
        eventually { vm.state.value.error != null }
        assertEquals(R.string.narration_no_credit, vm.state.value.error!!.id)
    }

    @Test
    fun `starting on the pretend server keeps the job and closes the form`() = runBlocking {
        val store = narrations()
        val vm = form(store = store)
        eventually { vm.state.value.options != null }
        vm.take("content://docs/emma")
        eventually { vm.state.value.ebook != null }
        vm.setOutput(NarrationOutput.SINGLE_M4B)
        vm.price()
        eventually { vm.state.value.quote != null }
        vm.start()
        eventually { vm.state.value.done }
        val body = requests.single { it.url.encodedPath == "/api/v2/narration/jobs" }.body!!.utf8()
        assertTrue(body.contains("single_m4b") && body.contains("v1") && body.contains("an ebook"))
        assertEquals(listOf("j1"), store.active("s1").map { it.id })
        assertFalse("the copy goes once the server has it", ebook.exists())
        assertEquals("j1", narrations().entries.value.keys.single())
    }

    @Test
    fun `polling waits twice as long while nothing moves, up to a minute`() {
        val delays = generateSequence(BookNarrations.FIRST_DELAY_MS) { BookNarrations.nextDelay(it, changed = false) }.take(7).toList()
        assertEquals(listOf(4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), delays)
        assertEquals(4_000L, BookNarrations.nextDelay(60_000, changed = true))
    }

    @Test
    fun `a poll brings the stage and chapters, and a finished job is said once`() = runBlocking {
        val store = narrations()
        store.add(NarrationJob(id = "j1", title = "Emma", stage = "queued", stages = listOf("queued", "extracting", "narrating", "assembling")), "s1")
        assertEquals(true, store.pollOnce())
        val running = store.entries.value.getValue("j1")
        assertEquals("narrating", running.stage)
        assertEquals(2, running.chaptersDone)
        assertEquals(4, running.chaptersTotal)
        assertEquals((2 + 0.5f) / 4, running.fraction, 0.001f)
        assertEquals("nothing moved", false, store.pollOnce())

        stage = "complete"
        chaptersDone = 4
        assertEquals(true, store.pollOnce())
        assertEquals(listOf("j1"), completed)
        assertEquals("b7", narrations().entries.value.getValue("j1").bookId)
        assertFalse(store.hasRunning)

        store.forget("j1")
        assertTrue(narrations().entries.value.isEmpty())
    }

    @Test
    fun `with no server left to ask, polling stops`() = runBlocking {
        val store = BookNarrations(File(dir, "n.json"), { null }, scope, AppLog(null, echo = false))
        store.add(NarrationJob(id = "j1", title = "Emma", stage = "queued"), "gone")
        assertNull(store.pollOnce())
    }

    @Test
    fun `prices are in the listener's locale and the server's currency`() {
        assertEquals("$1.50", formatPrice(150, "USD", Locale.US))
        assertEquals("1,50 US$", formatPrice(150, "USD", Locale.forLanguageTag("cs-CZ")).replace(' ', ' '))
    }
}
