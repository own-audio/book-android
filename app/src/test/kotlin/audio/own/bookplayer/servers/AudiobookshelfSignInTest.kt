package audio.own.bookplayer.servers

import audio.own.bookplayer.FakeAudiobookshelf
import audio.own.bookplayer.ModelHarness
import audio.own.bookplayer.R as AppR
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.app.UserFacingException
import audio.own.bookplayer.eventually
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerLibraryRef
import audio.own.core.strings.R
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for the server icon, which is decoded with Android's BitmapFactory.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudiobookshelfSignInTest {
    private val fake = FakeAudiobookshelf()
    private val harness = ModelHarness()
    private val model get() = harness.model

    @After
    fun tearDown() {
        fake.close()
        harness.close()
    }

    private fun expectMessage(id: Int, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("expected a failure")
        } catch (e: UserFacingException) {
            assertEquals(id, e.text.id)
        }
    }

    @Test
    fun anAddressThatIsNotOwnAudioIsTriedAsAudiobookshelf() = runBlocking {
        val found = ServerDetector(harness.http, "test", harness.log).detect(fake.url)
        assertTrue(found is Detected.Audiobookshelf)
        assertEquals("2.37.1", (found as Detected.Audiobookshelf).status.serverVersion)
        assertEquals(listOf("GET /.well-known/own-audio", "GET /status"), fake.paths())
    }

    @Test
    fun signingInSavesTheBookLibrariesOnlyAndShowsTheFirst() = runBlocking {
        model.signIn(fake.url, " kim ", fake.password, "")
        val saved = harness.store.loadAll().single()
        assertEquals(ServerKinds.AUDIOBOOKSHELF, saved.kind)
        assertEquals(fake.server.hostName, saved.displayName)
        assertEquals("u7", saved.userId)
        val auth = saved.auth as ServerAuth.Audiobookshelf
        assertEquals("kim", auth.userName)
        assertEquals("abs-a1", auth.tokens?.access)
        assertEquals("abs-r1", auth.tokens?.refresh)
        assertEquals(listOf(ServerLibraryRef("lib1", "Fiction"), ServerLibraryRef("lib2", "Kids")), saved.libraries)
        assertEquals("lib1", saved.libraryId)
        assertEquals(saved.id, model.state.value.activeId)
        assertTrue(fake.requests.first { it.url.encodedPath == "/login" }.headers["x-return-tokens"] == "true")
    }

    @Test
    fun theAccountsDefaultLibraryIsShownFirst() = runBlocking {
        fake.defaultLibrary = "lib2"
        model.signIn(fake.url, "kim", fake.password, "NAS")
        val saved = harness.store.loadAll().single()
        assertEquals("NAS", saved.displayName)
        assertEquals("lib2", saved.libraryId)
    }

    @Test
    fun anAccountWithoutABookLibraryIsRefusedAndNothingIsSaved() {
        fake.libraries = """[{"id":"pod","name":"Podcasts","mediaType":"podcast"}]"""
        expectMessage(AppR.string.error_no_book_library) { model.signIn(fake.url, "kim", fake.password, "") }
        runBlocking { assertTrue(harness.store.loadAll().isEmpty()) }
    }

    @Test
    fun anAccountAddedAgainIsRefused() = runBlocking {
        model.signIn(fake.url, "kim", fake.password, "")
        // The address typed differently: upper case, a trailing slash.
        expectMessage(AppR.string.error_already_added) { model.signIn(fake.url.uppercase() + "/", " Kim ", fake.password, "Again") }
        assertEquals(1, harness.store.loadAll().size)
        assertEquals("refused before signing in", 1, fake.requests.count { it.url.encodedPath == "/login" })
    }

    @Test
    fun aWrongPasswordSavesNothing() {
        expectMessage(R.string.error_rejected) { model.signIn(fake.url, "kim", "wrong", "") }
        runBlocking { assertTrue(harness.store.loadAll().isEmpty()) }
    }

    @Test
    fun aServerAskingForALongWaitSaysTooManyTries() {
        fake.rateLimited = true
        expectMessage(R.string.error_rate_limited) { model.signIn(fake.url, "kim", fake.password, "") }
    }

    @Test
    fun theLibraryCanBeChangedAndTheHomeSwitchChoosesOne() = runBlocking {
        model.signIn(fake.url, "kim", fake.password, "")
        val id = harness.store.loadAll().single().id
        model.select(id, "lib2")
        assertEquals("lib2", harness.store.loadAll().single().libraryId)
        fake.libraries = """[{"id":"lib2","name":"Kids","mediaType":"book"}]"""
        assertEquals(listOf(ServerLibraryRef("lib2", "Kids")), model.bookLibraries(id))
        assertEquals(listOf(ServerLibraryRef("lib2", "Kids")), harness.store.loadAll().single().libraries)
    }

    @Test
    fun checkNowAsksAuthorizeAndARefusedRefreshMarksTheServer() = runBlocking {
        model.signIn(fake.url, "kim", fake.password, "")
        val id = harness.store.loadAll().single().id
        model.checkNow(id)
        assertEquals(ConnectionCheck.Status.OK, model.state.value.checks[id]?.status)
        assertEquals(ConnectionState.Connected(fake.url), model.state.value.connections[id])
        fake.authorizeStatus = 401
        fake.refreshRefused = true
        model.checkNow(id)
        assertEquals(ConnectionCheck.Status.REJECTED, model.state.value.checks[id]?.status)
        assertTrue(model.state.value.isRefused(id))
        assertNull((harness.store.loadAll().single().auth as ServerAuth.Audiobookshelf).tokens)
    }

    @Test
    fun signingInAgainKeepsTheServerAndItsLibrary() = runBlocking {
        model.signIn(fake.url, "kim", fake.password, "Mine")
        val id = harness.store.loadAll().single().id
        model.setLibrary(id, "lib2")
        model.signInAgain(id, "kim", fake.password)
        val saved = harness.store.loadAll().single()
        assertEquals(id, saved.id)
        assertEquals("Mine", saved.displayName)
        assertEquals("lib2", saved.libraryId)
    }

    @Test
    fun removingSignsOutOnTheServer() = runBlocking {
        model.signIn(fake.url, "kim", fake.password, "")
        model.remove(harness.store.loadAll().single().id)
        assertTrue(harness.store.loadAll().isEmpty())
        eventually { fake.paths().contains("POST /logout") }
    }

    @Test
    fun theServersIconIsFetchedOnceAndKeptOnDisk() = runBlocking {
        model.signIn(fake.url, "kim", fake.password, "")
        val record = harness.store.loadAll().single()
        val dir = Files.createTempDirectory("icons").toFile()
        val icons = ServerIcons(dir, OkHttpClient(), "test", { false }, AppLog(null, echo = false))
        icons.load(record, fake.url)
        assertNotNull(icons.icons.value[record.id])
        assertTrue(java.io.File(dir, "${record.id}.png").exists())
        val asked = fake.paths().count { it == "GET /icon192.png" }
        assertEquals(1, asked)
        // A fresh run reads it from disk.
        val again = ServerIcons(dir, OkHttpClient(), "test", { false }, AppLog(null, echo = false))
        again.load(record, fake.url)
        assertNotNull(again.icons.value[record.id])
        assertEquals(1, fake.paths().count { it == "GET /icon192.png" })
        again.remove(record.id)
        assertFalse(java.io.File(dir, "${record.id}.png").exists())
        dir.deleteRecursively()
        Unit
    }
}
