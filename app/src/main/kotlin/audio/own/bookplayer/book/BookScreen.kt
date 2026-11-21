package audio.own.bookplayer.book

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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Sell
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.book.library.Book
import audio.own.book.library.BookId
import audio.own.book.library.BookSource
import audio.own.book.library.Bookmark
import audio.own.book.library.Capability
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
    val error: UiText? = null,
)

/** One book with its files and chapters, where the listener is, and their bookmarks. */
class BookViewModel(private val source: BookSource?, private val bookId: String, known: Book? = null, private val isOffline: () -> Boolean = { false }) : ViewModel() {
    private val _state = MutableStateFlow(BookState(book = known?.let { Load.Loaded(it) } ?: Load.Loading, canFavourite = Capability.FAVOURITES in source?.capabilities.orEmpty()))
    val state: StateFlow<BookState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val source = source ?: return
        viewModelScope.launch {
            val shown = (_state.value.book as? Load.Loaded)?.value
            if (shown?.files == null) {
                try {
                    val detail = source.book(bookId)
                    _state.update { it.copy(book = Load.Loaded(detail)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (shown == null) _state.update { it.copy(book = Load.Failed(e.sourceMessage())) }
                }
            }
            refresh()
        }
    }

    /** Where the listener is, their bookmarks and favourite; after playing or a change. */
    fun refresh() {
        val source = source ?: return
        viewModelScope.launch {
            val progress = runCatching { source.progress(bookId) }.getOrNull()
            _state.update { it.copy(progress = progress ?: it.progress) }
            if (isOffline()) return@launch
            val bookmarks = runCatching { source.bookmarks(bookId).sortedBy { it.positionMs } }.getOrNull()
            val favourite = if (_state.value.canFavourite) runCatching { bookId in source.favourites() }.getOrNull() else null
            _state.update { it.copy(bookmarks = bookmarks ?: it.bookmarks, isFavourite = favourite ?: it.isFavourite) }
        }
    }

    fun toggleFavourite() {
        val source = source ?: return
        val wanted = !_state.value.isFavourite
        _state.update { it.copy(isFavourite = wanted) }
        viewModelScope.launch {
            try {
                source.setFavourite(bookId, wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isFavourite = !wanted, error = e.sourceMessage()) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

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
    onToggleFavourite: () -> Unit,
    onOpenSeries: (SeriesRef) -> Unit,
    onDismissError: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val loaded = (state.book as? Load.Loaded)?.value
    var menu by remember { mutableStateOf(false) }
    Page(
        title = loaded?.title.orEmpty(),
        onBack = onBack,
        actions = {
            if (state.canFavourite) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(SR.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (state.isFavourite) R.string.remove_favorite else R.string.add_to_favorites)) },
                            leadingIcon = { Icon(if (state.isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
                            onClick = { menu = false; onToggleFavourite() },
                        )
                    }
                }
            }
        },
    ) {
        LoadStateView(state.book.forView(), onRetry = onRetry, modifier = Modifier.padding(top = 96.dp)) { book ->
            BookBody(serverId, book, state, playback, onPlay, onToggle, onPlayFrom, onOpenSeries)
        }
        Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
    }
    state.error?.let { ErrorAlert(it.text(), onDismissError) }
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
) {
    val palette = LocalPalette.current
    val isThis = playback.bookId == BookId.of(serverId, book.id).value && !playback.restored
    Column(Modifier.padding(vertical = Tokens.Spacing.md), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg)) {
        Row(Modifier.padding(horizontal = Tokens.Spacing.screenEdge), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg)) {
            BookCover(serverId, book, 120.dp)
            Column(Modifier.weight(1f).heightIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = palette.foreground)
                book.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                if (book.authors.isNotEmpty()) Text(book.authorLine, style = MaterialTheme.typography.titleSmall, color = palette.muted)
                book.series.forEach { series ->
                    Text(
                        series.sequence?.let { stringResource(R.string.series_number, series.name, it) } ?: series.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.accent,
                        modifier = Modifier.clickable { onOpenSeries(series) },
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
        PlayCapsule(
            book = book,
            progress = state.progress,
            isThis = isThis,
            playback = playback,
            onClick = { if (isThis) onToggle() else onPlay(book) },
            modifier = Modifier.padding(horizontal = Tokens.Spacing.screenEdge),
        )
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
                    val chapters = book.chapters.orEmpty()
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
                    if (state.bookmarks.isEmpty()) {
                        Muted(stringResource(R.string.no_bookmarks_book))
                    } else {
                        state.bookmarks.forEach { bookmark ->
                            TimeRow(bookmark.label ?: stringResource(R.string.bookmark), clock(bookmark.positionMs), timeBelow = true) { onPlayFrom(book, bookmark.positionMs) }
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
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(palette.accent.copy(alpha = 0.55f))
            .clickable(enabled = !starting) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() },
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
            modifier = Modifier.padding(horizontal = Tokens.Spacing.screenEdge).clickable { expanded = !expanded },
        )
    }
}

@Composable
private fun Details(book: Book) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = Tokens.Spacing.screenEdge)) {
        Text(stringResource(R.string.details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LocalPalette.current.foreground, modifier = Modifier.padding(bottom = Tokens.Spacing.sm))
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
