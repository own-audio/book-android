package audio.own.bookplayer.browse

import audio.own.bookplayer.downloads.SetDownloadButton

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import audio.own.book.library.Book
import audio.own.book.library.BookCollection
import audio.own.book.library.BookFilter
import audio.own.book.library.BookSort
import audio.own.book.library.Capability
import audio.own.book.library.ListeningProgress
import audio.own.book.library.Person
import audio.own.book.library.SeriesSummary
import audio.own.bookplayer.R
import audio.own.bookplayer.app.Load
import audio.own.bookplayer.app.forView
import audio.own.bookplayer.ui.Page
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular

/** The ways into the library besides the books themselves. */
enum class LibrarySection(@StringRes val title: Int, val icon: ImageVector) {
    SERIES(R.string.series, Icons.Filled.Layers),
    AUTHORS(R.string.authors, Icons.Filled.People),
    NARRATORS(R.string.narrators, Icons.Filled.GraphicEq),
    COLLECTIONS(R.string.collections, Icons.Filled.ViewCarousel),
}

/** The sections a server has: series and collections only where it keeps them. */
fun sectionsFor(capabilities: Set<Capability>): List<LibrarySection> = buildList {
    if (Capability.SERIES in capabilities) add(LibrarySection.SERIES)
    add(LibrarySection.AUTHORS)
    add(LibrarySection.NARRATORS)
    if (Capability.COLLECTIONS in capabilities) add(LibrarySection.COLLECTIONS)
}

@StringRes
fun emptyTextFor(filter: BookFilter): Int = when (filter) {
    BookFilter.ALL -> R.string.empty_all
    BookFilter.NOT_STARTED -> R.string.empty_not_started
    BookFilter.IN_PROGRESS -> R.string.empty_in_progress
    BookFilter.FINISHED -> R.string.empty_finished
    BookFilter.FAVOURITES -> R.string.empty_favorites
}

/** What a list needs to know about playback: which book plays, to mark its row. */
data class NowPlaying(val bookId: String? = null, val isPlaying: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksListContent(
    title: String,
    serverId: String,
    state: BooksListState,
    capabilities: Set<Capability>,
    onBack: (() -> Unit)?,
    onOpenBook: (Book) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onSort: (BookSort) -> Unit,
    onDescending: (Boolean) -> Unit,
    onFilter: (BookFilter) -> Unit,
    onGrid: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sections: List<LibrarySection> = emptyList(),
    onSection: (LibrarySection) -> Unit = {},
    offline: Boolean = false,
    nowPlaying: NowPlaying = NowPlaying(),
    contentPadding: PaddingValues = PaddingValues(),
    /** Every book of the query, for Download All on a list that isn't the whole library; null hides it. */
    allBooks: (suspend () -> List<Book>)? = null,
) {
    Page(
        title = title,
        onBack = onBack,
        modifier = modifier,
        scrolls = false,
        actions = {
            if (allBooks != null) (state.books as? Load.Loaded)?.value?.let { SetDownloadButton(serverId, it, allBooks) }
            SortFilterMenu(state, Capability.FAVOURITES in capabilities, onSort, onDescending, onFilter, onGrid)
        },
    ) {
        val bottom = contentPadding.calculateBottomPadding() + Tokens.Spacing.lg
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            val books = state.books
            val header: @Composable () -> Unit = {
                if (sections.isNotEmpty()) SectionChips(sections, onSection)
            }
            when {
                books !is Load.Loaded -> Column(Modifier.fillMaxSize()) {
                    header()
                    LoadStateView(books.forView(), onRetry = onRetry) {}
                }
                books.value.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    header()
                    EmptyState(Icons.AutoMirrored.Filled.LibraryBooks, stringResource(R.string.no_books), stringResource(emptyTextFor(state.query.filter)))
                }
                state.grid -> Column(Modifier.fillMaxSize()) {
                header()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Tokens.Spacing.screenEdge, end = Tokens.Spacing.screenEdge, top = Tokens.Spacing.md, bottom = bottom),
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
                ) {
                    itemsIndexed(books.value, key = { _, b -> b.id }) { index, book ->
                        if (index == books.value.lastIndex) LaunchedEffect(book.id) { onLoadMore() }
                        BoxWithConstraints {
                            BookCard(
                                serverId, book, state.progress[book.id],
                                Modifier.clickable { onOpenBook(book) },
                                width = maxWidth.coerceAtMost(200.dp),
                                isFavourite = book.id in state.favourites,
                                dimmed = offline,
                            )
                        }
                    }
                    if (state.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { Spinner() }
                }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
                    item { header() }
                    state.total?.let { total ->
                        item {
                            Text(
                                booksCount(total).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalPalette.current.muted,
                                modifier = Modifier.padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
                            )
                        }
                    }
                    itemsIndexed(books.value, key = { _, b -> b.id }) { index, book ->
                        if (index == books.value.lastIndex) LaunchedEffect(book.id) { onLoadMore() }
                        BookRow(
                            serverId, book, state.progress[book.id],
                            Modifier.clickable { onOpenBook(book) },
                            nowPlaying = nowPlaying.bookId == audio.own.book.library.BookId.of(serverId, book.id).value,
                            isPlaying = nowPlaying.isPlaying,
                            dimmed = offline,
                        )
                        HorizontalDivider(Modifier.padding(start = 84.dp), color = LocalPalette.current.border)
                    }
                    if (state.loadingMore) item { Spinner() }
                }
            }
        }
    }
}

