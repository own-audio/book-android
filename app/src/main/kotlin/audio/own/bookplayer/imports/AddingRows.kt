package audio.own.bookplayer.imports

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import audio.own.bookplayer.R
import audio.own.bookplayer.app.UiText
import audio.own.bookplayer.app.text
import audio.own.bookplayer.narration.NarrationEntry
import audio.own.bookplayer.narration.formatPrice
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R as SR

/** The Library root's + : one button, or a menu when narration is there too. */
@Composable
fun AddBookButton(rights: AddBookRights, onImport: () -> Unit, onNarrate: () -> Unit) {
    when {
        rights.canImport && rights.canNarrate -> {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag("library.add")) { Icon(Icons.Filled.Add, stringResource(R.string.add_a_book)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_audio_files_menu)) },
                        leadingIcon = { Icon(Icons.Filled.GraphicEq, null) },
                        onClick = { open = false; onImport() },
                        modifier = Modifier.testTag("library.import"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.narrate_an_ebook_menu)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                        onClick = { open = false; onNarrate() },
                        modifier = Modifier.testTag("library.narrate"),
                    )
                }
            }
        }
        rights.canImport -> IconButton(onClick = onImport, modifier = Modifier.testTag("library.import")) { Icon(Icons.Filled.Add, stringResource(R.string.import_a_book)) }
        rights.canNarrate -> IconButton(onClick = onNarrate, modifier = Modifier.testTag("library.narrate")) { Icon(Icons.Filled.Add, stringResource(R.string.narrate_an_ebook)) }
    }
}

class AddingActions(
    val onRetryImport: (String) -> Unit,
    val onCancelImport: (String) -> Unit,
    val onForgetNarration: (String) -> Unit,
)

/** The Library's Importing and Narrating sections, above the books; nothing when both are empty. */
@Composable
fun AddingRows(imports: List<ImportRecord>, snapshot: ImportsSnapshot, narrations: List<NarrationEntry>, actions: AddingActions) {
    if (imports.isNotEmpty()) {
        Header(stringResource(R.string.importing))
        imports.forEach { ImportRow(it, snapshot.fraction(it), actions) }
    }
    if (narrations.isNotEmpty()) {
        Header(stringResource(R.string.narrating))
        narrations.forEach { NarrationRow(it, actions) }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = LocalPalette.current.muted,
        modifier = Modifier.padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm).semantics { heading() },
    )
}

@Composable
private fun ImportRow(record: ImportRecord, fraction: Float, actions: AddingActions) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = Tokens.Spacing.screenEdge, end = Tokens.Spacing.xs).testTag("import.${record.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = Tokens.Spacing.sm), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
            Text(record.title ?: record.files.firstOrNull()?.fileName ?: stringResource(R.string.book), maxLines = 1, overflow = TextOverflow.Ellipsis, color = palette.foreground)
            val failure = record.failure
            if (failure != null) {
                Text(failure.message().text(), style = MaterialTheme.typography.bodySmall, color = palette.error)
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                    TextButton(onClick = { actions.onRetryImport(record.id) }) { Text(stringResource(SR.string.try_again), fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = { actions.onCancelImport(record.id) }) { Text(stringResource(SR.string.remove), color = palette.error, fontWeight = FontWeight.SemiBold) }
                }
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    pluralStringResource(R.plurals.import_row_summary, record.files.size, record.files.size, sizeText(record.totalBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
        }
        if (record.failure == null) {
            IconButton(onClick = { actions.onCancelImport(record.id) }) { Icon(Icons.Filled.Close, stringResource(SR.string.cancel)) }
        }
    }
    HorizontalDivider(Modifier.padding(start = Tokens.Spacing.screenEdge), color = palette.border)
}

@Composable
private fun NarrationRow(entry: NarrationEntry, actions: AddingActions) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = Tokens.Spacing.screenEdge, end = Tokens.Spacing.xs).testTag("narration.${entry.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = Tokens.Spacing.sm), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = palette.foreground)
            when (entry.stage) {
                "failed" -> Text(entry.error ?: stringResource(R.string.narration_failed), style = MaterialTheme.typography.bodySmall, color = palette.error)
                "complete" -> Text(stringResource(R.string.narration_done, formatPrice(entry.quotedPriceCents, entry.currency)), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                else -> {
                    LinearProgressIndicator(progress = { entry.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(stageText(entry), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
            }
        }
        if (entry.isTerminal) {
            TextButton(onClick = { actions.onForgetNarration(entry.id) }) { Text(stringResource(SR.string.remove), color = palette.error) }
        }
    }
    HorizontalDivider(Modifier.padding(start = Tokens.Spacing.screenEdge), color = palette.border)
}

@Composable
private fun stageText(entry: NarrationEntry): String = when (entry.stage) {
    "queued" -> stringResource(R.string.waiting_its_turn)
    "extracting" -> stringResource(R.string.reading_the_ebook)
    "translating" -> stringResource(R.string.translating)
    "narrating" -> if (entry.chaptersTotal > 0) stringResource(R.string.narrating_chapter, minOf(entry.chaptersDone + 1, entry.chaptersTotal), entry.chaptersTotal) else stringResource(R.string.narrating)
    "assembling" -> stringResource(R.string.putting_the_book_together)
    else -> entry.stage
}

/** An import's failure in the app's words. */
fun ImportFailure.message(): UiText = when (code) {
    ImportFailure.NO_ROOM -> UiText(R.string.import_no_room)
    ImportFailure.NOT_ALLOWED -> UiText(R.string.import_not_allowed)
    ImportFailure.STORAGE_REFUSED -> UiText.of(R.string.storage_refused, status ?: 0)
    ImportFailure.FILE_UNREADABLE -> UiText(R.string.import_file_unreadable)
    ImportFailure.SIGN_IN -> UiText(SR.string.error_sign_in_refused)
    ImportFailure.NETWORK -> UiText(SR.string.error_network)
    ImportFailure.SERVER_GONE -> UiText(R.string.import_server_gone)
    ImportFailure.HTTP -> status?.let { UiText.of(SR.string.error_http, it) } ?: UiText(SR.string.error_generic)
    else -> UiText(SR.string.error_generic)
}
