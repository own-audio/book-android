package audio.own.bookplayer.servers

import audio.own.bookplayer.FakeAudiobookshelf
import audio.own.bookplayer.FakeOwnAudio
import audio.own.bookplayer.ModelHarness
import audio.own.bookplayer.app.UserFacingException
import audio.own.bookplayer.browse.FakeBookSource
import audio.own.bookplayer.closeMain
import audio.own.bookplayer.eventually
import audio.own.bookplayer.mainThread
import audio.own.bookplayer.search.SearchViewModel
import audio.own.core.strings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * book-ios `MisuseTests.testWrongSignIns` and its odd searches: sign-ins that can't work say why
 * and save nothing, one after the other, and the right one still works afterwards, even typed with
 * spaces around the address and a slash after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignInMisuseTest {
    private val own = FakeOwnAudio()
    private val shelf = FakeAudiobookshelf()
    private val harness = ModelHarness(hosted = own.url, demo = own.url)
    private val model get() = harness.model

    @After
    fun tearDown() {
        own.close()
        shelf.close()
        harness.close()
    }

    /** Fails with a sentence for the listener, never a crash or a silent success. */
    private fun refused(block: suspend () -> Unit): Int = runBlocking {
        try {
            block()
            fail("expected a refusal")
            0
        } catch (e: UserFacingException) {
            e.text.id
        }
    }

    private fun saved() = runBlocking { harness.store.loadAll() }

    @Test
    fun `wrong own audio sign-ins one after the other save nothing, then the right one works`() {
        refused { model.signIn("", "a@example.com", own.password, "") }
        refused { model.signIn("http://127.0.0.1:9", "a@example.com", own.password, "") }
        refused { model.signIn("not a server at all", "a@example.com", own.password, "") }
        repeat(3) { assertEquals(R.string.error_rejected, refused { model.signIn(own.url, "a@example.com", "wrong $it", "") }) }
        refused { model.signIn(own.url, "a@example.com", "", "") }
        assertTrue(saved().isEmpty())

        runBlocking { model.signIn("  ${own.url}/  ", " a@example.com ", own.password, "") }
        assertEquals(1, saved().size)
        assertEquals(saved().single().id, model.state.value.activeId)
    }

    @Test
    fun `audiobookshelf asking to wait, then a wrong password, then the right one`() {
        shelf.rateLimited = true
        assertEquals(R.string.error_rate_limited, refused { model.signIn(shelf.url, "kim", shelf.password, "") })
        shelf.rateLimited = false
        assertEquals(R.string.error_rejected, refused { model.signIn(shelf.url, "kim", "wrong", "") })
        assertTrue(saved().isEmpty())

        runBlocking { model.signIn(" ${shelf.url}/ ", "kim", shelf.password, "") }
        assertEquals(ServerKinds.AUDIOBOOKSHELF, saved().single().kind)
        assertEquals(saved().single().id, model.state.value.activeId)
    }

    @Test
    fun `odd searches keep the search answering`() = runBlocking {
        val main = mainThread()
        Dispatchers.setMain(main)
        try {
            val source = FakeBookSource()
            val vm = SearchViewModel(source, debounceMs = 0)
            for (text in listOf("", " ", "a", "%", "'; drop table books;--", "😀😀", "x".repeat(500), "\n\t", "zzzzzz")) vm.setText(text)
            vm.setText("  Emma ")
            eventually { vm.state.value.result?.books?.map { it.id } == listOf("b2") }
            assertTrue("short ones are not sent", source.searches.none { it.length < 2 })
            assertTrue("sent trimmed", source.searches.all { it == it.trim() })
        } finally {
            closeMain(main)
        }
    }
}
