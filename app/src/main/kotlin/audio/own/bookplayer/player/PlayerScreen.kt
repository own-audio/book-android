package audio.own.bookplayer.player

import android.content.Context
import android.media.MediaRouter2
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import audio.own.book.library.Bookmark
import audio.own.bookplayer.R
import audio.own.bookplayer.browse.BookSymbol
import audio.own.bookplayer.browse.clock
import audio.own.bookplayer.browse.leftAtSpeed
import audio.own.bookplayer.browse.percent
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.bookplayer.playback.SleepUi
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.player.core.SleepTimer
import audio.own.player.spoken.SleepEnd
import audio.own.player.spoken.SpeedText
import audio.own.core.strings.R as SR

/** Which sheet of the player is up. */
enum class PlayerSheet { SPEED, SLEEP, CHAPTERS, BOOKMARKS }

/** What the player screen does; one place so the route and the tests wire it the same way. */
data class PlayerActions(
    val onClose: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onSkipBack: () -> Unit = {},
    val onSkipForward: () -> Unit = {},
    val onPreviousChapter: () -> Unit = {},
    val onNextChapter: () -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onSleep: (SleepTimer.Preset) -> Unit = {},
    val onSleepEnd: (SleepEnd) -> Unit = {},
    val onExtendSleep: () -> Unit = {},
    val onCancelSleep: () -> Unit = {},
    /** Plays from a chapter or bookmark, starting playback if paused. */
    val onPlayFrom: (Long) -> Unit = {},
)

/**
 * The full player (book-ios `PlayerView`): cover with the sleep badge, title, author, the
 * chapter button, the book bar over the chapter slider, the transport with the numbered skip
 * buttons, chapter steps, and the tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(
    state: PlaybackUi,
    actions: PlayerActions,
    bookmarks: List<Bookmark>? = null,
    onLoadBookmarks: () -> Unit = {},
    initialSheet: PlayerSheet? = null,
) {
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    val focus = remember { FocusRequester() }
    Scaffold(
        modifier = Modifier
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || sheet != null) return@onKeyEvent false
                when {
                    event.key == Key.Spacebar -> { actions.onToggle(); true }
                    event.key == Key.DirectionLeft && event.isCtrlPressed -> { actions.onSkipBack(); true }
                    event.key == Key.DirectionRight && event.isCtrlPressed -> { actions.onSkipForward(); true }
                    event.key == Key.Escape -> { actions.onClose(); true }
                    else -> false
                }
            },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = actions.onClose) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.close)) } },
                actions = { IconButton(onClick = actions.onStop) { Icon(Icons.Filled.Close, stringResource(R.string.stop)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        if (!state.hasBook) {
            EmptyState(BookSymbol, stringResource(R.string.nothing_playing), null, Modifier.padding(inner))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(bottom = Tokens.Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xl),
        ) {
            Cover(state)
            Titles(state, onChapters = { sheet = PlayerSheet.CHAPTERS })
            Scrubbers(state, actions.onSeek)
            Transport(state, actions)
            Tools(state, onSheet = { sheet = it; if (it == PlayerSheet.BOOKMARKS) onLoadBookmarks() })
        }
    }
    when (sheet) {
        PlayerSheet.SPEED -> SpeedSheet(state.speed, actions.onSpeed, onDismiss = { sheet = null })
        PlayerSheet.SLEEP -> SleepSheet(state.sleep, state.hasBook, actions, onDismiss = { sheet = null })
        PlayerSheet.CHAPTERS -> ChaptersSheet(state, onPick = { actions.onPlayFrom(it); sheet = null }, onDismiss = { sheet = null })
        PlayerSheet.BOOKMARKS -> BookmarksSheet(bookmarks, onPick = { actions.onPlayFrom(it); sheet = null }, onDismiss = { sheet = null })
        null -> Unit
    }
}

@Composable
private fun Cover(state: PlaybackUi) {
    val palette = LocalPalette.current
    Box(Modifier.padding(top = Tokens.Spacing.lg)) {
        CoverArt(state.cover, 260.dp, BookSymbol)
        val badge = when (val sleep = state.sleep) {
            is SleepUi.Countdown -> clock((sleep.remainingSecs * 1000).toLong())
            SleepUi.EndOfChapter -> stringResource(R.string.end_of_chapter)
            SleepUi.EndOfBook -> stringResource(R.string.end_of_book)
            SleepUi.Off -> null
        }
        if (badge != null) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(Tokens.Spacing.sm).background(palette.backgroundAlt.copy(alpha = 0.92f), CircleShape).padding(horizontal = Tokens.Spacing.sm, vertical = Tokens.Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                Icon(Icons.Filled.Bedtime, null, tint = palette.foreground, modifier = Modifier.size(14.dp))
                Text(badge, style = MaterialTheme.typography.labelMedium.tabular(), fontWeight = FontWeight.SemiBold, color = palette.foreground)
            }
        }
    }
}

@Composable
private fun Titles(state: PlaybackUi, onChapters: () -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.padding(horizontal = Tokens.Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
        Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = palette.foreground, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (state.author.isNotBlank()) Text(state.author, style = MaterialTheme.typography.titleSmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val chapter = state.chapter?.title ?: stringResource(R.string.chapters)
        val label = stringResource(R.string.chapter_label, state.chapter?.title.orEmpty())
        Row(
            Modifier.heightIn(min = Tokens.minTarget).clickable(onClick = onChapters).semantics { contentDescription = label }.padding(horizontal = Tokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
        ) {
            Text(chapter, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = palette.accent, modifier = Modifier.size(16.dp))
        }
    }
}

/** The thin book bar with the time left at speed; the chapter slider under it, seeking on release. */
@Composable
private fun Scrubbers(state: PlaybackUi, onSeek: (Long) -> Unit) {
    val palette = LocalPalette.current
    val chapter = state.chapter
    val start = chapter?.startMs ?: 0L
    val end = chapter?.endMs ?: state.durationMs
    val span = (end - start).coerceAtLeast(1)
    val inChapter = (state.positionMs - start).coerceIn(0, span)
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val shown = scrubbing?.toLong() ?: inChapter
    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = Tokens.Spacing.xl), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.weight(1f),
                color = palette.muted,
                trackColor = palette.border,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
            Text(percent(state.progress), style = MaterialTheme.typography.labelSmall.tabular(), color = palette.muted)
        }
        Row {
            Text(clock(state.positionMs), style = MaterialTheme.typography.labelSmall.tabular(), color = palette.muted)
            Spacer(Modifier.weight(1f))
            Text(leftAtSpeed(state.durationMs - state.positionMs, state.speed), style = MaterialTheme.typography.labelSmall.tabular(), color = palette.muted)
        }
        val value = stringResource(R.string.time_of, clock(shown), clock(span))
        Slider(
            value = shown.toFloat(),
            onValueChange = { scrubbing = it },
            onValueChangeFinished = { scrubbing?.let { onSeek(start + it.toLong()) }; scrubbing = null },
            valueRange = 0f..span.toFloat(),
            colors = SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent, inactiveTrackColor = palette.border),
            modifier = Modifier.semantics { stateDescription = value },
        )
        Row {
            Text(clock(shown), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
            Spacer(Modifier.weight(1f))
            Text("−" + clock((span - shown).coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
        }
        val saved = state.savedAheadMs
        if (state.waitingForSignal) {
            StatusLine(Icons.Filled.WifiOff, stringResource(R.string.no_signal_waiting), palette.foreground)
        } else if (saved != null) {
            StatusLine(Icons.Filled.DownloadForOffline, stringResource(R.string.min_saved, (saved / 60_000).toInt()), palette.muted)
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(Tokens.Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelMedium.tabular(), color = color)
    }
}

@Composable
private fun Transport(state: PlaybackUi, actions: PlayerActions) {
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    fun tap(action: () -> Unit): () -> Unit = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); action() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxl)) {
            IconButton(onClick = tap(actions.onSkipBack), modifier = Modifier.size(56.dp)) {
                SkipIcon(back = true, secs = state.skipBackSecs, size = 36.dp, contentDescription = pluralStringResource(R.plurals.back_seconds, state.skipBackSecs, state.skipBackSecs))
            }
            IconButton(onClick = tap(actions.onToggle), modifier = Modifier.size(72.dp)) {
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(48.dp))
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                        stringResource(if (state.isPlaying) SR.string.pause else SR.string.play),
                        tint = palette.foreground,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            IconButton(onClick = tap(actions.onSkipForward), modifier = Modifier.size(56.dp)) {
                SkipIcon(back = false, secs = state.skipForwardSecs, size = 36.dp, contentDescription = pluralStringResource(R.plurals.forward_seconds, state.skipForwardSecs, state.skipForwardSecs))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xxl)) {
            IconButton(onClick = tap(actions.onPreviousChapter)) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous_chapter), tint = palette.foreground) }
            IconButton(onClick = tap(actions.onNextChapter)) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next_chapter), tint = palette.foreground) }
        }
    }
}

