package audio.own.bookplayer.book

import audio.own.bookplayer.browse.percent
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.book.library.Book
import audio.own.book.library.BookId
import audio.own.book.library.BookSharing
import audio.own.book.library.BookSource
import audio.own.book.library.Bookmark
import audio.own.book.library.Capability
import audio.own.book.library.Chapter
import audio.own.book.library.ListeningProgress
import audio.own.book.library.SeriesRef
import audio.own.book.library.Visibility
import audio.own.bookplayer.R
import audio.own.bookplayer.app.Load
import audio.own.bookplayer.app.UiText
import audio.own.bookplayer.app.forView
import audio.own.bookplayer.app.text
import audio.own.bookplayer.browse.BookCover
import audio.own.bookplayer.browse.SectionTitle
import audio.own.bookplayer.browse.clock
import audio.own.bookplayer.browse.lengthText
import audio.own.bookplayer.browse.progressFraction
import audio.own.bookplayer.browse.progressSummary
import audio.own.bookplayer.browse.sourceMessage
import audio.own.bookplayer.browse.strippingHtml
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.bookplayer.player.BookmarkActions
import audio.own.bookplayer.player.BookmarkRow
import audio.own.bookplayer.ui.Page
import audio.own.core.designsystem.components.ErrorAlert
import audio.own.core.designsystem.components.LoadStateView
import audio.own.core.designsystem.components.NowPlayingIndicator
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import audio.own.core.strings.R as SR

data class BookState(
    val book: Load<Book> = Load.Loading,
    val progress: ListeningProgress? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val isFavourite: Boolean = false,
    val canFavourite: Boolean = false,
    /** The listener's own book on a server with households: Share with the Family / Keep to Myself. */
    val canShare: Boolean = false,
    /** Bookmarks are online only. */
    val offline: Boolean = false,
    val error: UiText? = null,
)

/**
 * One book with its files and chapters, where the listener is, and their bookmarks; the book
 * menu: favourite, share, finished, reset. [markFinished] and [resetProgress] go through playback,
 * which lets go of the book first (book-ios `AppModel.markFinished`, `clearProgress`).
 */
