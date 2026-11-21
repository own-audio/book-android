package audio.own.bookplayer.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.edit
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import audio.own.book.library.Book
import audio.own.book.library.BookId
import audio.own.book.library.BookQuery
import audio.own.bookplayer.AppContainer
import audio.own.bookplayer.R
import audio.own.bookplayer.book.BookContent
import audio.own.bookplayer.book.BookViewModel
import audio.own.bookplayer.browse.BooksListContent
import audio.own.bookplayer.browse.BooksListViewModel
import audio.own.bookplayer.browse.CollectionDetailContent
import audio.own.bookplayer.browse.CollectionsContent
import audio.own.bookplayer.browse.LibrarySection
import audio.own.bookplayer.browse.ListViewModel
import audio.own.bookplayer.browse.NowPlaying
import audio.own.bookplayer.browse.PeopleContent
import audio.own.bookplayer.browse.SeriesDetailContent
import audio.own.bookplayer.browse.SeriesListContent
import audio.own.bookplayer.browse.sectionsFor
import audio.own.bookplayer.home.HomeContent
import audio.own.bookplayer.home.HomeViewModel
import audio.own.bookplayer.library.ActiveLibrary
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.bookplayer.search.SearchContent
import audio.own.bookplayer.search.SearchViewModel
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import kotlinx.coroutines.launch

/** Routes inside each tab: the tab's root, then the library pages pushed over it. */
private object TabRoutes {
    const val ROOT = "root"
    const val BOOK = "book/{id}"
    const val BOOKS = "books/{kind}/{id}?title={title}"
    const val PEOPLE = "people/{kind}"
    const val SERIES = "series"
    const val ONE_SERIES = "series/{id}?title={title}"
    const val COLLECTIONS = "collections"
    const val COLLECTION = "collection/{id}"
}

/** Where a tab's pages go; the same for every tab, as iOS's `libraryDestinations()`. */
class LibraryNavigator(private val nav: NavHostController, private val known: MutableMap<String, Book>) {
    fun book(book: Book) {
        known[book.id] = book
        nav.navigate("book/${Uri.encode(book.id)}")
    }

    fun author(id: String, name: String) = nav.navigate("books/author/${Uri.encode(id)}?title=${Uri.encode(name)}")

    fun narrator(id: String, name: String) = nav.navigate("books/narrator/${Uri.encode(id)}?title=${Uri.encode(name)}")

    fun series(id: String, name: String) = nav.navigate("series/${Uri.encode(id)}?title=${Uri.encode(name)}")

    fun section(section: LibrarySection) = nav.navigate(
        when (section) {
            LibrarySection.SERIES -> TabRoutes.SERIES
            LibrarySection.AUTHORS -> "people/authors"
            LibrarySection.NARRATORS -> "people/narrators"
            LibrarySection.COLLECTIONS -> TabRoutes.COLLECTIONS
        },
    )

    fun collection(id: String) = nav.navigate("collection/${Uri.encode(id)}")

    fun back() = nav.popBackStack()

    /** The book as the list had it, so its page shows at once while the detail loads. */
    fun known(id: String): Book? = known[id]
}

/**
 * The four tabs over the active library. Each tab keeps its own page stack while another is
 * shown; switching servers starts every tab over, since their pages belong to the old library,
 * while a book keeps playing.
 */
