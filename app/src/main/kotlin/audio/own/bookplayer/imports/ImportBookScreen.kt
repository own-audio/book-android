package audio.own.bookplayer.imports

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.book.library.ImportMetadata
import audio.own.book.library.Visibility
import audio.own.bookplayer.R
import audio.own.bookplayer.app.UiText
import audio.own.bookplayer.app.text
import audio.own.bookplayer.browse.clock
import audio.own.bookplayer.browse.lengthText
import audio.own.bookplayer.onboarding.FormTextField
import audio.own.bookplayer.ui.ButtonRow
import audio.own.bookplayer.ui.ErrorLine
import audio.own.bookplayer.ui.Page
import audio.own.bookplayer.ui.RowDivider
import audio.own.bookplayer.ui.Section
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import audio.own.core.strings.R as SR

data class ImportFormState(
    val files: List<PickedFile> = emptyList(),
    val title: String = "",
    val author: String = "",
    val narrator: String = "",
    val visibility: Visibility = Visibility.PRIVATE,
    val cover: ByteArray? = null,
    val reading: Boolean = false,
    val starting: Boolean = false,
    val error: UiText? = null,
    /** The size to ask about before going over mobile data. */
    val askMobile: Long? = null,
    val done: Boolean = false,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val totalMs: Long get() = files.sumOf { it.durationMs }
    val canImport: Boolean get() = files.isNotEmpty() && !reading && !starting
}

/**
 * The Import a Book form (iOS ImportBookView): picked files and folders read for their tags and
 * put in order, the book's names, a cover, who sees it; then [BookImports] takes over and the
 * screen closes.
 */
class ImportBookViewModel(
    private val serverId: String,
    private val reader: ImportReader,
    private val imports: BookImports,
    private val isMetered: () -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportFormState())
    val state: StateFlow<ImportFormState> = _state.asStateFlow()
    private val taken = mutableSetOf<String>()

    fun addDocuments(uris: List<String>) = read { reader.documents(uris, wantArtwork = _state.value.cover == null) }

    fun addFolder(tree: String) = read { reader.folder(tree, wantArtwork = _state.value.cover == null) }

    private fun read(work: suspend () -> List<PickedFile>) {
        _state.update { it.copy(reading = true, error = null) }
        viewModelScope.launch {
            try {
                val picked = work()
                picked.forEach { taken += it.grant }
                _state.update { s ->
                    // The same file twice is one file, as on iOS.
                    val fresh = picked.filter { p -> s.files.none { it.fileName == p.fileName } }
                    val first = fresh.firstOrNull()
                    s.copy(
                        files = s.files + fresh.map { it.copy(artwork = null) },
                        title = s.title.ifEmpty { first?.album ?: first?.title.orEmpty() },
                        author = s.author.ifEmpty { first?.artist.orEmpty() },
                        cover = s.cover ?: fresh.firstNotNullOfOrNull { it.artwork },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = UiText(SR.string.error_generic)) }
            } finally {
                _state.update { it.copy(reading = false) }
            }
        }
    }

    fun move(index: Int, by: Int) = _state.update { s ->
        val to = index + by
        if (index !in s.files.indices || to !in s.files.indices) return@update s
        s.copy(files = s.files.toMutableList().apply { add(to, removeAt(index)) })
    }

    fun remove(index: Int) = _state.update { s -> if (index in s.files.indices) s.copy(files = s.files.toMutableList().apply { removeAt(index) }) else s }

    fun setTitle(value: String) = _state.update { it.copy(title = value) }
    fun setAuthor(value: String) = _state.update { it.copy(author = value) }
    fun setNarrator(value: String) = _state.update { it.copy(narrator = value) }
    fun setVisibility(value: Visibility) = _state.update { it.copy(visibility = value) }

    fun setCover(uri: String) {
        viewModelScope.launch { reader.picture(uri)?.let { bytes -> _state.update { it.copy(cover = bytes) } } }
    }

    /** Over mobile data and past 100 MB the listener is asked first. */
    fun addToLibrary() {
        val s = _state.value
        if (!s.canImport) return
        if (isMetered() && s.totalBytes > MOBILE_QUESTION_BYTES) _state.update { it.copy(askMobile = s.totalBytes) } else start()
    }

    fun dismissMobile() = _state.update { it.copy(askMobile = null) }

    fun start() {
        val s = _state.value
        if (!s.canImport) return
        _state.update { it.copy(askMobile = null, starting = true, error = null) }
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                imports.start(
                    ImportDraft(
                        serverId = serverId,
                        title = s.title,
                        authors = ImportMetadata.names(s.author),
                        narrators = ImportMetadata.names(s.narrator),
                        visibility = s.visibility,
                        cover = s.cover,
                        files = s.files,
                    ),
                )
                }
                _state.update { it.copy(done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = UiText(SR.string.error_generic), starting = false) }
            }
        }
    }

    /** Permissions taken for files that were removed again, or for a form left without importing. */
    override fun onCleared() {
        reader.release(taken - imports.state.value.records.values.flatMap { it.grants }.toSet())
    }

    companion object {
        const val MOBILE_QUESTION_BYTES = 100L shl 20
    }
}

/** What the screen asks the system for. */
data class ImportPickers(
    val audioFiles: () -> Unit,
    val folder: () -> Unit,
    val cover: () -> Unit,
)