class BookViewModel(
    private val source: BookSource?,
    private val bookId: String,
    known: Book? = null,
    private val isOffline: () -> Boolean = { false },
    private val markFinished: suspend (Book, Boolean) -> Unit = { book, finished ->
        source?.saveProgress(book.id, if (finished) book.durationMs else 0, book.durationMs, finished, java.time.Instant.now())
    },
    private val resetProgress: suspend (Book) -> Unit = { book -> source?.clearProgress(book.id) },
    /** After a change of who sees the book: the catalogue catches up. */
    private val onShared: () -> Unit = {},
    /**
     * Where the listener is as the phone knows it, without asking the server: this phone's own
     * position and the catalogue's copy, the newer of them. Shown at once, kept when the server
     * can't be asked, and taken over the server's when it is newer (not sent yet).
     */
    private val keptProgress: suspend () -> ListeningProgress? = { null },
) : ViewModel() {
    private val _state = MutableStateFlow(
        BookState(book = known?.let { Load.Loaded(it) } ?: Load.Loading, canFavourite = Capability.FAVOURITES in source?.capabilities.orEmpty()).withSharing(known),
    )
    val state: StateFlow<BookState> = _state.asStateFlow()

    private val book: Book? get() = (_state.value.book as? Load.Loaded)?.value

    init {
        load()
    }

    private fun BookState.withSharing(book: Book?): BookState =
        copy(canShare = book != null && (source as? BookSharing)?.canShare(book) == true && Capability.FAMILY_SHARING in source.capabilities)

    fun load() {
        val source = source ?: return
        viewModelScope.launch {
            // A book opened before anything played (from Search, say) shows its place before the server answers.
            if (_state.value.progress == null) {
                val kept = keptProgressOrNull()
                if (kept != null) _state.update { it.copy(progress = it.progress ?: kept) }
            }
            val shown = book
            if (shown?.files == null) {
                try {
                    val detail = source.book(bookId)
                    _state.update { it.copy(book = Load.Loaded(detail)).withSharing(detail) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (shown == null) _state.update { it.copy(book = Load.Failed(e.sourceMessage())) }
                }
            }
            refresh()
        }
    }

    private suspend fun keptProgressOrNull(): ListeningProgress? = try {
        keptProgress()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun newer(a: ListeningProgress?, b: ListeningProgress?): ListeningProgress? = when {
        a == null -> b
        b == null -> a
        b.updatedAt.isAfter(a.updatedAt) -> b
        else -> a
    }

    private var bookmarkEdit: Int? = null

    /**
     * True when [edit] (how many times the player changed this book's bookmarks) is newer than
     * what this page last saw: then it reads them again. The first count seen is the baseline,
     * as the page has just read them.
     */
    fun seesBookmarkEdit(edit: Int): Boolean {
        val newer = bookmarkEdit != null && bookmarkEdit != edit
        bookmarkEdit = edit
        return newer
    }

    /** Where the listener is, their bookmarks and favourite; after playing or a change. */
    fun refresh() {
        val source = source ?: return
        viewModelScope.launch {
            val server = try {
                Result.success(source.progress(bookId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val kept = keptProgressOrNull()
            val progress = if (server.isSuccess) {
                newer(server.getOrNull(), kept)
            } else {
                newer(_state.value.progress, kept)
            }
            val offline = isOffline()
            _state.update { it.copy(progress = progress, offline = offline) }
            if (offline) return@launch
            val bookmarks = runCatching { source.bookmarks(bookId).sortedBy { it.positionMs } }.getOrNull()
            val favourite = if (_state.value.canFavourite) runCatching { bookId in source.favourites() }.getOrNull() else null
            _state.update { it.copy(bookmarks = bookmarks ?: it.bookmarks, isFavourite = favourite ?: it.isFavourite) }
        }
    }

    fun toggleFavourite() {
        val source = source ?: return
        val wanted = !_state.value.isFavourite
        _state.update { it.copy(isFavourite = wanted) }
        perform(onFailure = { _state.update { it.copy(isFavourite = !wanted) } }) { source.setFavourite(bookId, wanted) }
    }

    /** The listener's own book shared with the family or kept to themselves; put back if the server refuses. */
    fun setShared(shared: Boolean) {
        val sharing = source as? BookSharing ?: return
        val before = book ?: return
        _state.update { it.copy(book = Load.Loaded(before.copy(visibility = if (shared) Visibility.FAMILY else Visibility.PRIVATE))) }
        perform(onFailure = { _state.update { it.copy(book = Load.Loaded(before)) } }) {
            sharing.setShared(bookId, shared)
            onShared()
        }
    }

    fun setFinished(finished: Boolean) {
        val book = book ?: return
        perform {
            markFinished(book, finished)
            refresh()
        }
    }

    fun resetProgress() {
        val book = book ?: return
        perform {
            resetProgress(book)
            _state.update { it.copy(progress = null) }
            refresh()
        }
    }

    fun renameBookmark(bookmark: Bookmark, label: String) {
        val source = source ?: return
        perform {
            val renamed = source.renameBookmark(bookmark, label.trim().ifEmpty { null })
            _state.update { s -> s.copy(bookmarks = s.bookmarks.map { if (it.id == bookmark.id) renamed else it }) }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        val source = source ?: return
        val before = _state.value.bookmarks
        _state.update { s -> s.copy(bookmarks = s.bookmarks.filterNot { it.id == bookmark.id }) }
        perform(onFailure = { _state.update { it.copy(bookmarks = before) } }) { source.deleteBookmark(bookmark) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun perform(onFailure: () -> Unit = {}, work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure()
                _state.update { it.copy(error = e.sourceMessage()) }
            }
        }
    }
}

/** The book page's menu and its bookmark rows. */
data class BookMenuActions(
    val onToggleFavourite: () -> Unit = {},
    val onShare: (Boolean) -> Unit = {},
    val onFinished: (Boolean) -> Unit = {},
    val onResetProgress: () -> Unit = {},
    val onRenameBookmark: (Bookmark, String) -> Unit = { _, _ -> },
    val onDeleteBookmark: (Bookmark) -> Unit = {},
)

private enum class BookTab { CHAPTERS, FILES, BOOKMARKS }

@Composable
fun BookContent(
    serverId: String,
    state: BookState,
    playback: PlaybackUi,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (Book) -> Unit,
    onToggle: () -> Unit,
    onPlayFrom: (Book, Long) -> Unit,
    onOpenSeries: (SeriesRef) -> Unit,
    onDismissError: () -> Unit,
    menu: BookMenuActions = BookMenuActions(),
    contentPadding: PaddingValues = PaddingValues(),
) {
    val loaded = (state.book as? Load.Loaded)?.value
    var open by remember { mutableStateOf(false) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    Page(
        title = loaded?.title.orEmpty(),
        onBack = onBack,
        actions = {
            if (loaded != null) {
                Box {
                    IconButton(onClick = { open = true }, modifier = Modifier.testTag("book.more")) { Icon(Icons.Filled.MoreVert, stringResource(SR.string.more)) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        BookMenuItems(loaded, state, menu, onResetAsked = { confirmingReset = true }, onClose = { open = false })
                    }
                }
            }
        },
    ) {
        LoadStateView(state.book.forView(), onRetry = onRetry, modifier = Modifier.padding(top = 96.dp)) { book ->
            BookBody(serverId, book, state, playback, onPlay, onToggle, onPlayFrom, onOpenSeries, menu)
        }
        Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
    }
    if (confirmingReset) ResetProgressDialog(onReset = { confirmingReset = false; menu.onResetProgress() }, onDismiss = { confirmingReset = false })
    state.error?.let { ErrorAlert(it.text(), onDismissError) }
}

/**
 * The book menu (book-ios `BookDetailView`): Add to Favorites / Remove Favorite where the server
 * keeps them, Share with the Family / Keep to Myself for the owner, Mark as Finished / Not
 * Finished, and Reset Progress once there is a position.
 */
@Composable
fun BookMenuItems(book: Book, state: BookState, menu: BookMenuActions, onResetAsked: () -> Unit, onClose: () -> Unit) {
    val palette = LocalPalette.current
    if (state.canFavourite) {
        DropdownMenuItem(
            text = { Text(stringResource(if (state.isFavourite) R.string.remove_favorite else R.string.add_to_favorites)) },
            leadingIcon = { Icon(if (state.isFavourite) Icons.Filled.HeartBroken else Icons.Filled.FavoriteBorder, null) },
            onClick = { onClose(); menu.onToggleFavourite() },
        )
    }
    if (state.canShare) {
        val shared = book.visibility == Visibility.FAMILY
        DropdownMenuItem(
            text = { Text(stringResource(if (shared) R.string.keep_to_myself else R.string.share_with_family)) },
            leadingIcon = { Icon(if (shared) Icons.Filled.PersonOutline else Icons.Filled.Group, null) },
            onClick = { onClose(); menu.onShare(!shared) },
            modifier = Modifier.testTag("book.share"),
        )
    }
    if (state.progress?.finished == true) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.mark_not_finished)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null) },
            onClick = { onClose(); menu.onFinished(false) },
        )
    } else {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.mark_finished)) },
            leadingIcon = { Icon(Icons.Filled.CheckCircleOutline, null) },
            onClick = { onClose(); menu.onFinished(true) },
        )
    }
    if (state.progress != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.reset_progress), color = palette.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = palette.error) },
            onClick = { onClose(); onResetAsked() },
        )
    }
}

