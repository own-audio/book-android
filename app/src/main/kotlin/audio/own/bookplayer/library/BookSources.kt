package audio.own.bookplayer.library

import android.content.Context
import android.os.Build
import audio.own.book.library.AudiobookshelfBookSource
import audio.own.book.library.BookSource
import audio.own.book.library.ChangeFeedCatalogue
import audio.own.book.library.OwnAudioBookSource
import audio.own.book.library.catalogue.BookCatalogueDatabase
import audio.own.book.library.catalogue.OwnAudioCatalogue
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.servers.ConnectionState
import audio.own.bookplayer.servers.ServerKinds
import audio.own.bookplayer.servers.ServersModel
import audio.own.bookplayer.servers.ServersState
import audio.own.core.servers.ServerRecord
import audio.own.kit.audiobookshelf.DeviceInfo
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The library the tabs show: one server (and on Audiobookshelf, one book library of it). */
data class ActiveLibrary(val record: ServerRecord, val source: BookSource) {
    val serverId: String get() = record.id
    val name: String get() = record.displayName
    val kind: String get() = record.kind
}

/** What playback needs of the servers' sources: one place for tests to stand in. */
interface PlaybackSources {
    /** The library the tabs show. */
    val active: StateFlow<ActiveLibrary?>

    /** The source for [serverId], or null for an unknown server or one without a sign-in. */
    fun source(serverId: String): BookSource?

    /** Brings a catalogue up to date. */
    suspend fun sync(source: ChangeFeedCatalogue)
}

/**
 * One [BookSource] per saved server, built from the server record and the address in use, and
 * rebuilt when either changes. own.audio servers share one catalogue database per server for the
 * whole run, so a new address doesn't mean a new snapshot.
 */
class BookSources(
    private val context: Context,
    private val servers: ServersModel,
    private val log: AppLog,
    scope: CoroutineScope,
    appVersion: String,
) : PlaybackSources {
    private val catalogues = ConcurrentHashMap<String, OwnAudioCatalogue>()
    private val built = ConcurrentHashMap<String, Pair<String, BookSource>>()
    private val syncing = Mutex()

    private val device = DeviceInfo(
        deviceId = deviceId(context),
        clientName = "own.audio Books",
        clientVersion = appVersion,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkVersion = Build.VERSION.SDK_INT.toString(),
    )

    override val active: StateFlow<ActiveLibrary?> = servers.state
        .map { state -> state.active?.let { record -> source(record.id, state)?.let { ActiveLibrary(record, it) } } }
        .distinctUntilChanged { a, b -> a?.source === b?.source && a?.record == b?.record }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // The active own.audio server's catalogue is brought up to date whenever it changes.
        scope.launch {
            active.collect { library ->
                val source = library?.source as? ChangeFeedCatalogue ?: return@collect
                launch { sync(source) }
            }
        }
    }

    /** The source for [serverId], or null for an unknown server or one without a sign-in. */
    override fun source(serverId: String): BookSource? = source(serverId, servers.state.value)

    fun source(serverId: String, state: ServersState): BookSource? {
        val record = state.server(serverId) ?: return null
        val key = listOf(record.kind, (state.connections[serverId] as? ConnectionState.Connected)?.url, record.connection.toString(), record.libraryId, record.auth.javaClass.name, record.userId).joinToString("|")
        built[serverId]?.takeIf { it.first == key }?.let { return it.second }
        val source: BookSource = when (record.kind) {
            ServerKinds.OWN_AUDIO -> {
                val client = servers.client(serverId) ?: return null
                OwnAudioBookSource(serverId, client, record.userId, catalogue(serverId))
            }
            ServerKinds.AUDIOBOOKSHELF -> {
                val client = servers.audiobookshelfClient(serverId) ?: return null
                val library = record.libraryId ?: record.libraries?.firstOrNull()?.id ?: return null
                AudiobookshelfBookSource(serverId, client, library, record.userId, device)
            }
            else -> return null
        }
        built[serverId] = key to source
        return source
    }

    fun catalogue(serverId: String): OwnAudioCatalogue =
        catalogues.getOrPut(serverId) { OwnAudioCatalogue(BookCatalogueDatabase.open(context, serverId)) }

    /** Brings a catalogue up to date; failures are logged and the copy on the phone stays. */
    override suspend fun sync(source: ChangeFeedCatalogue) {
        syncing.withLock {
            try {
                source.syncCatalogue()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.error("catalogue", "sync failed: ${e.message}")
            }
        }
    }

    /** A removed server's catalogue leaves the phone with it. */
    fun forget(serverId: String) {
        built.remove(serverId)
        catalogues.remove(serverId)?.database?.close()
        BookCatalogueDatabase.delete(context, serverId)
    }

    private companion object {
        fun deviceId(context: Context): String {
            val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
            return prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit { putString("id", it) } }
        }
    }
}
