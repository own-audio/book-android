package audio.own.bookplayer

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import audio.own.core.sync.PeriodicRefresh
import audio.own.bookplayer.servers.ConnectionState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class OwnAudioApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A locked screen, the background and playing with the phone locked all stop the
        // activity: what only the listener looking at the app needs stops with it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.foreground.value = true
            }

            override fun onStop(owner: LifecycleOwner) {
                container.foreground.value = false
            }
        })
        container.log.info("app", "started ${container.versionName} (${container.versionCode})")
        container.scope.launch { container.servers.load() }
        // Audiobookshelf's icon, once a server is added or an address answers.
        container.scope.launch {
            container.servers.state.collect { state ->
                state.servers.forEach { record ->
                    val connected = (state.connections[record.id] as? ConnectionState.Connected)?.url ?: return@forEach
                    launch { container.serverIcons.load(record, connected) }
                }
            }
        }
        container.scope.launch {
            combine(container.network.state, container.preferences.offlineMode, container.playbackSettings.prefs) { network, offlineMode, prefs ->
                Triple(network, offlineMode, prefs.mobileData)
            }.collect { (network, offlineMode, mobileData) ->
                container.offline.update(offlineMode, network, mobileData)
                // Offline Mode, Downloaded Only on mobile data, or no network: live updates stop or start.
                container.sync.liveUpdates.refresh()
            }
        }
        // Positions saved while the app was away go out now, and every hour or so in the background.
        container.playbackModel.flush()
        // What the index doesn't know goes; what waited (killed mid-download) starts again.
        container.scope.launch { container.downloads.reconcile() }
        container.downloads.start()
        // Imports WorkManager lost (cleared data, a failed enqueue) start again; the rest it runs itself.
        runCatching { container.imports.resume() }
        container.sync.start()
        // The widget follows the server shown, positions made elsewhere and a library that grew.
        container.widget.start(
            container.scope,
            merge(container.sources.active.drop(1).map { }, container.playbackModel.progressChanged, container.libraryGrew.map { }),
        )
        container.accountSettings.start()
        runCatching {
            val work = WorkManager.getInstance(this)
            // The hourly job used to flush positions only; the refresh does that and the catalogues.
            work.cancelUniqueWork(OLD_PROGRESS_FLUSH)
            PeriodicRefresh.schedule(work)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(container.workerFactory).build()

    private companion object {
        const val OLD_PROGRESS_FLUSH = "progress-flush"
    }

    /** Covers through the app's client (no Authorization header), cached by record rather than by URL. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .build()
}
