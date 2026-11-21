package audio.own.bookplayer.playback

import audio.own.book.library.AudiobookshelfBookSource
import audio.own.book.library.BookId
import audio.own.book.library.OwnAudioBookSource
import audio.own.bookplayer.library.BookSources
import audio.own.kit.audiobookshelf.Tokens
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.playbackInfo
import audio.own.player.core.PlaybackLink
import audio.own.player.core.PlaybackLinkRequest
import audio.own.player.core.PlaybackLinkResolver
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The link for one file of a book, asked for when the player opens it: own.audio signs one per
 * file (`POST /playback-info`, kind `book_file`); Audiobookshelf serves the file with the access
 * token in the query, a fresh one after the server refused the last ([refused]).
 */
class BookLinkResolver(private val sources: BookSources) : PlaybackLinkResolver {
    /** Books (composite ids) whose last Audiobookshelf link was refused: the next one takes a new token. */
    private val refusedTokens = ConcurrentHashMap<String, String>()

    override suspend fun resolve(request: PlaybackLinkRequest): PlaybackLink {
        val id = BookId(request.mediaId)
        val fileId = request.fileId ?: throw IOException("No file named for ${request.mediaId}")
        val source = sources.source(id.serverId) ?: throw IOException("The server of ${request.mediaId} is gone")
        return when (source) {
            is OwnAudioBookSource -> try {
                val info = source.client.playbackInfo(BOOK_FILE, fileId, OwnAudioBookSource.playbackCapabilities(request.maxBitrateKbps))
                PlaybackLink(info.url, info.expiresAt.toEpochMilli(), info.contentType)
            } catch (e: OwnAudioException) {
                // No network, or offline by choice: an IOException, so the player waits for a signal.
                throw IOException("No link for $fileId: ${e.message}", e)
            }
            is AudiobookshelfBookSource -> {
                val client = source.client
                val token = refusedTokens.remove(request.mediaId)?.let { client.tokenAfterRefusal(it) } ?: client.freshToken()
                val expires = client.tokens.current?.expiresAtMillis?.takeIf { it != Tokens.NEVER }
                PlaybackLink(client.fileUrl(id.bookId, fileId, token).toString(), expires)
            }
            else -> throw IOException("No player links for this server")
        }
    }

    /** The server refused [url] (an Audiobookshelf 401): the next link for the book takes a new token. */
    fun refused(bookId: String, url: String?) {
        val token = url?.toHttpUrlOrNull()?.queryParameter("token") ?: return
        refusedTokens[bookId] = token
    }

    private companion object {
        const val BOOK_FILE = "book_file"
    }
}
