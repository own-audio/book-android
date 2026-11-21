package audio.own.bookplayer.home

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.book.library.Book
import audio.own.book.library.BookSource
import audio.own.book.library.Capability
import audio.own.book.library.ListeningProgress
import audio.own.book.library.Person
import audio.own.book.library.SeriesSummary
import audio.own.book.library.Shelf
import audio.own.bookplayer.R
import audio.own.bookplayer.app.Load
import audio.own.bookplayer.app.forView
import audio.own.bookplayer.browse.BookCard
import audio.own.bookplayer.browse.BookCover
import audio.own.bookplayer.browse.Gap
import audio.own.bookplayer.browse.SectionTitle
import audio.own.bookplayer.browse.booksCount
import audio.own.bookplayer.browse.sourceMessage
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val shelves: Load<List<Shelf>> = Load.Loading,
    val progress: Map<String, ListeningProgress> = emptyMap(),
    val favourites: Set<String> = emptySet(),
    val refreshing: Boolean = false,
)

/** The shelves of the active server; offline, what is on the phone (nothing until downloads, B6). */
class HomeViewModel(private val source: BookSource?, private val isOffline: () -> Boolean) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        reload()
    }

    /** [quietly]: keep what is shown while the new shelves load, and keep it on a failure. */
    fun reload(quietly: Boolean = false, refreshing: Boolean = false) {
        val source = source ?: return
        if (isOffline()) {
            _state.update { it.copy(shelves = Load.Loaded(emptyList()), refreshing = false) }
            return
        }
        if (!quietly && _state.value.shelves !is Load.Loaded) _state.update { it.copy(shelves = Load.Loading) }
        if (refreshing) _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val shelves = source.shelves().filterNot { it.isEmpty }
                val progress = runCatching { source.allProgress() }.getOrDefault(_state.value.progress)
                val favourites = if (Capability.FAVOURITES in source.capabilities) runCatching { source.favourites() }.getOrDefault(emptySet()) else emptySet()
                _state.update { HomeState(Load.Loaded(shelves), progress, favourites) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (quietly && it.shelves is Load.Loaded) it.copy(refreshing = false) else it.copy(shelves = Load.Failed(e.sourceMessage()), refreshing = false)
                }
            }
        }
    }
}

@StringRes
fun greetingFor(hour: Int): Int = when (hour) {
    in 5..11 -> R.string.good_morning
    in 12..17 -> R.string.good_afternoon
    else -> R.string.good_evening
}

/** The screen's own words for the shelves it knows; the server's for the others. */
@Composable
fun shelfTitle(shelf: Shelf): String = when (shelf.id) {
    "continue-listening" -> stringResource(R.string.shelf_continue_listening)
    "recently-added" -> stringResource(R.string.shelf_recently_added)
    "listen-again" -> stringResource(R.string.shelf_listen_again)
    "downloaded" -> stringResource(R.string.shelf_on_this_device)
    else -> shelf.title
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    serverId: String?,
    serverName: String?,
    state: HomeState,
    offline: Boolean,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Book) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (SeriesSummary) -> Unit,
    onOpenAuthor: (Person) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    hour: Int = LocalTime.now().hour,
) {
    val palette = LocalPalette.current
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Tokens.Spacing.lg, bottom = Tokens.Spacing.lg + contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl),
        ) {
            item {
                Column(Modifier.padding(horizontal = Tokens.Spacing.screenEdge), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                    Text(stringResource(greetingFor(hour)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = palette.foreground)
                    Text(
                        if (serverName != null) stringResource(R.string.greeting_server, serverName) else stringResource(R.string.greeting_no_server),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted,
                    )
                }
            }
            item {
                LoadStateView(state.shelves.forView(), onRetry = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) { shelves ->
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl)) {
                        if (shelves.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(top = 48.dp)) {
                                if (offline) {
                                    EmptyState(Icons.Filled.WifiOff, stringResource(R.string.nothing_downloaded), stringResource(R.string.nothing_downloaded_offline))
                                } else {
                                    EmptyState(Icons.AutoMirrored.Filled.LibraryBooks, stringResource(R.string.no_books_yet), stringResource(R.string.library_empty_server))
                                }
                            }
                        }
                        if (serverId != null) {
                            shelves.forEach { shelf -> ShelfRow(serverId, shelf, state, onPlay, onOpenBook, onOpenSeries, onOpenAuthor) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    serverId: String,
    shelf: Shelf,
    state: HomeState,
    onPlay: (Book) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (SeriesSummary) -> Unit,
    onOpenAuthor: (Person) -> Unit,
) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
        SectionTitle(shelfTitle(shelf))
        LazyRow(
            contentPadding = PaddingValues(horizontal = Tokens.Spacing.screenEdge),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg),
        ) {
            when (val content = shelf.content) {
                is Shelf.Content.Books -> items(content.books, key = { it.id }) { book ->
                    if (shelf.id == CONTINUE_LISTENING) {
                        PlayingCard(serverId, book, state, onPlay = { onPlay(book) }, onShow = { onOpenBook(book) })
                    } else {
                        BookCard(
                            serverId, book, state.progress[book.id],
                            Modifier.clickable { onOpenBook(book) },
                            isFavourite = book.id in state.favourites,
                        )
                    }
                }
                is Shelf.Content.Series -> items(content.series, key = { it.id }) { series ->
                    Column(Modifier.width(140.dp).clickable { onOpenSeries(series) }, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                        val first = series.books?.firstOrNull()
                        if (first != null) {
                            BookCover(serverId, first, 140.dp)
                        } else {
                            Box(Modifier.size(140.dp).background(palette.backgroundAlt, RoundedCornerShape(AppBrand.radii.cover)))
                        }
                        Text(series.name, style = MaterialTheme.typography.titleSmall, color = palette.foreground, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(booksCount(series.bookCount), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                    }
                }
                is Shelf.Content.Authors -> items(content.authors, key = { it.id }) { person ->
                    Column(Modifier.width(120.dp).clickable { onOpenAuthor(person) }, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.AccountCircle, null, tint = palette.muted, modifier = Modifier.size(72.dp))
                        Gap(Tokens.Spacing.sm)
                        Text(person.name, style = MaterialTheme.typography.bodyMedium, color = palette.foreground, maxLines = 2, minLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** On Continue Listening a tap plays; a long press offers the book's page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayingCard(serverId: String, book: Book, state: HomeState, onPlay: () -> Unit, onShow: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        BookCard(
            serverId, book, state.progress[book.id],
            Modifier.combinedClickable(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onPlay() },
                onLongClick = { menu = true },
                onLongClickLabel = stringResource(R.string.show_book),
            ),
            isFavourite = book.id in state.favourites,
            playOverlay = true,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.show_book)) }, onClick = { menu = false; onShow() })
        }
    }
}

const val CONTINUE_LISTENING = "continue-listening"
