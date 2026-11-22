package audio.own.bookplayer.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import audio.own.core.servers.ServerRecord
import audio.own.bookplayer.R
import audio.own.bookplayer.servers.ServerKinds
import audio.own.core.strings.R as SR

/** The four tabs of book-ios, in its order. */
enum class Tab(@StringRes val title: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    LIBRARY(R.string.tab_library, Icons.AutoMirrored.Filled.LibraryBooks),
    DOWNLOADS(R.string.tab_downloads, Icons.Filled.DownloadForOffline),
    SEARCH(R.string.tab_search, Icons.Filled.Search),
}

/**
 * One entry of Home's server switch: a server, or on an Audiobookshelf account with several book
 * libraries, one of its libraries ("Server · Library"), since the shelves show one at a time.
 */
data class ServerChoice(val serverId: String, val libraryId: String?, val name: String, val kind: String)

fun serverChoices(servers: List<ServerRecord>): List<ServerChoice> = servers.flatMap { server ->
    val libraries = server.libraries.orEmpty()
    if (server.kind == ServerKinds.AUDIOBOOKSHELF && libraries.size > 1) {
        libraries.map { ServerChoice(server.id, it.id, "${server.displayName} · ${it.name}", server.kind) }
    } else {
        listOf(ServerChoice(server.id, null, server.displayName, server.kind))
    }
}

/** Which entry of [serverChoices] the library shows now. */
fun currentChoice(servers: List<ServerRecord>, activeId: String?): ServerChoice? {
    val active = servers.firstOrNull { it.id == activeId } ?: return null
    val choices = serverChoices(listOf(active))
    return choices.firstOrNull { it.libraryId == null || it.libraryId == active.libraryId } ?: choices.firstOrNull()
}

/**
 * The tabs, with the offline banner and the mini player above the navigation bar on every tab.
 * [content] draws a tab; without one, Home shows its bar over a placeholder.
 */
@Composable
fun TabsScreen(
    servers: List<ServerRecord> = emptyList(),
    activeId: String? = null,
    offlineMode: Boolean = false,
    onSelectServer: (String, String?) -> Unit = { _, _ -> },
    onOfflineMode: (Boolean) -> Unit = {},
    onSettings: () -> Unit = {},
    selected: Tab? = null,
    onSelect: ((Tab) -> Unit)? = null,
    banner: @Composable () -> Unit = {},
    miniPlayer: @Composable () -> Unit = {},
    content: (@Composable (Tab) -> Unit)? = null,
) {
    var own by rememberSaveable { mutableStateOf(Tab.HOME) }
    val tab = selected ?: own
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { item ->
                item(
                    selected = item == tab,
                    onClick = { if (onSelect != null) onSelect(item) else own = item },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { TabLabel(stringResource(item.title)) },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (content != null) {
                    content(tab)
                } else {
                    Scaffold(
                        topBar = { if (tab == Tab.HOME) HomeTopBar(servers, activeId, offlineMode, onSelectServer, onOfflineMode, onSettings) },
                    ) { inner ->
                        Box(Modifier.fillMaxSize().padding(inner).padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.placeholder_coming), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            banner()
            miniPlayer()
        }
    }
}

/** Home's bar: the server switch (with more than one choice), Offline Mode and Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    servers: List<ServerRecord>,
    activeId: String?,
    offlineMode: Boolean,
    onSelectServer: (String, String?) -> Unit,
    onOfflineMode: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val choices = serverChoices(servers)
    TopAppBar(
        title = { Text(stringResource(Tab.HOME.title), modifier = Modifier.semantics { heading() }) },
        actions = {
            if (choices.size > 1) ServerMenu(choices, currentChoice(servers, activeId), onSelectServer)
            IconToggleButton(checked = offlineMode, onCheckedChange = onOfflineMode) {
                Icon(if (offlineMode) Icons.Filled.WifiOff else Icons.Filled.Wifi, contentDescription = stringResource(R.string.offline_mode))
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(SR.string.settings)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun ServerMenu(choices: List<ServerChoice>, current: ServerChoice?, onSelect: (String, String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Dns, contentDescription = stringResource(SR.string.servers)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.name) },
                    leadingIcon = { Icon(if (choice.kind == ServerKinds.OWN_AUDIO) Icons.Filled.Group else Icons.Filled.Dns, contentDescription = null) },
                    trailingIcon = { if (choice == current) Icon(Icons.Filled.Check, contentDescription = stringResource(SR.string.selected)) },
                    onClick = { open = false; onSelect(choice.serverId, choice.libraryId) },
                )
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** The activity behind a (possibly wrapped) context: Credential Manager shows its sheet over it. */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * A tab's name on one line: at large font sizes "Downloads" shrinks to fit its tab instead of
 * breaking mid-word ("Downlo / ads").
 */
@Composable
internal fun TabLabel(text: String) {
    val size = LocalTextStyle.current.fontSize
    Text(
        text,
        maxLines = 1,
        autoSize = if (size.isSp) TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = size, stepSize = 0.5.sp) else null,
        modifier = Modifier.testTag("tab.label"),
    )
}
