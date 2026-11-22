package audio.own.bookplayer.browse

import audio.own.bookplayer.closeMain

import audio.own.book.library.BookFilter
import audio.own.book.library.BookSort
import audio.own.book.library.SourceException
import audio.own.bookplayer.app.Load
import audio.own.bookplayer.book.BookViewModel
import audio.own.bookplayer.eventually
import audio.own.bookplayer.home.HomeViewModel
import audio.own.bookplayer.home.greetingFor
import audio.own.bookplayer.mainThread
import audio.own.bookplayer.R
import audio.own.bookplayer.search.SearchViewModel
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenViewModelsTest {
    private val main = mainThread()
    private val source = FakeBookSource()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        closeMain(main)
    }

    private fun <T> Load<T>.value(): T = (this as Load.Loaded).value

    @Test
    fun greetingFollowsTheHour() {
        assertEquals(R.string.good_morning, greetingFor(5))
        assertEquals(R.string.good_afternoon, greetingFor(12))
        assertEquals(R.string.good_evening, greetingFor(18))
        assertEquals(R.string.good_evening, greetingFor(2))
    }

    @Test
    fun homeDropsEmptyShelvesAndKnowsProgressAndFavourites() = runBlocking {
        val vm = HomeViewModel(source)
        eventually { vm.state.value.shelves is Load.Loaded }
        assertEquals(listOf("continue-listening", "recently-added"), vm.state.value.shelves.value().map { it.id })
        assertTrue("b1" in vm.state.value.progress)
        assertEquals(setOf("b2"), vm.state.value.favourites)
    }

    @Test
    fun homeOfflineShowsNothingFromTheServer() = runBlocking {
        val vm = HomeViewModel(audio.own.book.library.OfflineBookSource(source, { emptyList() }, { true }))
        eventually { vm.state.value.shelves is Load.Loaded }
        assertTrue(vm.state.value.shelves.value().isEmpty())
    }

    @Test
    fun aQuietReloadKeepsTheShelvesWhenItFailsALoudOneSaysSo() = runBlocking {
        val vm = HomeViewModel(source)
        eventually { vm.state.value.shelves is Load.Loaded }
        source.fail = SourceException.Network
        vm.reload(quietly = true)
        delay(200)
        assertTrue(vm.state.value.shelves is Load.Loaded)
        // Asked for by the listener (Try Again), a failure is shown.
        vm.reload()
        eventually { vm.state.value.shelves is Load.Failed }
    }

    @Test
    fun theLibraryPagesBySixtyAndStopsAtTheEnd() = runBlocking {
        val vm = BooksListViewModel(source)
        eventually { vm.state.value.books is Load.Loaded }
        assertEquals(60, vm.state.value.books.value().size)
        assertEquals(70, vm.state.value.total)
        assertTrue(vm.state.value.hasMore)
        vm.loadMore()
        eventually { vm.state.value.books.value().size == 70 }
        assertFalse(vm.state.value.hasMore)
        vm.loadMore()
        delay(100)
        assertEquals(listOf(null, "60"), source.pages)
    }

    @Test
    fun sortFilterAndGridAreRemembered() = runBlocking {
        var grid: Boolean? = null
        val vm = BooksListViewModel(source, onGrid = { grid = it })
        eventually { vm.state.value.books is Load.Loaded }
        vm.setSort(BookSort.DURATION)
        vm.setDescending(true)
        eventually { vm.state.value.books is Load.Loaded && vm.state.value.query.descending && vm.state.value.books.value().first().id == "b2" }
        vm.setFilter(BookFilter.FINISHED)
        eventually { vm.state.value.books is Load.Loaded && vm.state.value.books.value().map { it.id } == listOf("b3") }
        vm.setGrid(false)
        assertEquals(false, grid)
        assertFalse(vm.state.value.grid)
    }

    @Test
    fun aFailedListSaysWhy() = runBlocking {
        source.fail = SourceException.Offline
        val vm = BooksListViewModel(source)
        eventually { vm.state.value.books is Load.Failed }
        assertEquals(audio.own.core.strings.R.string.error_offline, (vm.state.value.books as Load.Failed).message.id)
    }

    @Test
    fun searchWaitsForTwoCharactersAndTheLastKey() = runBlocking {
        val vm = SearchViewModel(source, debounceMs = 100)
        vm.setText("e")
        delay(200)
        assertNull(vm.state.value.result)
        vm.setText("em")
        vm.setText("emm")
        eventually { vm.state.value.result != null }
        assertEquals(listOf("emm"), source.searches)
        assertEquals(listOf("b2"), vm.state.value.result!!.books.map { it.id })
        vm.setText("")
        assertNull(vm.state.value.result)
    }

    @Test
    fun theBookPageLoadsTheDetailBookmarksAndFavourite() = runBlocking {
        val vm = BookViewModel(source, "b2")
        eventually { vm.state.value.book is Load.Loaded && vm.state.value.bookmarks.isNotEmpty() }
        assertTrue(vm.state.value.isFavourite)
        assertTrue(vm.state.value.canFavourite)
    }

    @Test
    fun aFavouriteTheServerRefusesIsPutBack() = runBlocking {
        val vm = BookViewModel(source, "b1")
        eventually { vm.state.value.book is Load.Loaded }
        delay(100)
        source.failFavourite = true
        vm.toggleFavourite()
        eventually { vm.state.value.error != null }
        assertFalse(vm.state.value.isFavourite)
    }

    @Test
    fun durationsAndClocks() {
        assertEquals("3:07", clock(187_000))
        assertEquals("1:02:07", clock(3_727_000))
        assertEquals("0:00", clock(-5))
        assertEquals("It is a truth.", "<p>It is a <b>truth</b>.</p>".strippingHtml())
    }
}
