package audio.own.bookplayer

import android.app.Application
import audio.own.bookplayer.servers.ConnectionState
import audio.own.core.servers.MobileData
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OwnAudioApp : Application() {
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
        // The mobile-data choice comes with the settings (B9); until then streaming is Original.
        container.scope.launch {
            combine(container.network.state, container.preferences.offlineMode) { network, offlineMode -> network to offlineMode }
                .collect { (network, offlineMode) -> container.offline.update(offlineMode, network, MobileData.ORIGINAL) }
        }
    }
}
