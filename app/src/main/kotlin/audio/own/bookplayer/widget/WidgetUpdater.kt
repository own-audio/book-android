package audio.own.bookplayer.widget

import android.content.Context
import audio.own.book.library.ListeningProgress
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.playback.PlaybackUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the widgets in step: the shelf is read again when the server shown changes, when
 * positions change and with the periodic refresh; the book in the player is followed on play,
 * pause and a new book, not on every tick (as on iOS). The file is written, and every widget
 * redrawn, only when the entries differ. The players are redrawn ([redrawPlayers]) on play, pause
 * and another book and, for the progress, once a minute while playing: never more often, and not
 * at all while paused.
 */
class WidgetUpdater(
    private val context: Context,
    private val store: WidgetStore,
    private val playback: StateFlow<PlaybackUi>,
    private val playingCoverUrl: () -> String?,
    /** The Continue Listening shelf of the server shown, or empty. */
    private val shelf: suspend () -> List<WidgetSnapshot.ShelfBook>,
    /** Where the listener is in each book of a server. */
    private val progress: suspend (serverId: String) -> Map<String, ListeningProgress>,
    private val log: AppLog,
    private val jpeg: suspend (WidgetSnapshot.Picked) -> ByteArray?,
    private val redraw: suspend () -> Unit = { WidgetRedraw.all(context) },
    private val redrawPlayers: suspend () -> Unit = { WidgetRedraw.players(context) },
    /** No widget on any home screen: nothing to read or write. */
    private val hasWidgets: suspend () -> Boolean = { WidgetRedraw.anyPlaced(context) },
    private val tickMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val writing = Mutex()
    private var books: List<WidgetSnapshot.ShelfBook> = emptyList()
    private var known: Map<String, Map<String, ListeningProgress>> = emptyMap()

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, changes: Flow<Unit>) {
        scope.launch { refresh() }
        scope.launch { changes.debounce(DEBOUNCE_MS).collect { refresh() } }
        scope.launch {
            playback.map { it.bookId to it.isPlaying }.distinctUntilChanged().drop(1).debounce(DEBOUNCE_MS).collect { write() }
        }
        scope.launch {
            playback.map { PlayingKey(it.bookId, it.isPlaying, it.restored) }.distinctUntilChanged().debounce(PLAYER_DEBOUNCE_MS).collectLatest { key ->
                drawPlayers()
                while (key.isPlaying) {
                    delay(tickMs)
                    drawPlayers()
                }
            }
        }
    }

    /** Reads the shelf again and writes what changed: the periodic refresh, a new server, new positions. */
    suspend fun refresh() {
        if (!hasWidgets()) return
        try {
            books = shelf()
            known = books.map { it.serverId }.distinct().associateWith { id -> runCatching { progress(id) }.getOrDefault(emptyMap()) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error("widget", "the shelf wasn't read: ${error.javaClass.simpleName}")
        }
        write()
    }

    private suspend fun drawPlayers() {
        try {
            if (hasWidgets()) redrawPlayers()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error("widget", "the player widgets weren't drawn: ${error.javaClass.simpleName}")
        }
    }

    private suspend fun write() = writing.withLock {
        try {
            if (!hasWidgets()) return@withLock
            val picked = WidgetSnapshot.pick(playback.value, playingCoverUrl(), books) { serverId, bookId -> known[serverId]?.get(bookId) }
            if (store.save(picked, now(), jpeg)) redraw()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.error("widget", "the snapshot wasn't written: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 1_000L

        /** Play then pause at once, or a skip through books, draws once. */
        const val PLAYER_DEBOUNCE_MS = 250L
    }
}

/** What the player widgets redraw for: another book, play/pause, or a remembered book loaded. */
data class PlayingKey(val bookId: String?, val isPlaying: Boolean, val restored: Boolean)
