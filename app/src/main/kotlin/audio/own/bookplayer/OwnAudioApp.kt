package audio.own.bookplayer

import android.app.Application
import audio.own.bookplayer.playback.ProgressFlushWorker
import audio.own.bookplayer.servers.ConnectionState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OwnAudioApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
            }.collect { (network, offlineMode, mobileData) -> container.offline.update(offlineMode, network, mobileData) }
        }
        // Positions saved while the app was away go out now, and every hour or so in the background.
        container.playbackModel.flush()
        ProgressFlushWorker.schedule(this)
    }

    /** Covers through the app's client (no Authorization header), cached by record rather than by URL. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .build()
}
