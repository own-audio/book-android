package audio.own.bookplayer.book

import audio.own.bookplayer.closeMain

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import audio.own.book.library.Book
import audio.own.book.library.Visibility
import audio.own.bookplayer.app.Load
import audio.own.bookplayer.browse.FakeBookSource
import audio.own.bookplayer.browse.Samples
import audio.own.bookplayer.browse.SharingFakeSource
import audio.own.bookplayer.eventually
import audio.own.bookplayer.mainThread
import audio.own.bookplayer.playback.PlaybackModel
import audio.own.bookplayer.player.BookmarksViewModel
import audio.own.bookplayer.player.ResumeDialog
import audio.own.book.library.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bookmarks, favourites, finished, reset and sharing; the "Continue from ‹device›?" dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookActionsTest {
    @get:Rule val compose = createComposeRule()

    private val main = mainThread()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        closeMain(main)
    }

    private fun BookViewModel.loaded(): Book = (state.value.book as Load.Loaded).value

    // region Bookmarks

    @Test
    fun bookmarksAreAddedWithAnOptionalLabelRenamedAndDeleted() = runBlocking {
        val source = FakeBookSource()
        val vm = BookmarksViewModel(source, "b1") { false }
        vm.load()
        eventually { vm.state.value.bookmarks?.size == 1 }
        vm.add(120_000, "  The ball ")
        eventually { vm.state.value.bookmarks?.size == 2 }
        assertEquals("in book order", listOf(120_000L, 754_000L), vm.state.value.bookmarks!!.map { it.positionMs })
        assertEquals("The ball", vm.state.value.bookmarks!!.first().label)
        vm.add(130_000, " ")
        eventually { vm.state.value.bookmarks?.size == 3 }
        assertNull("an empty label is none", vm.state.value.bookmarks!![1].label)

        val ball = vm.state.value.bookmarks!!.first()
        vm.rename(ball, "Netherfield")
        eventually { vm.state.value.bookmarks!!.first().label == "Netherfield" }
        vm.delete(ball)
        assertEquals("gone at once", 2, vm.state.value.bookmarks?.size)
        eventually { source.bookmarkCalls.size == 4 }
        assertEquals(listOf("add 120000 The ball", "add 130000 null", "rename m3 Netherfield", "delete m3"), source.bookmarkCalls)
    }

    @Test
    fun aDeleteTheServerRefusesIsPutBack() = runBlocking {
        val source = FakeBookSource()
        val vm = BookmarksViewModel(source, "b1") { false }
        vm.load()
        eventually { vm.state.value.bookmarks?.size == 1 }
        source.failChanges = SourceException.Network
        vm.delete(vm.state.value.bookmarks!!.single())
        eventually { vm.state.value.error != null }
        assertEquals(1, vm.state.value.bookmarks!!.size)
    }

    @Test
    fun offlineTheBookmarksSayWhyAndAskNothing() = runBlocking {
        val source = FakeBookSource()
        val vm = BookmarksViewModel(source, "b1") { true }
        vm.load()
        assertTrue(vm.state.value.offline)
        assertEquals(emptyList<Any>(), vm.state.value.bookmarks)
        assertTrue(source.bookmarkCalls.isEmpty())
    }

    @Test
    fun theBookPageRenamesAndDeletesItsBookmarks() = runBlocking {
        val source = FakeBookSource()
        val vm = BookViewModel(source, "b1")
        eventually { vm.state.value.bookmarks.isNotEmpty() }
        vm.renameBookmark(vm.state.value.bookmarks.single(), "Letter")
        eventually { vm.state.value.bookmarks.single().label == "Letter" }
        vm.deleteBookmark(vm.state.value.bookmarks.single())
        eventually { vm.state.value.bookmarks.isEmpty() }
    }

    // endregion

    // region Favourites, finished, reset, sharing

    @Test
    fun aFavouriteIsKeptOnTheServer() = runBlocking {
        val source = FakeBookSource()
        val vm = BookViewModel(source, "b1")
        eventually { vm.state.value.book is Load.Loaded }
        delay(100)
        assertFalse(vm.state.value.isFavourite)
        vm.toggleFavourite()
        eventually { "b1" in source.favourite }
        assertTrue(vm.state.value.isFavourite)
        vm.toggleFavourite()
        eventually { "b1" !in source.favourite }
    }

    @Test
    fun markFinishedThenNotFinishedGoesThroughPlayback() = runBlocking {
        val source = FakeBookSource()
        val calls = mutableListOf<Boolean>()
        val vm = BookViewModel(source, "b1", markFinished = { book, finished ->
            calls += finished
            source.saveProgress(book.id, if (finished) book.durationMs else 0, book.durationMs, finished, java.time.Instant.now())
        })
        eventually { vm.state.value.progress != null }
        assertFalse(vm.state.value.progress!!.finished)
        vm.setFinished(true)
        eventually { vm.state.value.progress?.finished == true }
        assertEquals(Samples.pride.durationMs, vm.state.value.progress!!.positionMs)
        vm.setFinished(false)
        eventually { vm.state.value.progress?.finished == false }
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun resetForgetsThePosition() = runBlocking {
        val source = FakeBookSource()
        var reset = 0
        val vm = BookViewModel(source, "b1", resetProgress = { reset++; source.clearProgress(it.id) })
        eventually { vm.state.value.progress != null }
        vm.resetProgress()
        eventually { reset == 1 && vm.state.value.progress == null }
    }

    @Test
    fun aRefusedChangeSaysSo() = runBlocking {
        val source = FakeBookSource()
        val vm = BookViewModel(source, "b1")
        eventually { vm.state.value.progress != null }
        source.failChanges = SourceException.NotAllowed
        vm.setFinished(true)
        eventually { vm.state.value.error != null }
        assertFalse(source.progress["b1"]!!.finished)
    }

    @Test
    fun theOwnerSharesAndKeepsABookAndARefusalPutsItBack() = runBlocking {
        val source = SharingFakeSource()
        var synced = 0
        val vm = BookViewModel(source, "b1", onShared = { synced++ })
        eventually { vm.state.value.book is Load.Loaded }
        assertTrue(vm.state.value.canShare)
        assertEquals(Visibility.FAMILY, vm.loaded().visibility)
        vm.setShared(false)
        assertEquals("shown at once", Visibility.PRIVATE, vm.loaded().visibility)
        eventually { source.shared.size == 1 && synced == 1 }
        source.failChanges = SourceException.Network
        vm.setShared(true)
        eventually { vm.state.value.error != null }
        assertEquals(Visibility.PRIVATE, vm.loaded().visibility)
        assertEquals(listOf("b1" to false), source.shared)

        val notMine = BookViewModel(source, "b2")
        eventually { notMine.state.value.book is Load.Loaded }
        assertFalse("someone else's book", notMine.state.value.canShare)
        assertFalse("no households on this server", BookViewModel(FakeBookSource(), "b1", Samples.pride.copy(ownerId = "me")).state.value.canShare)
    }

    // endregion

    // region Continue from ‹device›?

    private fun question(device: String?) = PlaybackModel.ResumeQuestion("s1", Samples.pride, localMs = 754_000, otherMs = 3_723_000, otherDevice = device)

    @Test
    fun theDialogNamesTheDeviceAndBothPlaces() {
        var answer: String? = null
        compose.setContent { ResumeDialog(question("Car"), onOther = { answer = "other" }, onLocal = { answer = "local" }, onDismiss = { answer = "none" }) }
        compose.onNodeWithText("Continue from Car?").assertExists()
        compose.onNodeWithText("Car got to 1:02:03; this device was at 12:34.").assertExists()
        compose.onNodeWithText("Keep this device's position (12:34)").assertExists()
        compose.onNodeWithTag("resume.other").performClick()
        assertEquals("other", answer)
    }

    @Test
    fun aDeviceWithoutANameIsAnotherDevice() {
        var answer: String? = null
        compose.setContent { ResumeDialog(question(null), onOther = { answer = "other" }, onLocal = { answer = "local" }, onDismiss = {}) }
        compose.onNodeWithText("Continue from another device?").assertExists()
        compose.onNodeWithText("Another device got to 1:02:03; this device was at 12:34.").assertExists()
        compose.onNodeWithText("Continue from there (1:02:03)").assertExists()
        compose.onNodeWithTag("resume.local").performClick()
        assertEquals("local", answer)
    }

    @Test
    @Config(qualifiers = "cs")
    fun theDialogInCzech() {
        compose.setContent { ResumeDialog(question("Auto"), onOther = {}, onLocal = {}, onDismiss = {}) }
        compose.onNodeWithText("Pokračovat z Auto?").assertExists()
        compose.onNodeWithText("Zůstat na pozici tohoto zařízení (12:34)").assertExists()
    }

    // endregion
}
