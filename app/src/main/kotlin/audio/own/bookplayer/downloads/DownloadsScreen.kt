package audio.own.bookplayer.downloads

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import audio.own.book.library.Book
import audio.own.bookplayer.R
import audio.own.bookplayer.browse.BookCover
import audio.own.bookplayer.ui.ButtonRow
import audio.own.bookplayer.ui.Page
import audio.own.bookplayer.ui.RowDivider
import audio.own.bookplayer.ui.Section
import audio.own.core.designsystem.components.EmptyState
import audio.own.core.designsystem.components.ValueRow
import audio.own.core.designsystem.theme.AppBrand
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.designsystem.theme.tabular
import audio.own.core.servers.ServerRecord
import audio.own.core.strings.R as SR

/** What every list and page knows of the downloads; the tabs provide it. */
val LocalBookDownloads = compositionLocalOf { BookDownloadsSnapshot() }

/** What a download control does; the tabs provide the real ones, tests and previews the defaults. */
data class DownloadActions(
    val offline: Boolean = false,
    val download: (serverId: String, book: Book) -> Unit = { _, _ -> },
    /** A whole set: [books] is asked for when tapped, so it can be every page of a list. */
    val downloadAll: (serverId: String, books: suspend () -> List<Book>) -> Unit = { _, _ -> },
    val remove: (serverId: String, books: List<Book>) -> Unit = { _, _ -> },
    val retry: (serverId: String, book: Book) -> Unit = { _, _ -> },
)

val LocalDownloadActions = staticCompositionLocalOf { DownloadActions() }

/** "1.2 GB", in the reader's units. */
@Composable
fun bytesText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** A failure as a row says it. */
@Composable
private fun failureText(code: String?): String = stringResource(
    when {
        code == null || code == "network" -> SR.string.error_network
        else -> R.string.download_server_did_not_send
    },
)

/**
 * What is on this device (book-ios `DownloadsView`): every downloaded book with its size, by
 * server when there is more than one, to open, play or remove; failed downloads can be tried
 * again together.
 */
@Composable
fun DownloadsContent(
    snapshot: BookDownloadsSnapshot,
    servers: List<ServerRecord>,
    onOpen: (DownloadedBook) -> Unit,
    onRemove: (DownloadedBook) -> Unit,
    onRetryFailed: () -> Unit,
    onRemoveAll: () -> Unit,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var confirmingRemoveAll by remember { mutableStateOf(false) }
    val books = snapshot.newestFirst
    Page(title = stringResource(R.string.tab_downloads), onBack = onBack, scrolls = books.isNotEmpty()) {
        if (books.isEmpty()) {
            EmptyState(Icons.Filled.DownloadForOffline, stringResource(R.string.nothing_downloaded), stringResource(R.string.downloads_empty_text))
            return@Page
        }
        if (snapshot.downloadingCount > 0 || snapshot.failedCount > 0) {
            Section(header = null) {
                if (snapshot.downloadingCount > 0) ValueRow(stringResource(R.string.downloading), snapshot.downloadingCount.toString(), onClick = null)
                if (snapshot.downloadingCount > 0 && snapshot.failedCount > 0) RowDivider()
                if (snapshot.failedCount > 0) {
                    ButtonRow(
                        pluralStringResource(R.plurals.retry_failed, snapshot.failedCount, snapshot.failedCount),
                        onClick = onRetryFailed,
                        icon = Icons.Filled.Refresh,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("downloads.retryFailed"),
                    )
                }
            }
        }
        val names = servers.associate { it.id to it.displayName }
        val byServer = books.groupBy { it.serverId }
        val groups: List<Pair<String?, List<DownloadedBook>>> = if (byServer.size <= 1) {
            listOf(null to books)
        } else {
            val known = servers.sortedBy { it.displayName.lowercase() }.mapNotNull { s -> byServer[s.id]?.let { s.displayName to it } }
            val gone = byServer.filterKeys { it !in names }.values.map { stringResource(R.string.removed_server) to it }
            known + gone
        }
        groups.forEach { (header, group) ->
            Section(header = header) {
                group.forEachIndexed { index, entry ->
                    if (index > 0) RowDivider(start = 84.dp)
                    DownloadRow(entry, names[entry.serverId] ?: stringResource(R.string.removed_server), onOpen = { onOpen(entry) }, onRemove = { onRemove(entry) })
                }
            }
        }
        Section(header = null, footer = stringResource(R.string.on_this_device_bytes, bytesText(snapshot.totalBytes))) {
            ButtonRow(
                stringResource(R.string.remove_all_downloads),
                onClick = { confirmingRemoveAll = true },
                color = LocalPalette.current.error,
                modifier = Modifier.testTag("downloads.removeAll"),
            )
        }
        Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
    }
    if (confirmingRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmingRemoveAll = false },
            title = { Text(stringResource(R.string.remove_all_downloads_question)) },
            text = { Text(stringResource(R.string.remove_all_downloads_message)) },
            confirmButton = {
                TextButton(onClick = { confirmingRemoveAll = false; onRemoveAll() }) {
                    Text(stringResource(R.string.remove_all_downloads), color = LocalPalette.current.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingRemoveAll = false }) { Text(stringResource(SR.string.cancel)) } },
        )
    }
}

