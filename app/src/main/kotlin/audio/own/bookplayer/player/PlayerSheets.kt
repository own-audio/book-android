package audio.own.bookplayer.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import audio.own.book.library.Bookmark
import audio.own.bookplayer.R
import audio.own.bookplayer.browse.clock
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.bookplayer.playback.SleepUi
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.player.core.SleepTimer
import audio.own.player.spoken.PlaybackSpeed
import audio.own.player.spoken.SleepEnd
import audio.own.player.spoken.SpeedText
import kotlin.math.abs
import audio.own.core.strings.R as SR

val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        content()
    }
}

@Composable
private fun SheetTitle(title: String, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = LocalPalette.current.foreground, modifier = Modifier.weight(1f))
        TextButton(onClick = onDone) { Text(stringResource(SR.string.done)) }
    }
}

@Composable
fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) = Sheet(onDismiss) { SpeedSheetContent(speed, onSpeed, onDismiss) }

/** A stepper in 0.05 steps, with the common speeds one tap away. */
@Composable
fun SpeedSheetContent(speed: Float, onSpeed: (Float) -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = Tokens.Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl)) {
        SheetTitle(stringResource(R.string.speed), onDone)
        Text(SpeedText.text(speed), fontSize = 48.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.displayMedium.tabular(), color = palette.foreground)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl)) {
            IconButton(onClick = { onSpeed(PlaybackSpeed.normalized(speed - 0.05f)) }, enabled = speed > PlaybackSpeed.MIN, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.RemoveCircle, stringResource(R.string.slower), tint = palette.accent, modifier = Modifier.size(44.dp))
            }
            IconButton(onClick = { onSpeed(PlaybackSpeed.normalized(speed + 0.05f)) }, enabled = speed < PlaybackSpeed.MAX, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.AddCircle, stringResource(R.string.faster), tint = palette.accent, modifier = Modifier.size(44.dp))
            }
        }
        Column(Modifier.padding(horizontal = Tokens.Spacing.xl), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
            SPEED_PRESETS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                    row.forEach { preset ->
                        val chosen = abs(speed - preset) < 0.01f
                        OutlinedButton(
                            onClick = { onSpeed(preset) },
                            modifier = Modifier.weight(1f).heightIn(min = Tokens.minTarget),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (chosen) palette.accent.copy(alpha = 0.15f) else palette.backgroundAlt,
                                contentColor = if (chosen) palette.accent else palette.foreground,
                            ),
                            border = null,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) { Text(SpeedText.text(preset), fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.speed_footer),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Tokens.Spacing.xl),
        )
    }
}

@Composable
fun SleepSheet(sleep: SleepUi, hasBook: Boolean, actions: PlayerActions, onDismiss: () -> Unit) = Sheet(onDismiss) { SleepSheetContent(sleep, hasBook, actions, onDismiss) }

