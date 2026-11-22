package audio.own.bookplayer.playback

import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.servers.ServerKinds
import audio.own.bookplayer.servers.ServersModel
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.Settings
import audio.own.kit.ownaudio.SettingsPatch
import audio.own.kit.ownaudio.settings
import audio.own.kit.ownaudio.updateSettings
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The skip intervals and the speed for new books follow an own.audio account
 * (`PATCH /me/settings` `audiobook{speed, skip_back_ms, skip_forward_ms}`): read when the
 * account signs in on this phone, and sent to every own.audio account when changed here.
 */
class AccountPlaybackSettings(
    private val servers: ServersModel,
    private val settings: PlaybackSettings,
    private val isOffline: () -> Boolean,
    private val log: AppLog,
    private val scope: CoroutineScope,
    /** For tests: the client of a server. */
    private val clientOf: (String) -> OwnAudioClient? = { servers.client(it) },
) {
    /** The choices as they were last read from or sent to the accounts. */
    @Volatile private var synced: Triple<Int, Int, Float>? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            var known: Set<String>? = null
            servers.state.collect { state ->
                if (!state.loaded) return@collect
                val ids = state.servers.filter { it.kind == ServerKinds.OWN_AUDIO }.map { it.id }.toSet()
                known?.let { before -> (ids - before).forEach { id -> launch { signedIn(id) } } }
                known = ids
            }
        }
        scope.launch {
            settings.prefs.map { Triple(it.skipBackSecs, it.skipForwardSecs, it.defaultSpeed) }
                .distinctUntilChanged().drop(1).debounce(1_000)
                .collect { if (it != synced) send(it) }
        }
    }

    /** A new sign-in: the account's choices replace this phone's; an account that has none gets this phone's. */
    suspend fun signedIn(serverId: String) {
        val client = clientOf(serverId) ?: return
        try {
            val account = client.settings()
            if (account.updatedAt == null) {
                val prefs = settings.prefs.value
                client.updateSettings(patch(Triple(prefs.skipBackSecs, prefs.skipForwardSecs, prefs.defaultSpeed)))
                return
            }
            val choices = choices(account.audiobook)
            synced = choices
            settings.update { it.copy(skipBackSecs = choices.first, skipForwardSecs = choices.second, defaultSpeed = choices.third) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("settings", "account settings not read: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun send(choices: Triple<Int, Int, Float>) {
        if (isOffline()) return
        synced = choices
        for (record in servers.state.value.servers.filter { it.kind == ServerKinds.OWN_AUDIO }) {
            val client = clientOf(record.id) ?: continue
            if (client.tokens?.current == null) continue
            try {
                client.updateSettings(patch(choices))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("settings", "account settings not saved: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        fun patch(choices: Triple<Int, Int, Float>) = SettingsPatch(
            audiobook = SettingsPatch.Audiobook(speed = choices.third.toDouble(), skipBackMs = choices.first * 1000L, skipForwardMs = choices.second * 1000L),
        )

        /** The account's values as this app's choices: the nearest skip interval and speed it offers. */
        fun choices(audiobook: Settings.Audiobook): Triple<Int, Int, Float> {
            fun skip(ms: Long) = PlaybackPrefs.SKIP_CHOICES.minBy { abs(it * 1000L - ms) }
            val speed = SPEEDS.minBy { abs(it - audiobook.speed.toFloat()) }
            return Triple(skip(audiobook.skipBackMs), skip(audiobook.skipForwardMs), speed)
        }
    }
}
