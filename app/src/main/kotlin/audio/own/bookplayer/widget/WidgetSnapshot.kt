package audio.own.bookplayer.widget

import android.net.Uri
import androidx.core.net.toUri
import audio.own.book.library.Book
import audio.own.book.library.BookId
import audio.own.book.library.ListeningProgress
import audio.own.bookplayer.playback.PlaybackUi
import audio.own.player.core.JsonFile
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

/**
 * What the Continue Listening widget shows: the app writes it to `filesDir/widget/` whenever its
 * entries change, the widget only reads. Covers sit next to it as small JPEGs, so the widget
 * needs neither the network nor a sign-in. Ported from book-ios `WidgetSnapshot`.
 */
@Serializable
data class WidgetSnapshot(val entries: List<Entry>, val atEpochMs: Long = 0) {
    @Serializable
    data class Entry(
        /** The composite book id, as `ownbook://play/<id>` wants it. */
        val id: String,
        val title: String,
        val author: String,
        /** 0…1 of the book listened to. */
        val fraction: Double,
        val remainingMs: Long,
        /** The cover's file name in the widget's folder, or null. */
        val cover: String? = null,
    )

    /** An entry with where its cover comes from, before the picture is on the device. */
    data class Picked(val entry: Entry, val serverId: String, val bookId: String, val coverUrl: String?)

    /** A book of the Continue Listening shelf, with its server. */
    data class ShelfBook(val serverId: String, val book: Book)

    companion object {
        const val SCHEME = "ownbook"
        const val MAX_ENTRIES = 3

        /** The link a tap opens; the composite id carries a `|`, so it is escaped whole. */
        fun playLink(id: String): Uri = "$SCHEME://play/${Uri.encode(id)}".toUri()

        /** The id back out of such a link, or null for any other link. */
        fun bookId(link: Uri): String? {
            if (link.scheme != SCHEME || link.host != "play") return null
            val encoded = link.toString().substringAfter("$SCHEME://play/", "").substringBefore('?').trimEnd('/')
            return Uri.decode(encoded).takeIf { it.isNotEmpty() }
        }

        /**
         * The book in the player (or the last one) first, then the Continue Listening shelf;
         * three at most. Fractions to the percent and time left to the minute, so a playing book
         * rewrites the file about once a minute at most, not with every tick.
         */
        fun pick(
            playing: PlaybackUi,
            /** The playing book's cover link, which [PlaybackUi] holds only as an image request. */
            playingCoverUrl: String?,
            shelf: List<ShelfBook>,
            progress: (serverId: String, bookId: String) -> ListeningProgress?,
        ): List<Picked> {
            val picked = mutableListOf<Picked>()
            val playingId = playing.bookId
            if (playingId != null && playing.serverId != null) {
                val id = BookId(playingId)
                val coverUrl = playingCoverUrl ?: shelf.firstOrNull { BookId.of(it.serverId, it.book.id).value == playingId }?.book?.coverUrl
                picked += Picked(entry(playingId, playing.title, playing.author, playing.positionMs, playing.durationMs, coverUrl), id.serverId, id.bookId, coverUrl)
            }
            for (item in shelf) {
                if (picked.size >= MAX_ENTRIES) break
                val composite = BookId.of(item.serverId, item.book.id).value
                if (picked.any { it.entry.id == composite }) continue
                val known = progress(item.serverId, item.book.id)
                val position = if (known?.finished == true) 0 else known?.positionMs ?: 0
                picked += Picked(entry(composite, item.book.title, item.book.authorLine, position, item.book.durationMs, item.book.coverUrl), item.serverId, item.book.id, item.book.coverUrl)
            }
            return picked
        }

        private fun entry(id: String, title: String, author: String, positionMs: Long, durationMs: Long, coverUrl: String?): Entry {
            val fraction = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
            return Entry(
                id = id,
                title = title,
                author = author,
                fraction = (fraction * 100).roundToLong() / 100.0,
                remainingMs = ((durationMs - positionMs).coerceAtLeast(0) / 60_000.0).roundToLong() * 60_000,
                cover = coverUrl?.let { coverName(id) },
            )
        }

        /** By book, not by link: own.audio's cover links carry a signature that changes. */
        fun coverName(compositeId: String): String =
            MessageDigest.getInstance("SHA-1").digest(compositeId.toByteArray()).joinToString("") { "%02x".format(it) }.take(20) + ".jpg"
    }
}

/** The snapshot's file and its pictures, in [folder] (`filesDir/widget`). */
class WidgetStore(private val folder: File) {
    private val file = JsonFile(File(folder, "snapshot.json"), WidgetSnapshot.serializer())

    fun load(): WidgetSnapshot? = runCatching { file.read() }.getOrNull()

    fun cover(name: String): File = File(folder, name)

    /**
     * Writes the picked entries when they differ from the file's, with the pictures they name
     * that aren't there yet ([jpeg] makes one) and without the ones no entry names any more.
     * Returns whether anything was written, so the widget is redrawn only then.
     */
    suspend fun save(picked: List<WidgetSnapshot.Picked>, atEpochMs: Long, jpeg: suspend (WidgetSnapshot.Picked) -> ByteArray?): Boolean {
        val entries = picked.map { pick ->
            val name = pick.entry.cover ?: return@map pick.entry
            val target = cover(name)
            if (target.exists()) return@map pick.entry
            val bytes = runCatching { jpeg(pick) }.getOrNull() ?: return@map pick.entry.copy(cover = null)
            folder.mkdirs()
            val partial = File(folder, "$name.part")
            partial.writeBytes(bytes)
            partial.renameTo(target)
            pick.entry
        }
        if (load()?.entries == entries) return false
        folder.mkdirs()
        file.write(WidgetSnapshot(entries, atEpochMs))
        val kept = entries.mapNotNull { it.cover }.toSet()
        folder.listFiles { f -> f.name.endsWith(".jpg") && f.name !in kept }?.forEach { it.delete() }
        return true
    }
}
