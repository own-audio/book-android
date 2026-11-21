package audio.own.bookplayer.servers

import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.app.UserFacingException
import audio.own.core.servers.TypedAddress
import audio.own.core.strings.R
import audio.own.kit.audiobookshelf.AudiobookshelfClient
import audio.own.kit.audiobookshelf.AudiobookshelfError
import audio.own.kit.audiobookshelf.AudiobookshelfHttp
import audio.own.kit.audiobookshelf.NetworkLog
import audio.own.kit.audiobookshelf.RateLimitPolicy
import audio.own.kit.audiobookshelf.ServerStatus
import audio.own.kit.ownaudio.Discovery
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.discover
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** What answered at a typed address. */
sealed interface Detected {
    /** An own.audio server new enough for this app, at [host]. */
    data class OwnAudio(val host: String, val discovery: Discovery) : Detected

    /** An Audiobookshelf server, at [host]. */
    data class Audiobookshelf(val host: String, val status: ServerStatus) : Detected
}

/**
 * Finds out what kind of server is at a typed address before anything is saved: each candidate
 * (https first, then http) is asked for own.audio's discovery, then for Audiobookshelf's
 * `/status`. A wrong guess is given up on after [timeoutMs].
 */
class ServerDetector(
    http: OkHttpClient,
    private val userAgent: String,
    private val log: AppLog,
    private val timeoutMs: Long = 6_000,
) {
    private val quick = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).followRedirects(true).build()

    // A probe that waited out a rate limit would hold up the address race.
    private val quickAbs = AudiobookshelfHttp(quick, userAgent, RateLimitPolicy(maxRetries = 0), NetworkLog { log.error("audiobookshelf", it) })

    fun client(host: String): OwnAudioClient = OwnAudioClient(baseUrl = host, http = quick, userAgent = userAgent, requestTimeoutMs = timeoutMs)

    /** The own.audio discovery at [host], or null when nothing like it answers there. */
    suspend fun discovery(host: String): Discovery? = try {
        client(host).discover()
    } catch (e: OwnAudioException) {
        null
    }

    /** Audiobookshelf's status at [host], or null when it isn't one or doesn't answer. */
    suspend fun audiobookshelfStatus(host: String): ServerStatus? {
        val url = host.toHttpUrlOrNull() ?: return null
        return try {
            AudiobookshelfClient.status(url, quickAbs)
        } catch (e: AudiobookshelfError) {
            null
        }
    }

    /**
     * @throws UserFacingException with "That doesn't look like a server address." for nothing
     *   to try, "Couldn't reach the server…" when no candidate answered, and the too-old message
     *   for an own.audio server without a new enough `/api/v2`.
     */
    suspend fun detect(typed: String): Detected {
        val candidates = TypedAddress.candidates(typed).filter { it.toHttpUrlOrNull() != null }
        if (candidates.isEmpty()) throw UserFacingException(R.string.error_invalid_address)
        var answered = false
        for (host in candidates) {
            try {
                val found = client(host).discover()
                found.requireV2()
                log.info("servers", "own.audio ${found.version} at ${hostOnly(host)}")
                return Detected.OwnAudio(host, found)
            } catch (e: OwnAudioException.TooOld) {
                throw UserFacingException(R.string.error_too_old, e)
            } catch (e: OwnAudioException.Network) {
                continue
            } catch (e: OwnAudioException) {
                answered = true
            }
            audiobookshelfStatus(host)?.let {
                log.info("servers", "Audiobookshelf ${it.serverVersion} at ${hostOnly(host)}")
                return Detected.Audiobookshelf(host, it)
            }
        }
        throw UserFacingException(if (answered) R.string.error_invalid_address else R.string.error_unreachable)
    }

    private fun hostOnly(url: String) = url.toHttpUrlOrNull()?.host ?: url
}