@Composable
fun rememberImportPickers(onDocuments: (List<String>) -> Unit, onFolder: (String) -> Unit, onCover: (String) -> Unit): ImportPickers {
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) onDocuments(uris.map { it.toString() }) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { onFolder(it.toString()) } }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { onCover(it.toString()) } }
    return remember(documents, tree, picture) {
        ImportPickers(
            audioFiles = { documents.launch(arrayOf("audio/*", "application/ogg")) },
            folder = { tree.launch(null) },
            cover = { picture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

class ImportFormActions(
    val onCancel: () -> Unit,
    val onPickFiles: () -> Unit,
    val onPickFolder: () -> Unit,
    val onPickCover: () -> Unit,
    val onMove: (Int, Int) -> Unit,
    val onRemove: (Int) -> Unit,
    val onTitle: (String) -> Unit,
    val onAuthor: (String) -> Unit,
    val onNarrator: (String) -> Unit,
    val onVisibility: (Visibility) -> Unit,
    val onAdd: () -> Unit,
    val onConfirmMobile: () -> Unit,
    val onDismissMobile: () -> Unit,
)

@Composable
fun ImportBookContent(state: ImportFormState, actions: ImportFormActions) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Page(title = stringResource(R.string.import_a_book), onBack = actions.onCancel, closeIsCancel = true) {
        val footer = if (state.files.isEmpty()) {
            null
        } else {
            pluralStringResource(R.plurals.import_files_summary, state.files.size, state.files.size, lengthText(state.totalMs), Formatter.formatShortFileSize(context, state.totalBytes))
        }
        Section(header = stringResource(R.string.files_in_playing_order), footer = footer) {
            ButtonRow(
                stringResource(if (state.files.isEmpty()) R.string.choose_audio_files else R.string.add_more_files),
                onClick = actions.onPickFiles,
                icon = Icons.Filled.NoteAdd,
                color = MaterialTheme.colorScheme.primary,
                enabled = !state.reading,
                modifier = Modifier.testTag("import.pick"),
            )
            RowDivider()
            ButtonRow(
                stringResource(R.string.choose_a_folder),
                onClick = actions.onPickFolder,
                icon = Icons.Filled.CreateNewFolder,
                color = MaterialTheme.colorScheme.primary,
                enabled = !state.reading,
                working = state.reading,
                modifier = Modifier.testTag("import.folder"),
            )
            state.files.forEachIndexed { index, file ->
                RowDivider()
                FileRow(file, index, state.files.size, actions)
            }
        }
        Section(header = stringResource(R.string.book)) {
            FormTextField(state.title, actions.onTitle, stringResource(R.string.title), null, KeyboardType.Text, Modifier.testTag("import.title"))
            RowDivider()
            FormTextField(state.author, actions.onAuthor, stringResource(R.string.author), stringResource(R.string.several_names_hint), KeyboardType.Text, Modifier.testTag("import.author"))
            RowDivider()
            FormTextField(state.narrator, actions.onNarrator, stringResource(R.string.narrator), stringResource(R.string.several_names_hint), KeyboardType.Text, Modifier.testTag("import.narrator"))
            RowDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable(onClick = actions.onPickCover).padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.cover), modifier = Modifier.weight(1f))
                val bitmap = remember(state.cover) { state.cover?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
                if (bitmap != null) {
                    Image(bitmap, contentDescription = stringResource(R.string.cover), contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                } else {
                    Text(stringResource(R.string.cover_from_file), color = palette.muted)
                }
            }
            RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.md), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                Text(stringResource(R.string.who_sees_it))
                val options = listOf(Visibility.PRIVATE to R.string.just_me, Visibility.FAMILY to R.string.the_family)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("import.visibility")) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = state.visibility == value,
                            onClick = { actions.onVisibility(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm)) }
        Section(header = null, footer = stringResource(R.string.import_footer)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ButtonRow(
                    stringResource(R.string.add_to_library),
                    onClick = actions.onAdd,
                    enabled = state.canImport,
                    working = state.starting,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("import.start"),
                )
            }
        }
    }
    state.askMobile?.let { bytes ->
        AlertDialog(
            onDismissRequest = actions.onDismissMobile,
            title = { Text(stringResource(R.string.upload_over_mobile_data, Formatter.formatShortFileSize(context, bytes))) },
            confirmButton = { TextButton(onClick = actions.onConfirmMobile) { Text(stringResource(R.string.upload_now)) } },
            dismissButton = { TextButton(onClick = actions.onDismissMobile) { Text(stringResource(SR.string.cancel)) } },
        )
    }
}

@Composable
private fun FileRow(file: PickedFile, index: Int, count: Int, actions: ImportFormActions) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(start = Tokens.Spacing.lg, end = Tokens.Spacing.xs, top = Tokens.Spacing.xs, bottom = Tokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.title ?: file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.fileName, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(clock(file.durationMs), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
        IconButton(onClick = { actions.onMove(index, -1) }, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
        IconButton(onClick = { actions.onMove(index, 1) }, enabled = index < count - 1) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
        IconButton(onClick = { actions.onRemove(index) }) { Icon(Icons.Filled.Close, stringResource(SR.string.remove)) }
    }
}

/** A number of bytes as the system says it ("1.2 GB"), for rows outside a composition. */
@Composable
fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)