@Composable
fun LibraryTabs(container: AppContainer, onOpenPlayer: () -> Unit, onSettings: () -> Unit) {
    val servers by container.servers.state.collectAsStateWithLifecycle()
    val active by container.sources.active.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val offlineMode by container.preferences.offlineMode.collectAsStateWithLifecycle(initialValue = false)
    val network by container.network.state.collectAsStateWithLifecycle()
    val offline = offlineMode || !network.connected
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val holder = rememberSaveableStateHolder()
    val known = remember { mutableMapOf<String, Book>() }

    TabsScreen(
        servers = servers.servers,
        activeId = servers.activeId,
        offlineMode = offlineMode,
        selected = tab,
        onSelect = { tab = it },
        banner = {
            if (offline) {
                audio.own.core.designsystem.components.OfflineBanner(
                    stringResource(if (offlineMode) R.string.banner_offline_mode else R.string.banner_no_network),
                    Modifier.padding(bottom = Tokens.Spacing.xs),
                    actionLabel = if (offlineMode) stringResource(audio.own.core.strings.R.string.turn_off) else null,
                    onAction = if (offlineMode) ({ scope.launch { container.preferences.setOfflineMode(false) } }) else null,
                )
            }
        },
        miniPlayer = {
            audio.own.bookplayer.player.MiniPlayer(
                playback,
                onOpen = onOpenPlayer,
                onToggle = container.playback::toggle,
                onSkipForward = container.playback::skipForward,
            )
        },
    ) { current ->
        val library = active
        if (library == null) {
            LoadingScreen()
            return@TabsScreen
        }
        key(library.serverId, library.record.libraryId) {
            holder.SaveableStateProvider("${library.serverId}|${library.record.libraryId}|$current") {
                val nav = rememberNavController()
                val go = remember(nav) { LibraryNavigator(nav, known) }
                TabHost(container, library, current, nav, go, playback, offline, onSettings = onSettings, onSelectServer = { id, lib -> scope.launch { container.servers.select(id, lib) } }, onOfflineMode = { on -> scope.launch { container.preferences.setOfflineMode(on) } })
            }
        }
    }
}