@Composable
private fun Spinner() = Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
fun SectionChips(sections: List<LibrarySection>, onSection: (LibrarySection) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
    ) {
        items(sections) { section ->
            AssistChip(
                onClick = { onSection(section) },
                label = { Text(stringResource(section.title)) },
                leadingIcon = { Icon(section.icon, null, Modifier.padding(0.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = LocalPalette.current.backgroundAlt, labelColor = LocalPalette.current.foreground),
                border = null,
            )
        }
    }
}

@Composable
private fun SortFilterMenu(
    state: BooksListState,
    hasFavourites: Boolean,
    onSort: (BookSort) -> Unit,
    onDescending: (Boolean) -> Unit,
    onFilter: (BookFilter) -> Unit,
    onGrid: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.FilterList, stringResource(R.string.sort_and_filter)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun check(on: Boolean) = if (on) Icon(Icons.Filled.Check, null) else Box(Modifier.padding(12.dp))
            MenuHeader(stringResource(R.string.sort))
            listOf(BookSort.TITLE to R.string.sort_title, BookSort.AUTHOR to R.string.sort_author, BookSort.ADDED to R.string.sort_added, BookSort.DURATION to R.string.sort_length)
                .forEach { (sort, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { check(state.query.sort == sort) }, onClick = { onSort(sort) })
                }
            DropdownMenuItem(text = { Text(stringResource(R.string.descending)) }, leadingIcon = { check(state.query.descending) }, onClick = { onDescending(!state.query.descending) })
            HorizontalDivider(color = palette.border)
            MenuHeader(stringResource(R.string.show))
            buildList {
                add(BookFilter.ALL to R.string.filter_all)
                add(BookFilter.NOT_STARTED to R.string.filter_not_started)
                add(BookFilter.IN_PROGRESS to R.string.filter_in_progress)
                add(BookFilter.FINISHED to R.string.filter_finished)
                if (hasFavourites) add(BookFilter.FAVOURITES to R.string.filter_favorites)
            }.forEach { (filter, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { check(state.query.filter == filter) }, onClick = { onFilter(filter); open = false })
            }
            HorizontalDivider(color = palette.border)
            DropdownMenuItem(text = { Text(stringResource(R.string.grid)) }, leadingIcon = { check(state.grid) }, onClick = { onGrid(!state.grid); open = false })
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.muted, modifier = Modifier.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm))
}

/** A row with a name and a count, for people, series and collections. */
@Composable
fun NameCountRow(name: String, count: Int?, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable(onClick = onClick).padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = palette.foreground, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("$count", color = palette.muted, style = MaterialTheme.typography.bodyMedium.tabular())
    }
    HorizontalDivider(Modifier.padding(start = Tokens.Spacing.screenEdge), color = palette.border)
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, color = LocalPalette.current.muted, modifier = Modifier.padding(Tokens.Spacing.screenEdge))
}

