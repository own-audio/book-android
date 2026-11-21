package audio.own.bookplayer.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.book.library.Book
import audio.own.book.library.BookSource
import audio.own.book.library.Person
import audio.own.book.library.SearchResult
import audio.own.book.library.SeriesSummary
import audio.own.bookplayer.R
import audio.own.bookplayer.app.UiText
import audio.own.bookplayer.app.text
import audio.own.bookplayer.browse.BookRow
import audio.own.bookplayer.browse.NameCountRow
import audio.own.bookplayer.browse.sourceMessage
import audio.own.bookplayer.ui.ErrorLine
import audio.own.bookplayer.ui.Page
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val text: String = "",
    val result: SearchResult? = null,
    val searching: Boolean = false,
    val error: UiText? = null,
)

/**
 * Books, series, authors and narrators: on the server where it searches (Audiobookshelf), else
 * over the catalogue on the phone (own.audio). Two characters at least, 300 ms after the last key.
 */
class SearchViewModel(private val source: BookSource?, private val debounceMs: Long = 300) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun setText(text: String) {
        _state.update { it.copy(text = text) }
        job?.cancel()
        val needle = text.trim()
        if (needle.length < 2 || source == null) {
            _state.update { it.copy(result = null, searching = false, error = null) }
            return
        }
        job = viewModelScope.launch {
            delay(debounceMs)
            _state.update { it.copy(searching = true, error = null) }
            try {
                val result = source.search(needle)
                _state.update { it.copy(result = result, searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.sourceMessage(), searching = false) }
            }
        }
    }
}

@Composable
fun SearchContent(
    serverId: String?,
    state: SearchState,
    onText: (String) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (SeriesSummary) -> Unit,
    onOpenAuthor: (Person) -> Unit,
    onOpenNarrator: (Person) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    focusOnOpen: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    Page(title = stringResource(R.string.tab_search), onBack = null, scrolls = false) {
        OutlinedTextField(
            value = state.text,
            onValueChange = onText,
            placeholder = { Text(stringResource(R.string.search_prompt)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (state.searching) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm).focusRequester(focus),
        )
        if (focusOnOpen) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val result = state.result
        when {
            state.error != null -> ErrorLine(state.error.text(), Modifier.padding(Tokens.Spacing.screenEdge))
            result != null && serverId != null -> {
                if (result.books.isEmpty() && result.series.isEmpty() && result.authors.isEmpty() && result.narrators.isEmpty()) {
                    EmptyState(Icons.Filled.Search, stringResource(R.string.no_results), stringResource(R.string.no_results_text, state.text.trim()))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                        if (result.books.isNotEmpty()) {
                            item { Header(stringResource(R.string.books)) }
                            items(result.books, key = { "b" + it.id }) { book -> BookRow(serverId, book, null, Modifier.clickable { onOpenBook(book) }) }
                        }
                        if (result.series.isNotEmpty()) {
                            item { Header(stringResource(R.string.series)) }
                            items(result.series, key = { "s" + it.id }) { one -> NameCountRow(one.name, null) { onOpenSeries(one) } }
                        }
                        if (result.authors.isNotEmpty()) {
                            item { Header(stringResource(R.string.authors)) }
                            items(result.authors, key = { "a" + it.id }) { person -> NameCountRow(person.name, null) { onOpenAuthor(person) } }
                        }
                        if (result.narrators.isNotEmpty()) {
                            item { Header(stringResource(R.string.narrators)) }
                            items(result.narrators, key = { "n" + it.id }) { person -> NameCountRow(person.name, null) { onOpenNarrator(person) } }
                        }
                    }
                }
            }
            state.text.isBlank() -> EmptyState(Icons.Filled.Search, stringResource(R.string.search_the_library), stringResource(R.string.search_the_library_text))
        }
    }
}

@Composable
private fun Header(text: String) {
    Column {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = LocalPalette.current.muted,
            modifier = Modifier.padding(start = Tokens.Spacing.screenEdge, end = Tokens.Spacing.screenEdge, top = Tokens.Spacing.lg, bottom = Tokens.Spacing.xs),
        )
    }
}