@Composable
private fun TabHost(
    container: AppContainer,
    library: ActiveLibrary,
    tab: Tab,
    nav: NavHostController,
    go: LibraryNavigator,
    playback: PlaybackUi,
    offline: Boolean,
    onSettings: () -> Unit,
    onSelectServer: (String, String?) -> Unit,
    onOfflineMode: (Boolean) -> Unit,
) {
    val source = library.source
    val serverId = library.serverId
    val nowPlaying = NowPlaying(playback.bookId, playback.isPlaying)
    fun arg(entry: NavBackStackEntry, name: String) = entry.arguments?.getString(name).orEmpty()

    NavHost(nav, startDestination = TabRoutes.ROOT) {
        composable(TabRoutes.ROOT) {
            when (tab) {
                Tab.HOME -> {
                    val servers by container.servers.state.collectAsStateWithLifecycle()
                    val offlineMode by container.preferences.offlineMode.collectAsStateWithLifecycle(initialValue = false)
                    val vm = viewModel { HomeViewModel(source) { container.offline.isOffline } }
                    val state by vm.state.collectAsStateWithLifecycle()
                    ReloadOnPause(playback.isPlaying) { vm.reload(quietly = true) }
                    LaunchedEffect(offline) { vm.reload(quietly = true) }
                    LaunchedEffect(vm) { container.playbackModel.progressChanged.collect { vm.reload(quietly = true) } }
                    Scaffold(topBar = { HomeTopBar(servers.servers, servers.activeId, offlineMode, onSelectServer, onOfflineMode, onSettings) }) { inner ->
                        HomeContent(
                            serverId = serverId,
                            serverName = library.name,
                            state = state,
                            offline = offline,
                            onRetry = { vm.reload() },
                            onRefresh = { vm.reload(quietly = true, refreshing = true) },
                            onPlay = { container.playback.play(serverId, it) },
                            onOpenBook = go::book,
                            onOpenSeries = { go.series(it.id, it.name) },
                            onOpenAuthor = { go.author(it.id, it.name) },
                            modifier = Modifier.padding(inner),
                        )
                    }
                }
                Tab.LIBRARY -> LibraryRoot(container, library, go, nowPlaying, offline)
                Tab.DOWNLOADS -> Page(title = stringResource(R.string.tab_downloads), onBack = null, scrolls = false) {
                    EmptyState(Icons.Filled.DownloadForOffline, stringResource(R.string.nothing_downloaded), stringResource(R.string.downloads_later))
                }
                Tab.SEARCH -> {
                    val vm = viewModel { SearchViewModel(source) }
                    val state by vm.state.collectAsStateWithLifecycle()
                    SearchContent(
                        serverId = serverId,
                        state = state,
                        onText = vm::setText,
                        onOpenBook = go::book,
                        onOpenSeries = { go.series(it.id, it.name) },
                        onOpenAuthor = { go.author(it.id, it.name) },
                        onOpenNarrator = { go.narrator(it.id, it.name) },
                    )
                }
            }
        }
        composable(TabRoutes.BOOK) { entry ->
            val id = arg(entry, "id")
            val vm = viewModel {
                BookViewModel(
                    source, id, go.known(id),
                    isOffline = { container.offline.isOffline },
                    markFinished = { book, finished -> container.playbackModel.markFinished(serverId, book, finished) },
                    resetProgress = { book -> container.playbackModel.resetProgress(serverId, book) },
                    onShared = { (source as? audio.own.book.library.ChangeFeedCatalogue)?.let { container.scope.launch { container.sources.sync(it) } } },
                )
            }
            val state by vm.state.collectAsStateWithLifecycle()
            ReloadOnPause(playback.isPlaying) { vm.refresh() }
            LaunchedEffect(vm) { container.playbackModel.progressChanged.collect { vm.refresh() } }
            BookContent(
                serverId = serverId,
                state = state,
                playback = playback,
                onBack = { go.back() },
                onRetry = vm::load,
                onPlay = { container.playback.play(serverId, it) },
                onToggle = container.playback::toggle,
                onPlayFrom = { book, at ->
                    if (playback.bookId == BookId.of(serverId, book.id).value && !playback.restored) {
                        container.playback.seek(at)
                        if (!playback.isPlaying) container.playback.toggle()
                    } else {
                        container.playback.play(serverId, book, at)
                    }
                },
                onOpenSeries = { go.series(it.id, it.name) },
                onDismissError = vm::dismissError,
                menu = audio.own.bookplayer.book.BookMenuActions(
                    onToggleFavourite = vm::toggleFavourite,
                    onShare = vm::setShared,
                    onFinished = vm::setFinished,
                    onResetProgress = vm::resetProgress,
                    onRenameBookmark = vm::renameBookmark,
                    onDeleteBookmark = vm::deleteBookmark,
                ),
            )
        }
        composable(TabRoutes.BOOKS) { entry ->
            val kind = arg(entry, "kind")
            val id = arg(entry, "id")
            val query = if (kind == "narrator") BookQuery(narratorId = id) else BookQuery(authorId = id)
            BooksList(container, library, arg(entry, "title"), query, isRoot = false, go, nowPlaying, offline)
        }
        composable(TabRoutes.PEOPLE) { entry ->
            val narrators = arg(entry, "kind") == "narrators"
            val vm = viewModel { ListViewModel.people(source, narrators) }
            val state by vm.state.collectAsStateWithLifecycle()
            PeopleContent(narrators, state, onBack = { go.back() }, onRetry = vm::reload, onOpen = { if (narrators) go.narrator(it.id, it.name) else go.author(it.id, it.name) })
        }
        composable(TabRoutes.SERIES) {
            val vm = viewModel { ListViewModel.series(source) }
            val state by vm.state.collectAsStateWithLifecycle()
            SeriesListContent(state, onBack = { go.back() }, onRetry = vm::reload, onOpen = { go.series(it.id, it.name) })
        }
        composable(TabRoutes.ONE_SERIES) { entry ->
            val id = arg(entry, "id")
            val vm = viewModel { ListViewModel.oneSeries(source, id) }
            val state by vm.state.collectAsStateWithLifecycle()
            SeriesDetailContent(serverId, arg(entry, "title"), state, onBack = { go.back() }, onRetry = vm::reload, onOpenBook = go::book, nowPlaying = nowPlaying)
        }
        composable(TabRoutes.COLLECTIONS) {
            val vm = viewModel { ListViewModel.collections(source) }
            val state by vm.state.collectAsStateWithLifecycle()
            CollectionsContent(state, onBack = { go.back() }, onRetry = vm::reload, onOpen = { go.collection(it.id) })
        }
        composable(TabRoutes.COLLECTION) { entry ->
            val id = arg(entry, "id")
            val vm = viewModel { ListViewModel.collection(source, id) }
            val state by vm.state.collectAsStateWithLifecycle()
            CollectionDetailContent(serverId, state, onBack = { go.back() }, onRetry = vm::reload, onOpenBook = go::book)
        }
    }
}

/** A pause moves a book up Continue Listening and changes its progress: read again. */
@Composable
private fun ReloadOnPause(isPlaying: Boolean, reload: () -> Unit) {
    var was by remember { mutableStateOf(isPlaying) }
    LaunchedEffect(isPlaying) {
        if (was && !isPlaying) reload()
        was = isPlaying
    }
}

