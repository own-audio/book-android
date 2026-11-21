package audio.own.bookplayer.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.book.library.BookSource
import audio.own.book.library.Bookmark
import audio.own.bookplayer.R
import audio.own.bookplayer.app.UiText
import audio.own.bookplayer.browse.clock
import audio.own.bookplayer.browse.sourceMessage
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

data class BookmarksState(
    /** Null while loading. */
    val bookmarks: List<Bookmark>? = null,
    /** Bookmarks live on the server: offline there is nothing to show or change. */
    val offline: Boolean = false,
    val error: UiText? = null,
)

/**
 * The bookmarks of one book (book-ios `BookmarksSheet`): added at a position with an optional
 * label, renamed, deleted. Online only, as on iOS; a change shows at once and is put back if
 * the server refuses it.
 */
class BookmarksViewModel(private val source: BookSource?, private val bookId: String?, private val isOffline: () -> Boolean) : ViewModel() {
    private val _state = MutableStateFlow(BookmarksState())
    val state: StateFlow<BookmarksState> = _state.asStateFlow()

    fun load() {
        if (isOffline() || source == null || bookId == null) {
            _state.update { it.copy(bookmarks = emptyList(), offline = isOffline()) }
            return
        }
        _state.update { it.copy(offline = false) }
        viewModelScope.launch {
            val list = runCatching { source.bookmarks(bookId).sortedBy { it.positionMs } }.getOrDefault(emptyList())
            _state.update { it.copy(bookmarks = list) }
        }
    }

    fun add(positionMs: Long, label: String) = change {
        val made = source!!.addBookmark(bookId!!, positionMs, label.trim().ifEmpty { null })
        _state.update { s -> s.copy(bookmarks = (s.bookmarks.orEmpty() + made).sortedBy { it.positionMs }) }
    }

    fun rename(bookmark: Bookmark, label: String) = change {
        val renamed = source!!.renameBookmark(bookmark, label.trim().ifEmpty { null })
        _state.update { s -> s.copy(bookmarks = s.bookmarks?.map { if (it.id == bookmark.id) renamed else it }) }
    }

    fun delete(bookmark: Bookmark) {
        val before = _state.value.bookmarks
        _state.update { s -> s.copy(bookmarks = s.bookmarks?.filterNot { it.id == bookmark.id }) }
        change(onFailure = { _state.update { it.copy(bookmarks = before) } }) { source!!.deleteBookmark(bookmark) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun change(onFailure: () -> Unit = {}, work: suspend () -> Unit) {
        if (source == null || bookId == null) return
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

/** What a bookmark row can do: jump there, rename, delete. */
data class BookmarkActions(
    val onPick: (Bookmark) -> Unit = {},
    val onRename: ((Bookmark, String) -> Unit)? = null,
    val onDelete: ((Bookmark) -> Unit)? = null,
)

/**
 * A bookmark: a tap jumps there. With actions, a swipe either way offers them in words (to the
 * left Delete, to the right Rename), and the ⋮ menu has both for those who don't swipe.
 */
@Composable
fun BookmarkRow(bookmark: Bookmark, actions: BookmarkActions, horizontalPadding: androidx.compose.ui.unit.Dp = Tokens.Spacing.lg) {
    val palette = LocalPalette.current
    var renaming by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val editable = actions.onRename != null || actions.onDelete != null
    val row = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .heightIn(min = Tokens.minTarget)
                .clickable { actions.onPick(bookmark) }
                .padding(start = horizontalPadding, end = if (editable) Tokens.Spacing.xs else horizontalPadding, top = Tokens.Spacing.sm, bottom = Tokens.Spacing.sm)
                .testTag("bookmark.${bookmark.positionMs}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(bookmark.label ?: stringResource(R.string.bookmark), color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(clock(bookmark.positionMs), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
            }
            if (editable) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(SR.string.more), tint = palette.muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (actions.onRename != null) DropdownMenuItem(text = { Text(stringResource(SR.string.rename)) }, onClick = { menu = false; renaming = true })
                        if (actions.onDelete != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = palette.error) }, onClick = { menu = false; actions.onDelete.invoke(bookmark) })
                        }
                    }
                }
            }
        }
    }
    if (editable) {
        SwipeToDismissBox(
            state = swipe,
            enableDismissFromStartToEnd = actions.onRename != null,
            enableDismissFromEndToStart = actions.onDelete != null,
            // The row springs back: a rename keeps it, and a deleted one leaves the list anyway.
            onDismiss = { direction ->
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> actions.onDelete?.invoke(bookmark)
                    SwipeToDismissBoxValue.StartToEnd -> renaming = true
                    SwipeToDismissBoxValue.Settled -> Unit
                }
                scope.launch { swipe.reset() }
            },
            backgroundContent = {
                val deleting = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
                Box(
                    Modifier.fillMaxSize().background(if (deleting) palette.error else palette.accent).padding(horizontal = horizontalPadding),
                    contentAlignment = if (deleting) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(if (deleting) R.string.delete else SR.string.rename),
                        color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
        ) { row() }
    } else {
        row()
    }
    HorizontalDivider(Modifier.padding(start = horizontalPadding), color = palette.border)
    if (renaming && actions.onRename != null) {
        LabelDialog(
            title = stringResource(R.string.rename_bookmark),
            placeholder = stringResource(R.string.label),
            initial = bookmark.label.orEmpty(),
            confirm = stringResource(SR.string.save),
            onConfirm = { renaming = false; actions.onRename.invoke(bookmark, it) },
            onDismiss = { renaming = false },
        )
    }
}

/** "New Bookmark" and "Rename Bookmark": one text field, Cancel and the confirming word. */
@Composable
fun LabelDialog(title: String, placeholder: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bookmark.label"),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(SR.string.cancel)) } },
    )
}

/** The add row: "Add Bookmark at 12:34", or offline why it can't be done. */
@Composable
fun AddBookmarkRow(positionMs: Long, offline: Boolean, onAdd: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.minTarget)
            .clickable(enabled = !offline, onClick = onAdd)
            .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm)
            .testTag("bookmarks.add"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.add_bookmark_at, clock(positionMs)),
            color = if (offline) palette.muted else palette.accent,
            fontWeight = FontWeight.SemiBold,
        )
        if (offline) Text(stringResource(R.string.bookmarks_offline), style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}