/** A countdown, the end of the chapter, or the end of the book. */
@Composable
fun SleepSheetContent(sleep: SleepUi, hasBook: Boolean, actions: PlayerActions, onDone: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = Tokens.Spacing.xl)) {
        SheetTitle(stringResource(R.string.sleep_timer), onDone)
        if (sleep.isOn) {
            audio.own.bookplayer.ui.Section(header = null) {
                when (sleep) {
                    is SleepUi.Countdown -> {
                        audio.own.core.designsystem.components.ValueRow(stringResource(R.string.stops_in), clock((sleep.remainingSecs * 1000).toLong()), onClick = null)
                        audio.own.bookplayer.ui.RowDivider()
                        audio.own.bookplayer.ui.ButtonRow(stringResource(R.string.add_10_minutes), onClick = actions.onExtendSleep, color = palette.accent)
                    }
                    SleepUi.EndOfChapter -> audio.own.bookplayer.ui.NoteRow(stringResource(R.string.stops_end_of_chapter), color = palette.foreground)
                    SleepUi.EndOfBook -> audio.own.bookplayer.ui.NoteRow(stringResource(R.string.stops_end_of_book), color = palette.foreground)
                    SleepUi.Off -> Unit
                }
                audio.own.bookplayer.ui.RowDivider()
                audio.own.bookplayer.ui.ButtonRow(stringResource(SR.string.turn_off), onClick = { actions.onCancelSleep(); onDone() }, color = palette.error)
            }
        }
        audio.own.bookplayer.ui.Section(header = null) {
            SleepTimer.Preset.entries.forEach { preset ->
                audio.own.bookplayer.ui.ButtonRow(pluralStringResource(R.plurals.n_minutes, preset.minutes, preset.minutes), onClick = { actions.onSleep(preset); onDone() }, color = palette.accent)
                audio.own.bookplayer.ui.RowDivider()
            }
            audio.own.bookplayer.ui.ButtonRow(stringResource(R.string.end_of_chapter), onClick = { actions.onSleepEnd(SleepEnd.END_OF_CHAPTER); onDone() }, color = palette.accent, enabled = hasBook)
            audio.own.bookplayer.ui.RowDivider()
            audio.own.bookplayer.ui.ButtonRow(stringResource(R.string.end_of_book), onClick = { actions.onSleepEnd(SleepEnd.END_OF_BOOK); onDone() }, color = palette.accent, enabled = hasBook)
        }
    }
}

@Composable
fun ChaptersSheet(state: PlaybackUi, onPick: (Long) -> Unit, onDismiss: () -> Unit) = Sheet(onDismiss) { ChaptersSheetContent(state, onPick, onDismiss) }

/** The book's chapters, the current one marked and scrolled to; a tap plays from there. */
@Composable
fun ChaptersSheetContent(state: PlaybackUi, onPick: (Long) -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    val current = state.chapterIndex
    val list = rememberLazyListState(initialFirstVisibleItemIndex = ((current ?: 0) - 3).coerceAtLeast(0))
    Column {
        SheetTitle(stringResource(R.string.chapters), onDone)
        LazyColumn(state = list) {
            itemsIndexed(state.chapters) { index, chapter ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable { onPick(chapter.startMs) }.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
                ) {
                    if (index == current) Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.playing), tint = palette.accent, modifier = Modifier.size(18.dp))
                    Text(
                        chapter.title,
                        color = if (index == current) palette.accent else palette.foreground,
                        fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(clock(chapter.durationMs), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
                }
                HorizontalDivider(Modifier.padding(start = Tokens.Spacing.lg), color = palette.border)
            }
        }
    }
}

@Composable
fun BookmarksSheet(bookmarks: List<Bookmark>?, onPick: (Long) -> Unit, onDismiss: () -> Unit) = Sheet(onDismiss) { BookmarksSheetContent(bookmarks, onPick, onDismiss) }

/** The book's bookmarks; a tap jumps there. Adding, renaming and deleting come with B8. */
@Composable
fun BookmarksSheetContent(bookmarks: List<Bookmark>?, onPick: (Long) -> Unit, onDone: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.padding(bottom = Tokens.Spacing.xl)) {
        SheetTitle(stringResource(R.string.bookmarks), onDone)
        when {
            bookmarks == null -> Box(Modifier.fillMaxWidth().padding(Tokens.Spacing.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            bookmarks.isEmpty() -> Text(stringResource(R.string.no_bookmarks_in_book), color = palette.muted, modifier = Modifier.padding(Tokens.Spacing.lg))
            else -> LazyColumn {
                itemsIndexed(bookmarks) { _, bookmark ->
                    Column(Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).clickable { onPick(bookmark.positionMs) }.padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm)) {
                        Text(bookmark.label ?: stringResource(R.string.bookmark), color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(clock(bookmark.positionMs), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
                    }
                    HorizontalDivider(Modifier.padding(start = Tokens.Spacing.lg), color = palette.border)
                }
            }
        }
    }
}
