package audio.own.bookplayer.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * What the media session drives. A controller that picks a book (Android Auto, the assistant,
 * playback resumption) goes through the session's callback, which has the app's model load it;
 * Media3 then hands the loaded book's parts to the player again. Here that second hand-over is
 * let go: loading the parts twice would open the book a second time and lose the model's start
 * position.
 */
@UnstableApi
class BookSessionPlayer(player: Player, private val loaded: () -> List<String>) : ForwardingPlayer(player) {
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        if (!isLoaded(mediaItems)) super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (!isLoaded(mediaItems)) super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (!isLoaded(mediaItems)) super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        if (!isLoaded(listOf(mediaItem))) super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (!isLoaded(listOf(mediaItem))) super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (!isLoaded(listOf(mediaItem))) super.setMediaItem(mediaItem, resetPosition)
    }

    private fun isLoaded(items: List<MediaItem>): Boolean {
        val ids = loaded()
        return ids.isNotEmpty() && items.map { it.mediaId } == ids
    }
}