/** Authors or narrators, each with their book count, and a filter field. */
@Composable
fun PeopleContent(
    narrators: Boolean,
    state: Load<List<Person>>,
    onBack: (() -> Unit)?,
    onRetry: () -> Unit,
    onOpen: (Person) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var filter by rememberSaveable { mutableStateOf("") }
    Page(title = stringResource(if (narrators) R.string.narrators else R.string.authors), onBack = onBack, scrolls = false) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text(stringResource(R.string.filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
        )
        LoadStateView(state.forView(), onRetry = onRetry) { people ->
            val shown = if (filter.isBlank()) people else people.filter { it.name.contains(filter.trim(), ignoreCase = true) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                if (shown.isEmpty()) item { EmptyLine(stringResource(R.string.nobody_here_yet)) }
                items(shown, key = { it.id }) { person -> NameCountRow(person.name, person.bookCount) { onOpen(person) } }
            }
        }
    }
}

@Composable
fun SeriesListContent(state: Load<List<SeriesSummary>>, onBack: (() -> Unit)?, onRetry: () -> Unit, onOpen: (SeriesSummary) -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    Page(title = stringResource(R.string.series), onBack = onBack, scrolls = false) {
        LoadStateView(state.forView(), onRetry = onRetry) { series ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                if (series.isEmpty()) item { EmptyLine(stringResource(R.string.no_series_yet)) }
                items(series, key = { it.id }) { one -> NameCountRow(one.name, one.bookCount) { onOpen(one) } }
            }
        }
    }
}

/** A series' books in series order, with the number in the series in front. */
@Composable
fun SeriesDetailContent(
    serverId: String,
    title: String,
    state: Load<Pair<SeriesSummary, Map<String, ListeningProgress>>>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenBook: (Book) -> Unit,
    nowPlaying: NowPlaying = NowPlaying(),
    contentPadding: PaddingValues = PaddingValues(),
) {
    val loaded = (state as? Load.Loaded)?.value
    Page(
        title = loaded?.first?.name ?: title,
        onBack = onBack,
        scrolls = false,
        actions = { loaded?.first?.books?.let { SetDownloadButton(serverId, it) } },
    ) {
        LoadStateView(state.forView(), onRetry = onRetry) { (series, progress) ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                items(series.books.orEmpty(), key = { it.id }) { book ->
                    BookRow(
                        serverId, book, progress[book.id], Modifier.clickable { onOpenBook(book) },
                        nowPlaying = nowPlaying.bookId == audio.own.book.library.BookId.of(serverId, book.id).value,
                        isPlaying = nowPlaying.isPlaying,
                        leading = book.series.firstOrNull { it.id == series.id }?.sequence ?: "",
                    )
                }
            }
        }
    }
}

@Composable
fun CollectionsContent(state: Load<List<BookCollection>>, onBack: (() -> Unit)?, onRetry: () -> Unit, onOpen: (BookCollection) -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    Page(title = stringResource(R.string.collections), onBack = onBack, scrolls = false) {
        LoadStateView(state.forView(), onRetry = onRetry) { collections ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                if (collections.isEmpty()) item { EmptyLine(stringResource(R.string.no_collections_yet)) }
                items(collections, key = { it.id }) { one -> NameCountRow(one.name, one.books.size) { onOpen(one) } }
            }
        }
    }
}

@Composable
fun CollectionDetailContent(
    serverId: String,
    state: Load<Pair<BookCollection, Map<String, ListeningProgress>>>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenBook: (Book) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val loaded = (state as? Load.Loaded)?.value
    Page(
        title = loaded?.first?.name ?: stringResource(R.string.collections),
        onBack = onBack,
        scrolls = false,
        actions = { loaded?.first?.books?.let { SetDownloadButton(serverId, it) } },
    ) {
        LoadStateView(state.forView(), onRetry = onRetry) { (collection, progress) ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                items(collection.books, key = { it.id }) { book -> BookRow(serverId, book, progress[book.id], Modifier.clickable { onOpenBook(book) }) }
            }
        }
    }
}