@Composable
private fun BooksList(
    container: AppContainer,
    library: ActiveLibrary,
    title: String,
    query: BookQuery,
    isRoot: Boolean,
    go: LibraryNavigator,
    nowPlaying: NowPlaying,
    offline: Boolean,
    onBack: (() -> Unit)? = { go.back() },
    showsSections: Boolean = isRoot,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("library", Context.MODE_PRIVATE) }
    val vm = viewModel(key = "books|$isRoot|$query") {
        BooksListViewModel(library.source, query, grid = prefs.getBoolean("grid", true), onGrid = { on -> prefs.edit { putBoolean("grid", on) } })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    BooksListContent(
        title = title,
        serverId = library.serverId,
        state = state,
        capabilities = vm.capabilities,
        onBack = onBack,
        onOpenBook = go::book,
        onLoadMore = vm::loadMore,
        onRetry = { vm.reload() },
        onRefresh = { vm.reload(refreshing = true) },
        onSort = vm::setSort,
        onDescending = vm::setDescending,
        onFilter = vm::setFilter,
        onGrid = vm::setGrid,
        sections = if (showsSections) sectionsFor(vm.capabilities) else emptyList(),
        onSection = go::section,
        offline = offline,
        nowPlaying = nowPlaying,
    )
}

/**
 * The Library tab: on a phone the books with the section chips; on a wide screen a sidebar of
 * All Books, the sections and Downloads beside the open one.
 */
@Composable
private fun LibraryRoot(container: AppContainer, library: ActiveLibrary, go: LibraryNavigator, nowPlaying: NowPlaying, offline: Boolean) {
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 840.dp
    if (!wide) {
        BooksList(container, library, stringResource(R.string.tab_library), BookQuery(), isRoot = true, go, nowPlaying, offline, onBack = null)
        return
    }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    val palette = LocalPalette.current
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(260.dp).fillMaxHeight().padding(Tokens.Spacing.sm)) {
            Text(stringResource(R.string.tab_library), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(Tokens.Spacing.md))
            val items = listOf<Pair<String?, Pair<String, androidx.compose.ui.graphics.vector.ImageVector>>>(null to (stringResource(R.string.all_books) to Icons.AutoMirrored.Filled.LibraryBooks)) +
                sectionsFor(library.source.capabilities).map { it.name to (stringResource(it.title) to it.icon) } +
                listOf("DOWNLOADS" to (stringResource(R.string.tab_downloads) to Icons.Filled.DownloadForOffline))
            items.forEach { (key, value) ->
                NavigationDrawerItem(
                    label = { Text(value.first) },
                    icon = { Icon(value.second, null) },
                    selected = selection == key,
                    onClick = { selection = key },
                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = palette.background),
                )
            }
        }
        VerticalDivider(color = palette.border)
        Box(Modifier.weight(1f)) {
            key(selection) {
                when (selection) {
                    null -> BooksList(container, library, stringResource(R.string.all_books), BookQuery(), isRoot = true, go, nowPlaying, offline, onBack = null, showsSections = false)
                    "DOWNLOADS" -> Page(title = stringResource(R.string.tab_downloads), onBack = null, scrolls = false) {
                        EmptyState(Icons.Filled.DownloadForOffline, stringResource(R.string.nothing_downloaded), stringResource(R.string.downloads_later))
                    }
                    LibrarySection.SERIES.name -> {
                        val vm = viewModel(key = "sidebar-series") { ListViewModel.series(library.source) }
                        val state by vm.state.collectAsStateWithLifecycle()
                        SeriesListContent(state, onBack = null, onRetry = vm::reload, onOpen = { go.series(it.id, it.name) })
                    }
                    LibrarySection.COLLECTIONS.name -> {
                        val vm = viewModel(key = "sidebar-collections") { ListViewModel.collections(library.source) }
                        val state by vm.state.collectAsStateWithLifecycle()
                        CollectionsContent(state, onBack = null, onRetry = vm::reload, onOpen = { go.collection(it.id) })
                    }
                    else -> {
                        val narrators = selection == LibrarySection.NARRATORS.name
                        val vm = viewModel(key = "sidebar-$selection") { ListViewModel.people(library.source, narrators) }
                        val state by vm.state.collectAsStateWithLifecycle()
                        PeopleContent(narrators, state, onBack = null, onRetry = vm::reload, onOpen = { if (narrators) go.narrator(it.id, it.name) else go.author(it.id, it.name) })
                    }
                }
            }
        }
    }
}
