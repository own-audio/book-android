package audio.own.bookplayer.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.book.library.Book
import audio.own.book.library.BookCollection
import audio.own.book.library.BookFilter
import audio.own.book.library.BookQuery
import audio.own.book.library.BookSort
import audio.own.book.library.BookSource
import audio.own.book.library.Capability
import audio.own.book.library.ListeningProgress
import audio.own.book.library.Person
import audio.own.book.library.SeriesSummary
import audio.own.bookplayer.app.Load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BooksListState(
    val query: BookQuery = BookQuery(),
    val books: Load<List<Book>> = Load.Loading,
    val total: Int? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val progress: Map<String, ListeningProgress> = emptyMap(),
    val favourites: Set<String> = emptySet(),
    val grid: Boolean = true,
)

/**
 * One query's books, 60 a page as the listener scrolls (book-ios `BooksListView`): the root
 * Library, an author's, a narrator's or a series' books.
 */
class BooksListViewModel(
    private val source: BookSource?,
    query: BookQuery = BookQuery(),
    grid: Boolean = true,
    private val onGrid: (Boolean) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(BooksListState(query = query, grid = grid))
    val state: StateFlow<BooksListState> = _state.asStateFlow()
    private var next: String? = null
    private var loading: Job? = null

    val capabilities: Set<Capability> get() = source?.capabilities.orEmpty()

    private var listing: Any? = null

    /**
     * True when the list was loaded for another [now] (online, the downloads, or offline the whole
     * catalogue) and has to load again. Kept here, not on the screen: the screen is gone while
     * Settings is open, where Offline Mode and Whole Library are switched.
     */
    fun seesListing(now: Any): Boolean {
        val changed = listing != null && listing != now
        listing = now
        return changed
    }

    init {
        reload()
    }

    fun setSort(sort: BookSort) = setQuery(_state.value.query.copy(sort = sort))

    fun setDescending(on: Boolean) = setQuery(_state.value.query.copy(descending = on))

    fun setFilter(filter: BookFilter) = setQuery(_state.value.query.copy(filter = filter))

    fun setGrid(on: Boolean) {
        _state.update { it.copy(grid = on) }
        onGrid(on)
    }

    private fun setQuery(query: BookQuery) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        reload()
    }

    fun reload(refreshing: Boolean = false) {
        val source = source ?: return
        loading?.cancel()
        _state.update { it.copy(books = if (it.books is Load.Loaded && refreshing) it.books else Load.Loading, refreshing = refreshing, loadingMore = false) }
        val query = _state.value.query
        loading = viewModelScope.launch {
            try {
                val page = source.books(query, null, PAGE_SIZE)
                next = page.next
                val progress = runCatching { source.allProgress() }.getOrDefault(_state.value.progress)
                val favourites = if (Capability.FAVOURITES in source.capabilities) runCatching { source.favourites() }.getOrDefault(emptySet()) else emptySet()
                _state.update {
                    it.copy(books = Load.Loaded(page.books), total = page.total, hasMore = page.next != null, refreshing = false, progress = progress, favourites = favourites)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(books = Load.Failed(e.sourceMessage()), refreshing = false) }
            }
        }
    }

    /** The next page, asked for when the last book comes into view. */
    fun loadMore() {
        val source = source ?: return
        val cursor = next ?: return
        val shown = (_state.value.books as? Load.Loaded)?.value ?: return
        if (_state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        val query = _state.value.query
        viewModelScope.launch {
            try {
                val page = source.books(query, cursor, PAGE_SIZE)
                next = page.next
                val known = shown.map { it.id }.toSet()
                _state.update { it.copy(books = Load.Loaded(shown + page.books.filter { b -> b.id !in known }), hasMore = page.next != null, loadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 60
    }
}

/** A list read once from the source, with a retry: people, series, collections. */
class ListViewModel<T>(private val load: suspend () -> T) : ViewModel() {
    private val _state = MutableStateFlow<Load<T>>(Load.Loading)
    val state: StateFlow<Load<T>> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _state.value = Load.Loading
        viewModelScope.launch {
            _state.value = try {
                Load.Loaded(load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e.sourceMessage())
            }
        }
    }

    companion object {
        fun people(source: BookSource?, narrators: Boolean) = ListViewModel<List<Person>> {
            val s = source ?: throw audio.own.book.library.SourceException.NotFound
            if (narrators) s.narrators() else s.authors()
        }

        fun series(source: BookSource?) = ListViewModel<List<SeriesSummary>> { (source ?: throw audio.own.book.library.SourceException.NotFound).series() }

        /** One series with its books in order, and the progress to show on them. */
        fun oneSeries(source: BookSource?, id: String) = ListViewModel<Pair<SeriesSummary, Map<String, ListeningProgress>>> {
            val s = source ?: throw audio.own.book.library.SourceException.NotFound
            s.series(id) to runCatching { s.allProgress() }.getOrDefault(emptyMap())
        }

        fun collections(source: BookSource?) = ListViewModel<List<BookCollection>> { (source ?: throw audio.own.book.library.SourceException.NotFound).collections() }

        fun collection(source: BookSource?, id: String) = ListViewModel<Pair<BookCollection, Map<String, ListeningProgress>>> {
            val s = source ?: throw audio.own.book.library.SourceException.NotFound
            val found = s.collections().firstOrNull { it.id == id } ?: throw audio.own.book.library.SourceException.NotFound
            found to runCatching { s.allProgress() }.getOrDefault(emptyMap())
        }
    }
}
