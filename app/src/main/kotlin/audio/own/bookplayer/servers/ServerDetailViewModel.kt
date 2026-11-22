package audio.own.bookplayer.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.servers.ConnectionMode
import audio.own.core.servers.ServerLibraryRef
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.app.UiText
import audio.own.bookplayer.app.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerDetailState(
    /** The server's version from its discovery, once it answered. */
    val version: String? = null,
    val deleting: Boolean = false,
    val error: UiText? = null,
    val addressError: UiText? = null,
    /** Removed or deleted: the page closes. */
    val gone: Boolean = false,
    /** An Audiobookshelf account's book libraries, once listed; null while asking. */
    val libraries: List<ServerLibraryRef>? = null,
)

/** One server's page: its connection, account, name, addresses, and removing it. */
class ServerDetailViewModel(
    val serverId: String,
    private val servers: ServersModel,
    private val detector: ServerDetector,
    private val log: AppLog,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerDetailState())
    val state: StateFlow<ServerDetailState> = _state.asStateFlow()
    val serversState: StateFlow<ServersState> = servers.state

    init {
        servers.aboutToUse(serverId)
        viewModelScope.launch {
            val record = servers.state.value.server(serverId) ?: return@launch
            val host = (servers.state.value.connections[serverId] as? ConnectionState.Connected)?.url ?: record.host
            if (record.kind == ServerKinds.OWN_AUDIO) {
                val found = detector.discovery(host)
                _state.update { it.copy(version = found?.version?.takeIf(String::isNotBlank)) }
            } else {
                _state.update { it.copy(libraries = record.libraries) }
                val libraries = servers.bookLibraries(serverId)
                _state.update { it.copy(libraries = libraries) }
                val status = detector.audiobookshelfStatus(host)
                _state.update { it.copy(version = status?.serverVersion?.takeIf(String::isNotBlank)) }
            }
        }
    }

    fun use() = viewModelScope.launch { servers.select(serverId) }

    fun setLibrary(libraryId: String) = viewModelScope.launch { servers.setLibrary(serverId, libraryId) }

    fun checkNow() = viewModelScope.launch { servers.checkNow(serverId) }

    fun rename(name: String) = viewModelScope.launch { servers.rename(serverId, name) }

    fun setConnection(mode: ConnectionMode) = viewModelScope.launch { servers.setConnection(serverId, mode) }

    fun addAddress(text: String) = viewModelScope.launch {
        try {
            servers.addAddress(serverId, text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.update { it.copy(addressError = e.userMessage()) }
        }
    }

    fun removeAddress(url: String) = viewModelScope.launch { servers.removeAddress(serverId, url) }

    fun dismissErrors() = _state.update { it.copy(error = null, addressError = null) }

    fun remove() {
        _state.update { it.copy(gone = true) }
        // Outlives the page, which closes at once.
        servers.scopeLaunch { servers.remove(serverId) }
    }

    fun deleteAccount(password: String?) {
        _state.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            try {
                servers.deleteAccount(serverId, password)
                _state.update { it.copy(gone = true) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.error("servers", "deleting the account failed: ${e.message}")
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(deleting = false) }
            }
        }
    }
}
