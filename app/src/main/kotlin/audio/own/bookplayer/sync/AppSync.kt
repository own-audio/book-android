package audio.own.bookplayer.sync

import audio.own.book.library.ChangeFeedCatalogue
import audio.own.book.library.LiveLibraryUpdates
import audio.own.book.library.SourceException
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.library.BookSources
import audio.own.bookplayer.playback.PlaybackModel
import audio.own.bookplayer.servers.ServersModel
import audio.own.core.servers.NetworkState
import audio.own.core.sync.LiveUpdates
import audio.own.core.sync.RefreshWorkerFactory
import audio.own.kit.ownaudio.OwnAudioException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * How the phone hears from its servers (docs/android-foundation.md §9.2): live updates
 * (`GET /events`) from the own.audio server shown while a screen is open, and the hourly
 * background refresh, which sends the positions still queued, brings every own.audio
 * catalogue up to date and redraws the Continue Listening widget when its books changed.
 */
class AppSync(
    private val servers: ServersModel,
    private val sources: BookSources,
    private val playback: () -> PlaybackModel,
    private val isOffline: () -> Boolean,
    private val network: StateFlow<NetworkState>,
    private val log: AppLog,
    private val scope: CoroutineScope,
    /** The widget reads the shelf again with the periodic refresh. */
    private val refreshWidget: suspend () -> Unit = {},
) {
    val liveUpdates = LiveUpdates(
        scope = scope,
        canRun = { !isOffline() && !network.value.dataSaver && sources.active.value?.source is LiveLibraryUpdates },
        open = { (sources.active.value?.source as? LiveLibraryUpdates)?.libraryChanges() },
        // The catalogue catches up, and the screens read it again.
        onChange = { playback().refreshProgress() },
        isFatal = { it is SourceException.SignInRejected || it is SourceException.NotAllowed || it is OwnAudioException.SignInRefused || it is OwnAudioException.SignedOut },
        log = { log.info("live", it) },
    )

    val refreshWorkerFactory = RefreshWorkerFactory { backgroundRefresh() }

    @OptIn(FlowPreview::class)
    fun start() {
        // The network came back and settled, or went: listen again, or stop.
        scope.launch {
            network.map { it.connected && !it.dataSaver }.distinctUntilChanged().drop(1).debounce(3_000).collect { liveUpdates.refresh() }
        }
        // Another server shown: its stream, not the last one's.
        scope.launch {
            sources.active.map { it?.source }.distinctUntilChanged { a, b -> a === b }.drop(1).collect { liveUpdates.refresh(restart = true) }
        }
    }

    /** The periodic job: positions out, catalogues in, the widget brought up to date. True when nothing needs another go. */
    private suspend fun backgroundRefresh(): Boolean {
        servers.state.first { it.loaded }
        if (!isOffline()) {
            playback().outbox.flush()
            for (record in servers.state.value.servers) {
                val source = sources.source(record.id) as? ChangeFeedCatalogue ?: continue
                sources.sync(source)
            }
        }
        refreshWidget()
        return true
    }
}