@Composable
private fun DownloadRow(entry: DownloadedBook, serverName: String, onOpen: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onRemove()
            swipe.reset()
        }
    }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(palette.error).padding(horizontal = Tokens.Spacing.lg), contentAlignment = Alignment.CenterEnd) {
                Text(stringResource(SR.string.remove), color = Color.White)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(palette.backgroundAlt)
                .clickable(onClick = onOpen)
                .heightIn(min = 72.dp)
                .padding(horizontal = Tokens.Spacing.lg, vertical = Tokens.Spacing.sm)
                .testTag("downloads.${entry.book.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md),
        ) {
            BookCover(entry.serverId, entry.book, 56.dp, radius = AppBrand.radii.rowCover)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.book.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = palette.foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.book.authorLine.isNotBlank()) {
                    Text(entry.book.authorLine, style = MaterialTheme.typography.bodyMedium, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Which server it came from: with several, the same title can be on more than one.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Dns, null, tint = palette.muted, modifier = Modifier.size(12.dp))
                    Text(serverName, style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    when (val state = entry.state) {
                        is DownloadState.Downloading -> stringResource(R.string.downloading_percent, (state.fraction * 100).toInt())
                        is DownloadState.Failed -> failureText(state.code)
                        DownloadState.Downloaded -> bytesText(entry.bytes)
                    },
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = if (entry.state is DownloadState.Failed) palette.error else palette.muted,
                )
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, stringResource(SR.string.remove_download), tint = palette.muted) }
        }
    }
}

/** The download button beside Play: an arrow, a ring while downloading, a check when done. */
@Composable
fun DownloadButton(serverId: String, book: Book, modifier: Modifier = Modifier) {
    val state = LocalBookDownloads.current.state(serverId, book.id)
    val actions = LocalDownloadActions.current
    var confirmingRemove by remember { mutableStateOf(false) }
    val label = when (state) {
        DownloadState.Downloaded -> stringResource(SR.string.downloaded)
        is DownloadState.Downloading -> stringResource(R.string.downloading_percent_short, (state.fraction * 100).toInt())
        is DownloadState.Failed -> stringResource(R.string.download_failed_retry)
        null -> stringResource(SR.string.download)
    }
    FilledTonalIconButton(
        onClick = {
            when (state) {
                DownloadState.Downloaded -> confirmingRemove = true
                is DownloadState.Downloading -> Unit
                is DownloadState.Failed -> actions.retry(serverId, book)
                null -> actions.download(serverId, book)
            }
        },
        enabled = !(actions.offline && state == null),
        modifier = modifier.size(50.dp).testTag("book.download").semantics { contentDescription = label },
    ) {
        when (state) {
            DownloadState.Downloaded -> Icon(Icons.Filled.DownloadDone, null)
            is DownloadState.Downloading -> CircularProgressIndicator(
                progress = { state.fraction.coerceAtLeast(0.02f) },
                modifier = Modifier.size(22.dp),
                strokeWidth = 3.dp,
            )
            is DownloadState.Failed -> Icon(Icons.Filled.Refresh, null)
            null -> Icon(Icons.Outlined.Download, null)
        }
    }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text(stringResource(SR.string.remove_download_question)) },
            confirmButton = {
                TextButton(onClick = { confirmingRemove = false; actions.remove(serverId, listOf(book)) }) {
                    Text(stringResource(SR.string.remove_download), color = LocalPalette.current.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text(stringResource(SR.string.cancel)) } },
        )
    }
}

/**
 * The bar button for a whole set of books (a series, an author's, a collection, a filtered
 * list): an arrow, a ring with the count while they come, a check with a menu once all are
 * here. [all] is every book of the set, asked for on the tap, not only the pages shown.
 */
@Composable
fun SetDownloadButton(serverId: String, books: List<Book>, all: (suspend () -> List<Book>)? = null) {
    if (books.isEmpty()) return
    val snapshot = LocalBookDownloads.current
    val actions = LocalDownloadActions.current
    val done = books.count { snapshot.isDownloaded(serverId, it.id) }
    val active = books.any { snapshot.state(serverId, it.id) is DownloadState.Downloading }
    var menu by remember { mutableStateOf(false) }
    when {
        done == books.size -> Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("setDownload.done")) {
                Icon(Icons.Filled.DownloadForOffline, stringResource(R.string.all_downloaded), tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(SR.string.remove_these_downloads), color = LocalPalette.current.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = LocalPalette.current.error) },
                    onClick = { menu = false; actions.remove(serverId, books) },
                )
            }
        }
        active -> {
            val label = stringResource(SR.string.downloading_n_of_m, done, books.size)
            Box(
                Modifier.size(48.dp).testTag("setDownload.progress").semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { (done.toFloat() / books.size).coerceAtLeast(0.03f) },
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                )
                Text(done.toString(), fontSize = 9.sp, style = MaterialTheme.typography.labelSmall.tabular())
            }
        }
        else -> IconButton(
            onClick = { actions.downloadAll(serverId, all ?: { books }) },
            enabled = !actions.offline,
            modifier = Modifier.testTag("setDownload.start"),
        ) { Icon(Icons.Outlined.Download, stringResource(R.string.download_all)) }
    }
}

/** The badge in a card's corner: a ring while downloading, a check when the book is on the phone. */
@Composable
fun DownloadBadge(state: DownloadState?, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    when (state) {
        DownloadState.Downloaded -> Box(modifier.size(22.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Download, stringResource(SR.string.downloaded), tint = Color.White, modifier = Modifier.size(14.dp))
        }
        is DownloadState.Downloading -> Box(modifier.size(22.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { state.fraction.coerceAtLeast(0.02f) },
                modifier = Modifier.size(14.dp),
                color = Color.White,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent,
            )
        }
        else -> Unit
    }
}
