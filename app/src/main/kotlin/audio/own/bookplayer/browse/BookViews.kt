package audio.own.bookplayer.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.book.library.Book
import audio.own.book.library.ListeningProgress
import audio.own.book.library.SourceException
import audio.own.book.library.Visibility
import audio.own.bookplayer.R
import audio.own.bookplayer.app.UiText
import audio.own.bookplayer.app.UserFacingException
import audio.own.bookplayer.downloads.DownloadBadge
import audio.own.bookplayer.downloads.DownloadState
import audio.own.bookplayer.downloads.LocalBookDownloads
import audio.own.bookplayer.library.rememberBookCover
import audio.own.core.designsystem.components.CoverArt
import audio.own.core.designsystem.components.FamilyBadge
import audio.own.core.designsystem.components.NowPlayingIndicator
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import java.util.Locale
import kotlin.math.roundToLong
import audio.own.core.strings.R as SR

/** The books app's placeholder symbol, where iOS has `book.closed`. */
val BookSymbol = Icons.AutoMirrored.Filled.MenuBook

@Composable
fun BookCover(serverId: String, book: Book, size: Dp, modifier: Modifier = Modifier, radius: Dp = AppBrand.radii.cover) {
    CoverArt(rememberBookCover(serverId, book), size, BookSymbol, modifier, radius = radius)
}

/** How far into the book, 0–1; null when not started. */
fun progressFraction(book: Book, progress: ListeningProgress?): Float? {
    if (progress == null || book.durationMs <= 0 || (progress.positionMs <= 0 && !progress.finished)) return null
    return if (progress.finished) 1f else (progress.positionMs.toFloat() / book.durationMs).coerceAtMost(1f)
}

/**
 * A card on a shelf or in the grid: the cover with its badges, a progress bar once started,
 * then title and author. [playOverlay] for Continue Listening, where a tap plays.
 */
@Composable
fun BookCard(
    serverId: String,
    book: Book,
    progress: ListeningProgress?,
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
    isFavourite: Boolean = false,
    playOverlay: Boolean = false,
    dimmed: Boolean = false,
) {
    val palette = LocalPalette.current
    val fraction = progressFraction(book, progress)
    val label = listOf(book.title, book.authorLine).filter { it.isNotBlank() }.joinToString(", ")
    val download = LocalBookDownloads.current.state(serverId, book.id)
    // Offline, a book on the phone still plays: only the others are greyed.
    val dim = dimmed && download != DownloadState.Downloaded
    Column(
        modifier.width(width).alpha(if (dim) 0.45f else 1f).clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm),
    ) {
        Box {
            BookCover(serverId, book, width)
            if (book.visibility == Visibility.FAMILY) FamilyBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            DownloadBadge(download, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            if (isFavourite) {
                Icon(Icons.Filled.Favorite, null, tint = palette.accent, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(14.dp))
            }
            if (playOverlay) {
                Box(
                    Modifier.align(Alignment.Center).size(52.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
            }
        }
        if (fraction != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.weight(1f),
                    color = palette.accent,
                    trackColor = palette.border,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                Text(percent(fraction), style = MaterialTheme.typography.labelSmall.tabular(), color = palette.muted)
            }
        }
        Column {
            Text(
                book.title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.foreground,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(book.authorLine, style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A row in a list: a 56 dp cover, title, author, and how far along the book is. */
@Composable
fun BookRow(
    serverId: String,
    book: Book,
    progress: ListeningProgress?,
    modifier: Modifier = Modifier,
    nowPlaying: Boolean = false,
    isPlaying: Boolean = false,
    dimmed: Boolean = false,
    leading: String? = null,
) {
    val palette = LocalPalette.current
    val download = LocalBookDownloads.current.state(serverId, book.id)
    val dim = dimmed && download != DownloadState.Downloaded
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).alpha(if (dim) 0.45f else 1f).padding(horizontal = Tokens.Spacing.screenEdge, vertical = Tokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
    ) {
        if (leading != null) {
            Text(leading, style = MaterialTheme.typography.labelMedium.tabular(), color = palette.muted, modifier = Modifier.width(28.dp))
        }
        BookCover(serverId, book, 56.dp, radius = AppBrand.radii.rowCover)
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = palette.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.authorLine.isNotBlank()) {
                Text(book.authorLine, style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(progressSummary(book, progress), style = MaterialTheme.typography.bodySmall.tabular(), color = palette.muted)
        }
        if (nowPlaying) NowPlayingIndicator(isPlaying) else DownloadBadge(download)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = LocalPalette.current.foreground,
        modifier = modifier.padding(horizontal = Tokens.Spacing.screenEdge),
    )
}

@Composable
fun Gap(height: Dp) = Spacer(Modifier.size(height))

// Durations and positions as the screens say them (iOS ProgressText).

/** "4 h 12 min", "38 min". */
@Composable
fun lengthText(ms: Long): String {
    val minutes = (ms / 60_000.0).roundToLong().toInt()
    val hours = minutes / 60
    return when {
        hours > 0 && minutes % 60 == 0 -> stringResource(R.string.length_hours, hours)
        hours > 0 -> stringResource(R.string.length_hours_minutes, hours, minutes % 60)
        else -> stringResource(R.string.length_minutes, maxOf(minutes, 1))
    }
}

/** "4 h 12 min · 1 h 3 min left", "Finished", or the length alone. */
@Composable
fun progressSummary(book: Book, progress: ListeningProgress?): String {
    if (progress == null || (progress.positionMs <= 0 && !progress.finished)) return lengthText(book.durationMs)
    if (progress.finished) return stringResource(R.string.finished)
    return stringResource(R.string.length_and_left, lengthText(book.durationMs), lengthText((book.durationMs - progress.positionMs).coerceAtLeast(0)))
}

/** "2 h 10 min left" at the speed the book plays at. */
@Composable
fun leftAtSpeed(ms: Long, speed: Float): String =
    stringResource(R.string.time_left, lengthText((ms.coerceAtLeast(0) / speed.coerceAtLeast(0.1f).toDouble()).toLong()))

/** "1:02:07" or "3:07". */
fun clock(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

fun percent(fraction: Float): String = "${(fraction * 100).roundToLong()} %".let {
    // The reader's own percent sign placement is close enough: Czech and English both read it.
    if (Locale.getDefault().language == "en") it.replace(" ", "") else it
}

@Composable
fun booksCount(count: Int) = pluralStringResource(R.plurals.books_count, count, count)

/** A source's failure as the listener reads it. */
fun Throwable.sourceMessage(): UiText = when (this) {
    is UserFacingException -> text
    SourceException.SignInRejected -> UiText(SR.string.error_sign_in_refused)
    SourceException.NotAllowed -> UiText(SR.string.error_not_allowed)
    SourceException.NotFound -> UiText(SR.string.error_not_found)
    is SourceException.Http -> UiText.of(SR.string.error_http, status)
    SourceException.Unreadable -> UiText(SR.string.error_unreadable)
    SourceException.Network -> UiText(SR.string.error_network)
    SourceException.Offline -> UiText(SR.string.error_offline)
    else -> UiText(SR.string.error_generic)
}

/** Descriptions arrive as HTML from some servers. */
fun String.strippingHtml(): String = audio.own.book.library.HtmlText.plain(this).orEmpty()

