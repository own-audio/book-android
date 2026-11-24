package audio.own.bookplayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.glance.ImageProvider
import audio.own.bookplayer.playback.PlaybackUi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** The book in the player as the widgets show it, read from the app's player when they are drawn. */
data class WidgetPlayer(
    val title: String,
    val author: String,
    /** The cover's file name in the widget folder; written by [WidgetStore] with the book's entry. */
    val cover: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** Time left at the listener's speed. */
    val remainingMs: Long = (durationMs - positionMs).coerceAtLeast(0),
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        /** What is loaded in the player; null for nothing, or only the last book remembered. */
        fun of(state: PlaybackUi): WidgetPlayer? {
            val id = state.bookId ?: return null
            if (state.restored) return null
            return WidgetPlayer(state.title, state.author, WidgetSnapshot.coverName(id), state.isPlaying, state.positionMs, state.durationMs, state.timeLeftAtSpeedMs)
        }
    }
}

/** Everything the three widgets draw. */
data class WidgetState(
    /** What is in the player; null when nothing is (the app was closed, or nothing was started). */
    val player: WidgetPlayer? = null,
    /** The book to continue, offered when [player] is null: the last one, or the first in progress. */
    val resume: WidgetSnapshot.Entry? = null,
    /** The books in progress. */
    val shelf: List<WidgetSnapshot.Entry> = emptyList(),
    /** Pictures by file name; one per name, so every size of a widget shares the same bitmap. */
    val covers: Map<String, ImageProvider> = emptyMap(),
    /** The listener's skip intervals, as the notification shows them. */
    val skipBackSecs: Int = 15,
    val skipForwardSecs: Int = 30,
)

/**
 * Reads the widgets' state: the snapshot file for the books in progress, the live player for
 * what plays. [version] goes up on every redraw, so a widget whose session is still running
 * reads again instead of drawing what it read last.
 */
object WidgetData {
    val version = MutableStateFlow(0L)

    fun folder(context: Context) = File(context.filesDir, "widget")

    suspend fun load(context: Context, playback: PlaybackUi?, shelfCovers: Boolean, coverPx: Int): WidgetState = withContext(Dispatchers.IO) {
        val store = WidgetStore(folder(context))
        val entries = store.load()?.entries.orEmpty()
        val player = playback?.let(WidgetPlayer::of)
        val resume = entries.firstOrNull()
        val names = buildSet {
            (player?.cover ?: resume?.cover)?.let(::add)
            if (shelfCovers) entries.mapNotNullTo(this) { it.cover }
        }
        val covers = names.mapNotNull { name -> decode(store.cover(name), coverPx)?.let { name to ImageProvider(it) } }.toMap()
        WidgetState(
            player = player,
            resume = if (player == null) resume else null,
            shelf = entries,
            covers = covers,
            skipBackSecs = playback?.skipBackSecs ?: 15,
            skipForwardSecs = playback?.skipForwardSecs ?: 30,
        )
    }

    /**
     * A picture no larger than [px] across: a home screen holds every widget's bitmaps in one
     * parcel with a size limit, so they are not decoded at the file's size when smaller will do.
     */
    fun decode(file: File, px: Int): Bitmap? {
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            if (bitmap.width <= px) bitmap else Bitmap.createScaledBitmap(bitmap, px, bitmap.height * px / bitmap.width, true)
        }.getOrNull()
    }
}
