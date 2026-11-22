package audio.own.bookplayer.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import audio.own.player.core.ListeningCache
import audio.own.player.core.PlaybackDataSources
import audio.own.player.spoken.SpokenPlayer
import audio.own.player.spoken.chapters.PartReaders
import audio.own.player.spoken.media.LookAheadLoadControl
import audio.own.player.spoken.media.Prefetcher
import audio.own.player.spoken.media.SignalWaitPolicy
import audio.own.player.spoken.media.SpokenMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * The one player of the process and what it reads through: the listening cache (1 GB), the
 * links, the look-ahead window and the signal wait. Built on the main thread, the first time
 * something plays or the media service starts.
 */
@UnstableApi
class PlaybackEngine(
    val spoken: SpokenPlayer,
    /** The look-ahead window "Keep Ahead" sets; null in tests. */
    val loadControl: LookAheadLoadControl?,
) {
    companion object {
        fun create(
            context: Context,
            resolver: BookLinkResolver,
            /** The bare client: stream links need no Authorization and must never carry it. */
            bareHttp: OkHttpClient,
            userAgent: String,
            isConnected: () -> Boolean,
            mainScope: CoroutineScope,
        ): PlaybackEngine {
            val cache = ListeningCache.open(context)
            val dataSources = PlaybackDataSources(context, resolver, bareHttp, cache, userAgent = userAgent)
            val loadControl = LookAheadLoadControl()
            val policy = SignalWaitPolicy(isConnected)
            val spoken = SpokenPlayer(
                player = SpokenPlayer.buildExoPlayer(context, SpokenMediaSourceFactory(context, dataSources, policy), loadControl),
                scope = mainScope,
                links = dataSources.links,
                prefetcher = Prefetcher(dataSources, mainScope),
                openPart = PartReaders(dataSources.links, bareHttp)::open,
                loadControl = loadControl,
            )
            spoken.onLinkFailed = { _, error ->
                // Audiobookshelf answers 401 for a token that ran out: the next link takes a new one.
                val refused = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val bookId = spoken.bookId
                if (refused?.responseCode == 401 && bookId != null) resolver.refused(bookId, refused.dataSpec.uri.toString())
            }
            return PlaybackEngine(spoken, loadControl)
        }
    }
}
