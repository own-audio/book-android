package audio.own.bookplayer.player

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.bookplayer.R
import audio.own.bookplayer.browse.BookSymbol
import audio.own.bookplayer.browse.leftAtSpeed
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R as SR

/**
 * The circular arrow with the interval written in, as iOS's `gobackward.15`. Drawn for every
 * interval, since Material has numbered icons for only some of them; forward is the mirror image.
 */
@Composable
fun SkipIcon(back: Boolean, secs: Int, size: Dp = 28.dp, contentDescription: String? = null) {
    val palette = LocalPalette.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.Replay,
            contentDescription = contentDescription,
            tint = palette.foreground,
            modifier = Modifier.size(size).then(if (back) Modifier else Modifier.scale(scaleX = -1f, scaleY = 1f)),
        )
        Text(
            "$secs",
            // Part of the glyph, so it scales with the icon, not with the font size setting.
            fontSize = with(LocalDensity.current) { (size * 0.27f).toSp() },
            lineHeight = with(LocalDensity.current) { (size * 0.3f).toSp() },
            fontWeight = FontWeight.Bold,
            color = palette.foreground,
            modifier = Modifier.padding(top = size * 0.1f),
        )
    }
}

/**
 * Above the navigation bar while a book is loaded: cover, title, time left at speed (or "No
 * signal — waiting"), play/pause and skip forward, the book's progress along the bottom. After
 * a relaunch the last book with a play symbol; a tap plays it and opens the player.
 */
@Composable
fun MiniPlayer(state: PlaybackUi, onOpen: () -> Unit, onToggle: () -> Unit, onSkipForward: () -> Unit, modifier: Modifier = Modifier) {
    if (!state.hasBook) return
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(AppBrand.radii.miniPlayer)
    val openLabel = stringResource(R.string.opens_the_player)
    val restoredLabel = stringResource(R.string.play_where_left_off, state.title)
    val open = {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        if (state.restored) onToggle()
        onOpen()
    }
    Surface(
        modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.xs),
        shape = shape,
        color = palette.backgroundAlt.copy(alpha = 0.92f),
    ) {
        Box(Modifier.clip(shape)) {
            Row(
                Modifier.clickable(onClick = open)
                    .semantics(mergeDescendants = true) {
                        onClick(label = openLabel) { open(); true }
                        if (state.restored) contentDescription = restoredLabel
                        progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f)
                    }
                    .padding(Tokens.Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(state.cover, 40.dp, BookSymbol, radius = AppBrand.radii.miniCover)
                Column(Modifier.weight(1f).padding(horizontal = Tokens.Spacing.md)) {
                    Text(state.title, style = MaterialTheme.typography.titleSmall, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (state.waitingForSignal) stringResource(R.string.no_signal_waiting) else leftAtSpeed(state.durationMs - state.positionMs, state.speed),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.restored) {
                    Icon(Icons.Filled.PlayArrow, null, tint = palette.foreground, modifier = Modifier.padding(Tokens.Spacing.md))
                } else {
                    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onToggle() }) {
                        when {
                            state.isLoading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            state.isPlaying -> Icon(Icons.Filled.Pause, stringResource(SR.string.pause), tint = palette.foreground)
                            else -> Icon(Icons.Filled.PlayArrow, stringResource(SR.string.play), tint = palette.foreground)
                        }
                    }
                    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onSkipForward() }) {
                        SkipIcon(back = false, secs = state.skipForwardSecs, size = 26.dp, contentDescription = pluralStringResource(R.plurals.skip_forward_seconds, state.skipForwardSecs, state.skipForwardSecs))
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(state.progress).height(2.dp).background(palette.accent))
        }
    }
}