@Composable
private fun Tools(state: PlaybackUi, onSheet: (PlayerSheet) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Row(Modifier.widthIn(max = 500.dp).fillMaxWidth().padding(top = Tokens.Spacing.sm)) {
        val speedTinted = state.speed != 1f
        Tool(stringResource(R.string.speed), stringResource(R.string.speed_label, SpeedText.number(state.speed)), speedTinted, { onSheet(PlayerSheet.SPEED) }) {
            Text(SpeedText.text(state.speed), style = MaterialTheme.typography.titleSmall.tabular(), fontWeight = FontWeight.SemiBold, color = if (speedTinted) palette.accent else palette.foreground)
        }
        val sleeping = state.sleep.isOn
        Tool(stringResource(R.string.sleep), stringResource(R.string.sleep_timer), sleeping, { onSheet(PlayerSheet.SLEEP) }) {
            Icon(if (sleeping) Icons.Filled.Bedtime else Icons.Outlined.Bedtime, null, tint = if (sleeping) palette.accent else palette.foreground)
        }
        Tool(stringResource(R.string.bookmarks), stringResource(R.string.bookmarks), false, { onSheet(PlayerSheet.BOOKMARKS) }) {
            Icon(Icons.Filled.BookmarkBorder, null, tint = palette.foreground)
        }
        Tool(stringResource(R.string.chapters), stringResource(R.string.chapters), false, { onSheet(PlayerSheet.CHAPTERS) }) {
            Icon(Icons.AutoMirrored.Filled.List, null, tint = palette.foreground)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Tool(stringResource(R.string.output), stringResource(R.string.output), false, { showOutputSwitcher(context) }) {
                Icon(Icons.Filled.SpeakerGroup, null, tint = palette.foreground)
            }
        }
    }
}

/** The system's own output switcher (speakers, headphones, Bluetooth); Android 14 and later. */
private fun showOutputSwitcher(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        MediaRouter2.getInstance(context).showSystemOutputSwitcher()
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tool(caption: String, label: String, tinted: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.weight(1f).heightIn(min = Tokens.minTarget).clickable(onClick = onClick).semantics { contentDescription = label }.padding(vertical = Tokens.Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
    ) {
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) { icon() }
        Text(caption, style = MaterialTheme.typography.labelSmall, color = if (tinted) palette.accent else palette.foreground, maxLines = 1)
    }
}
