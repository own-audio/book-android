package audio.own.bookplayer.imports

import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.discover
import audio.own.kit.ownaudio.me
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Library's + offers on one own.audio server. */
data class AddBookRights(
    /** `me.family.can_upload`: Import Audio Files. */
    val canImport: Boolean = false,
    /** `modules.narration` and `me.family.can_generate`: Narrate an Ebook. */
    val canNarrate: Boolean = false,
) {
    val any: Boolean get() = canImport || canNarrate
}

/**
 * The account's rights per server, asked once per run when the Library shows it (and again after
 * a refusal): members without the right to upload see no +, and narration is offered only where
 * the server has it.
 */
class AddBookRightsModel(private val client: (serverId: String) -> OwnAudioClient?) {
    private val _rights = MutableStateFlow<Map<String, AddBookRights>>(emptyMap())
    val rights: StateFlow<Map<String, AddBookRights>> = _rights.asStateFlow()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    /** Servers that refused an upload this run, whatever `/me` says (a read-only demo says yes). */
    private val refusedHere = ConcurrentHashMap.newKeySet<String>()

    suspend fun refresh(serverId: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        ask(serverId, force)
    }

    private suspend fun ask(serverId: String, force: Boolean) {
        if (!force && !asked.add(serverId)) return
        val client = client(serverId) ?: return
        try {
            val me = client.me()
            val narration = runCatching { client.discover().has("narration") }.getOrDefault(false)
            val canImport = me.family.canUpload && serverId !in refusedHere
            _rights.update { it + (serverId to AddBookRights(canImport, narration && me.family.canGenerate)) }
        } catch (e: CancellationException) {
            asked.remove(serverId)
            throw e
        } catch (_: OwnAudioException) {
            // Asked again next time the Library opens.
            asked.remove(serverId)
        }
    }

    /** A 403 said the right is gone: importing is no longer offered until the app starts again. */
    fun refused(serverId: String) {
        refusedHere += serverId
        _rights.update { rights -> rights + (serverId to (rights[serverId] ?: AddBookRights()).copy(canImport = false)) }
    }
}
