package audio.own.bookplayer

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import audio.own.core.servers.DiskAnswerCache
import audio.own.core.servers.KeystoreSecretBox
import audio.own.core.servers.NetworkMonitor
import audio.own.core.servers.OfflineSwitch
import audio.own.core.servers.PreferencesAddressMemory
import audio.own.core.servers.ServerStore
import audio.own.kit.ownaudio.AnswerCache
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.TokenSessions
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.app.AppPreferences
import audio.own.bookplayer.app.DataStorePreferences
import audio.own.bookplayer.app.LoggingInterceptor
import audio.own.bookplayer.library.BookSources
import audio.own.bookplayer.playback.DataStorePlaybackSettings
import audio.own.bookplayer.playback.PlaybackControls
import audio.own.bookplayer.playback.BookLinkResolver
import audio.own.bookplayer.playback.PlaybackEngine
import audio.own.bookplayer.playback.PlaybackModel
import androidx.media3.common.util.UnstableApi
import audio.own.bookplayer.playback.PlaybackSettings
import audio.own.bookplayer.servers.ServerDetector
import audio.own.bookplayer.servers.ServerIcons
import audio.own.bookplayer.servers.ServersModel
import audio.own.book.library.BookSource
import audio.own.book.library.OfflineBookSource
import audio.own.bookplayer.downloads.BookDownloads
import audio.own.bookplayer.playback.AccountPlaybackSettings
import audio.own.bookplayer.sync.AppSync
import audio.own.player.core.ProxyMemory
import androidx.work.DelegatingWorkerFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Hand-wired dependencies. Android Auto, the assistant and the widget can bind to the media
 * service without any screen having started, so the playback dependencies will be created here,
 * eagerly, rather than from the UI.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val log = AppLog(File(app.filesDir, "logs"))

    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE

    /** The hosted service's edge refuses requests without a proper User-Agent. */
    val userAgent = "own.audio Books/$versionName (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(LoggingInterceptor(log))
        .build()

    /**
     * For download links and storage redirects: no Authorization header and no request log, so
     * neither a token nor a signed link leaves through it (docs/android-foundation.md §7.2).
     */
    val bareHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .build()

    val preferences: AppPreferences = DataStorePreferences(app)
    val network = NetworkMonitor(app).also { it.start() }
    val offline = OfflineSwitch()
    val answers = DiskAnswerCache(File(app.cacheDir, "server-responses"))
    val tokenSessions = TokenSessions()
    val serverStore = ServerStore(File(app.filesDir, "servers.bin"), KeystoreSecretBox("own.book.servers")) { log.error("servers", it) }

    /** What the session list on the server shows for this device. */
    val device = OwnAudioClient.Device(
        name = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL,
        kind = "android",
    )

    val serverIcons = ServerIcons(File(app.filesDir, "server-icons"), http, userAgent, { offline.isOffline }, log)

    val servers: ServersModel = ServersModel(
        store = serverStore,
        sessions = tokenSessions,
        http = http,
        userAgent = userAgent,
        device = device,
        preferences = preferences,
        detector = ServerDetector(http, userAgent, log),
        addressMemory = PreferencesAddressMemory(app.getSharedPreferences("addresses", Context.MODE_PRIVATE)),
        network = network.state,
        cache = object : AnswerCache {
            override fun get(key: String) = answers.get(key)
            override fun put(key: String, bytes: ByteArray) = answers.put(key, bytes)
        },
        clearCache = { answers.clear() },
        isOffline = { offline.isOffline },
        acceptLanguage = { appLocale().toLanguageTag() },
        log = log,
        scope = scope,
        onRemoved = { id ->
            serverIcons.remove(id)
            sources.forget(id)
            playbackModel.outbox.forgetServer(id)
            // A removed server's downloads leave the device with it.
            scope.launch { downloads.removeAll(id) }
        },
    )

    val sources: BookSources = BookSources(app, servers, log, scope, versionName)

    val playbackSettings: PlaybackSettings = DataStorePlaybackSettings(app, scope)

    val covers = audio.own.bookplayer.library.CoverRefresher({ sources.source(it) }, scope)

    private val linkResolver = BookLinkResolver(sources)

    val downloads: BookDownloads = BookDownloads.create(
        context = app,
        bareHttp = bareHttp,
        userAgent = userAgent,
        proxyMemory = ProxyMemory(),
        sourceOf = { sources.source(it) },
        serversLoaded = { servers.state.first { it.loaded } },
        prefs = playbackSettings.prefs,
        playing = { playbackModel.state.value.bookId },
        isFinished = { playbackModel.outbox.local(it)?.finished == true },
        log = log,
        scope = scope,
    )

    /** The screens' library of a server: the downloads alone while offline, unless Whole Library is on. */
    fun screenSource(source: BookSource): BookSource = OfflineBookSource(
        inner = source,
        downloaded = { downloads.state.value.downloadedBooks(source.serverId) },
        isOffline = { offline.isOffline },
        wholeLibrary = { playbackSettings.prefs.value.wholeLibraryOffline },
    )

    val playbackModel: PlaybackModel = PlaybackModel(
        context = app,
        sources = sources,
        servers = servers,
        settings = playbackSettings,
        network = network.state,
        offline = offline,
        log = log,
        filesDir = app.filesDir,
        engineFactory = { PlaybackEngine(app, linkResolver, http, userAgent, { network.state.value.connected }, kotlinx.coroutines.MainScope()) },
        downloads = downloads,
    )

    val sync = AppSync(servers, sources, { playbackModel }, { offline.isOffline }, network.state, log, scope)

    val accountSettings = AccountPlaybackSettings(servers, playbackSettings, { offline.isOffline }, log, scope)

    /** WorkManager's workers, with their dependencies: downloads and the periodic refresh. */
    val workerFactory = DelegatingWorkerFactory().apply {
        addFactory(BookDownloads.workerFactory { downloads })
        addFactory(sync.refreshWorkerFactory)
    }

    /** What the screens play through. */
    val playback: PlaybackControls = playbackModel

    /** The header of a shared log: which app, which system. */
    val logHeader: String get() = "own.book $versionName ($versionCode), Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"

    private fun appLocale(): Locale = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
}