@Composable
private fun ResetProgressDialog(onReset: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_progress_question)) },
        text = { Text(stringResource(R.string.reset_progress_message)) },
        confirmButton = { TextButton(onClick = onReset) { Text(stringResource(R.string.reset_progress), color = LocalPalette.current.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(SR.string.cancel)) } },
    )
}

@Composable
private fun BookBody(
    serverId: String,
    book: Book,
    state: BookState,
    playback: PlaybackUi,
    onPlay: (Book) -> Unit,
    onToggle: () -> Unit,
    onPlayFrom: (Book, Long) -> Unit,
    onOpenSeries: (SeriesRef) -> Unit,
    menu: BookMenuActions,
) {
    val palette = LocalPalette.current
    val isThis = playback.bookId == BookId.of(serverId, book.id).value && !playback.restored
    Column(Modifier.padding(vertical = Tokens.Spacing.md), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg)) {
        Row(Modifier.padding(horizontal = Tokens.Spacing.screenEdge), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg)) {
            BookCover(serverId, book, 120.dp)
            Column(Modifier.weight(1f).heightIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = palette.foreground, modifier = Modifier.semantics { heading() })
                book.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                if (book.authors.isNotEmpty()) Text(book.authorLine, style = MaterialTheme.typography.titleSmall, color = palette.muted)
                book.series.forEach { series ->
                    Text(
                        series.sequence?.let { stringResource(R.string.series_number, series.name, it) } ?: series.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.accent,
                        // 48 dp to tap, the text as small as iOS draws it.
                        modifier = Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button) { onOpenSeries(series) },
                    )
                }
                if (book.visibility == Visibility.FAMILY) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                        Icon(Icons.Filled.Group, null, tint = palette.accent, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.shared_with_the_family), style = MaterialTheme.typography.bodySmall, color = palette.accent)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(progressSummary(book, state.progress), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
            }
        }
        Row(
            Modifier.padding(horizontal = Tokens.Spacing.screenEdge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
        ) {
            PlayCapsule(
                book = book,
                progress = state.progress,
                isThis = isThis,
                playback = playback,
                onClick = { if (isThis) onToggle() else onPlay(book) },
                modifier = Modifier.weight(1f),
            )
            audio.own.bookplayer.downloads.DownloadButton(serverId, book)
        }
        book.description?.strippingHtml()?.takeIf { it.isNotBlank() }?.let { Description(it) }
        Details(book)
        var tab by rememberSaveable { mutableStateOf(BookTab.CHAPTERS) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge)) {
            BookTab.entries.forEachIndexed { index, t ->
                SegmentedButton(
                    selected = tab == t,
                    onClick = { tab = t },
                    shape = SegmentedButtonDefaults.itemShape(index, BookTab.entries.size),
                    icon = {},
                    label = {
                        Text(stringResource(when (t) { BookTab.CHAPTERS -> R.string.chapters; BookTab.FILES -> R.string.files; BookTab.BOOKMARKS -> R.string.bookmarks }), maxLines = 1)
                    },
                )
            }
        }
        Column {
            when (tab) {
                BookTab.CHAPTERS -> {
                    val chapters = shownChapters(book, isThis, playback)
                    when {
                        chapters.isNotEmpty() -> chapters.forEach { chapter ->
                            val current = isThis && playback.chapter?.startMs == chapter.startMs
                            TimeRow(chapter.title, clock(chapter.durationMs), current = current, playing = playback.isPlaying) { onPlayFrom(book, chapter.startMs) }
                        }
                        book.files == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> Muted(stringResource(R.string.no_chapter_marks))
                    }
                }
                BookTab.FILES -> {
                    val files = book.files
                    if (files == null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        files.forEachIndexed { index, file ->
                            TimeRow(file.title ?: stringResource(R.string.file_n, index + 1), clock(file.durationMs)) { onPlayFrom(book, file.startMs) }
                        }
                    }
                }
                BookTab.BOOKMARKS -> {
                    when {
                        state.offline -> Muted(stringResource(R.string.bookmarks_offline))
                        state.bookmarks.isEmpty() -> Muted(stringResource(R.string.no_bookmarks_book))
                        else -> state.bookmarks.forEach { bookmark ->
                            key(bookmark.id) {
                                BookmarkRow(
                                    bookmark,
                                    BookmarkActions(onPick = { onPlayFrom(book, it.positionMs) }, onRename = menu.onRenameBookmark, onDelete = menu.onDeleteBookmark),
                                    horizontalPadding = Tokens.Spacing.screenEdge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Play capsule whose background is the book's progress: the accent at 55 % with the
 * listened part filled.
 */
@Composable
fun PlayCapsule(book: Book, progress: ListeningProgress?, isThis: Boolean, playback: PlaybackUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val fraction = if (isThis && playback.durationMs > 0) playback.progress else progressFraction(book, progress) ?: 0f
    val starting = isThis && playback.isLoading && !playback.isPlaying
    val (label, icon) = when {
        isThis && playback.isPlaying -> stringResource(SR.string.pause) to Icons.Filled.Pause
        isThis -> stringResource(R.string.resume) to Icons.Filled.PlayArrow
        progress?.finished == true -> stringResource(R.string.play_again) to Icons.Filled.PlayArrow
        progress != null && progress.positionMs > 0 -> stringResource(R.string.play_continue) to Icons.Filled.PlayArrow
        else -> stringResource(SR.string.play) to Icons.Filled.PlayArrow
    }
    val listened = if (progress?.finished == true && !isThis) stringResource(R.string.finished) else stringResource(R.string.percent_listened, percent(fraction))
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(palette.accent.copy(alpha = 0.55f))
            .clickable(enabled = !starting, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                if (fraction > 0f) stateDescription = listened
                if (starting) disabled()
                onClick(label = label) { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(if (fraction >= 0.01f) fraction else 0f).background(palette.accent))
        if (starting) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                Icon(icon, null, tint = Color.White)
                Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        SectionTitle(stringResource(R.string.description))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.foreground,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            modifier = Modifier.padding(horizontal = Tokens.Spacing.screenEdge).clickable { expanded = !expanded },
        )
        if (overflows || expanded) Text(
            stringResource(if (expanded) R.string.show_less else R.string.show_more),
            style = MaterialTheme.typography.labelLarge,
            color = palette.accent,
            modifier = Modifier.padding(horizontal = Tokens.Spacing.screenEdge).minimumInteractiveComponentSize().clickable(role = Role.Button) { expanded = !expanded },
        )
    }
}

@Composable
private fun Details(book: Book) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = Tokens.Spacing.screenEdge)) {
        Text(stringResource(R.string.details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LocalPalette.current.foreground, modifier = Modifier.padding(bottom = Tokens.Spacing.sm).semantics { heading() })
        if (book.narrators.isNotEmpty()) DetailRow(Icons.Filled.Mic, stringResource(R.string.narrator), book.narratorLine)
        DetailRow(Icons.Filled.Schedule, stringResource(R.string.length), lengthText(book.durationMs))
        book.publishedYear?.let { DetailRow(Icons.Filled.CalendarMonth, stringResource(R.string.published), it) }
        book.publisher?.let { DetailRow(Icons.Filled.Business, stringResource(R.string.publisher), it) }
        if (book.genres.isNotEmpty()) DetailRow(Icons.Filled.Sell, stringResource(R.string.genres), book.genres.joinToString(", "))
        book.files?.takeIf { it.isNotEmpty() }?.let { files ->
            val bytes = files.mapNotNull { it.sizeBytes }.sum()
            val value = if (bytes > 0) "${files.size} · ${android.text.format.Formatter.formatShortFileSize(context, bytes)}" else "${files.size}"
            DetailRow(Icons.Filled.SdStorage, stringResource(R.string.files), value)
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = Tokens.Spacing.sm), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        Icon(icon, null, tint = palette.muted, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.foreground)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = palette.muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = palette.border)
}

@Composable
private fun TimeRow(title: String, time: String, current: Boolean = false, playing: Boolean = false, timeBelow: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable(onClick = onClick).padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
    ) {
        if (current) NowPlayingIndicator(playing)
        if (timeBelow) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(time, style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
            }
        } else {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) palette.accent else palette.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(time, style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
        }
    }
    HorizontalDivider(Modifier.padding(start = Tokens.Spacing.screenEdge), color = palette.border)
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.muted, modifier = Modifier.padding(Tokens.Spacing.md).padding(horizontal = Tokens.Spacing.xs))
}

/**
 * The server's chapters; without them, for the book that is playing, the marks the player read
 * from its files (an .m4b's own chapters), so the page and the player list the same ones.
 */
internal fun shownChapters(book: Book, isThis: Boolean, playback: PlaybackUi): List<Chapter> =
    book.chapters.orEmpty().ifEmpty {
        if (isThis && playback.hasRealChapters) playback.chapters.map { Chapter(it.title, it.startMs, it.endMs) } else emptyList()
    }
