package audio.own.bookplayer.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import audio.own.book.library.Book
import audio.own.book.library.BookId
import audio.own.book.library.BookQuery
import audio.own.book.library.BookSource
import audio.own.book.library.ListeningProgress
import audio.own.book.library.Shelf
import audio.own.player.spoken.SpokenChapter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * What the car's screen shows, apart from the media service so tests can check it without a car:
 * the root's lists, which books Continue Listening holds, a row's progress, and the speed the
 * speed button goes to next. Ported from book-ios `CarPlayContent`.
 */
object AutoContent {
    /** The car's guidelines ask for short lists. */
    const val MAX_ITEMS = 100

    const val ROOT = "@root"
    const val RECENT = "@recent"
    const val CONTINUE = "@continue"
    const val DOWNLOADS = "@downloads"
    const val LIBRARY = "@library"
    const val SERIES = "@series"
    const val AUTHORS = "@authors"
    const val CHAPTERS = "@chapters"
    const val SERIES_PREFIX = "@series/"
    const val AUTHOR_PREFIX = "@author/"
    const val CHAPTER_PREFIX = "@chapter/"
    const val MESSAGE_PREFIX = "@message/"

    enum class RootEntry(val id: String) {
        CONTINUE_LISTENING(CONTINUE),
        DOWNLOADS(AutoContent.DOWNLOADS),
        LIBRARY(AutoContent.LIBRARY),
        SERIES(AutoContent.SERIES),
        AUTHORS(AutoContent.AUTHORS),
    }

    /**
     * Continue Listening first and the downloads next, because a car often has no signal. With
     * no server, no lists: the root says to add one on the phone.
     */
    fun rootEntries(hasServer: Boolean): List<RootEntry> = if (hasServer) RootEntry.entries else emptyList()

    /** The server's own Continue Listening shelf (offline, the started downloads: the offline source builds it). */
    fun continueListening(shelves: List<Shelf>): List<Book> {
        val content = shelves.firstOrNull { it.id == CONTINUE_LISTENING_SHELF }?.content as? Shelf.Content.Books ?: return emptyList()
        return content.books.take(MAX_ITEMS)
    }

    /** How far into a book, 0…1, for the row's bar; none for a finished or not started one. */
    fun progress(progress: ListeningProgress?, durationMs: Long): Double? {
        if (progress == null || durationMs <= 0 || progress.finished || progress.positionMs <= 0) return null
        return minOf(progress.positionMs.toDouble() / durationMs, 1.0)
    }

    /** 1× → 1.25× → … → 2× → 0.75× → 1×, the steps of the phone's speed settings. */
    val RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    fun nextRate(current: Float): Float = RATES.firstOrNull { it > current + 0.01f } ?: RATES.first()

    const val CONTINUE_LISTENING_SHELF = "continue-listening"
}

/**
 * The browse tree Android Auto (and any media browser) reads: the root's Continue Listening,
 * Downloads, Library, Series and Authors, a series' or an author's books, and the chapters of
 * the book playing. Rows carry their cover and how far the listener is; an empty list or a
 * failure is one row that says so.
 */
@UnstableApi
class AutoLibrary(
    private val library: Library,
    private val words: Words,
    /** A cover's address as the car may read it (a content URI), or null. */
    private val artwork: (serverId: String, book: Book) -> Uri? = { _, book -> book.coverUrl?.let(Uri::parse) },
) {
    interface Library {
        /** The server the tabs show, or null when there is none. */
        val serverId: String?

        /** Its library as the screens see it: the downloads alone while offline. */
        val source: BookSource?

        /** Its downloaded books, newest first. */
        fun downloaded(serverId: String): List<Book>

        /** The book in the player, its chapters and where it is. */
        val playing: PlaybackUi

        /** Where the listener is in each book of the server, as far as known. */
        suspend fun progress(serverId: String): Map<String, ListeningProgress>
    }

    interface Words {
        fun title(entry: AutoContent.RootEntry): String
        val chapters: String
        val addServer: String
        val nothingStarted: String
        val nothingDownloaded: String
        val nothingHere: String
        val noChapters: String

        fun books(count: Int): String

        fun chapter(number: Int): String

        fun failure(error: Throwable): String
    }

    /** Books the tree has listed, so a tap on one plays it without asking its server again. */
    private val listed = ConcurrentHashMap<String, Book>()

    fun known(compositeId: String): Book? = listed[compositeId]

    fun root(): MediaItem = folder(AutoContent.ROOT, "", browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)

    suspend fun children(parentId: String): List<MediaItem> {
        val serverId = library.serverId
        val source = library.source
        if (parentId == AutoContent.ROOT) {
            val entries = AutoContent.rootEntries(serverId != null && source != null)
            if (entries.isEmpty()) return listOf(message(words.addServer))
            return entries.map(::entry) + listOfNotNull(chaptersFolder())
        }
        if (parentId == AutoContent.CHAPTERS) return chapters().ifEmpty { listOf(message(words.noChapters)) }
        if (serverId == null || source == null) return listOf(message(words.addServer))
        return try {
            when {
                parentId == AutoContent.CONTINUE -> books(serverId, AutoContent.continueListening(source.shelves()), words.nothingStarted)
                parentId == AutoContent.DOWNLOADS -> books(serverId, library.downloaded(serverId).take(AutoContent.MAX_ITEMS), words.nothingDownloaded)
                parentId == AutoContent.LIBRARY -> books(serverId, source.books(BookQuery(), null, AutoContent.MAX_ITEMS).books, words.nothingHere)
                parentId == AutoContent.SERIES -> source.series().take(AutoContent.MAX_ITEMS).map { series ->
                    folder(AutoContent.SERIES_PREFIX + series.id, series.name, subtitle = words.books(series.bookCount), playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                }.ifEmpty { listOf(message(words.nothingHere)) }
                parentId == AutoContent.AUTHORS -> source.authors().take(AutoContent.MAX_ITEMS).map { person ->
                    folder(AutoContent.AUTHOR_PREFIX + person.id, person.name, subtitle = person.bookCount?.let(words::books), playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                }.ifEmpty { listOf(message(words.nothingHere)) }
                parentId.startsWith(AutoContent.SERIES_PREFIX) -> {
                    val id = parentId.removePrefix(AutoContent.SERIES_PREFIX)
                    books(serverId, source.series(id).books.orEmpty().take(AutoContent.MAX_ITEMS), words.nothingHere)
                }
                parentId.startsWith(AutoContent.AUTHOR_PREFIX) -> {
                    val id = parentId.removePrefix(AutoContent.AUTHOR_PREFIX)
                    books(serverId, source.books(BookQuery(authorId = id), null, AutoContent.MAX_ITEMS).books, words.nothingHere)
                }
                else -> emptyList()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            listOf(message(words.failure(error)))
        }
    }

    /** A row by its id, for a controller that asks for one item (the root's folders, a listed book). */
    fun item(id: String): MediaItem? = when {
        id == AutoContent.ROOT -> root()
        id == AutoContent.CHAPTERS -> chaptersFolder()
        id.startsWith("@") -> AutoContent.RootEntry.entries.firstOrNull { it.id == id }?.let(::entry)
        else -> known(id)?.let { book(BookId(id).serverId, it, null) }
    }

    /** Rows for books found elsewhere (a search in the car), on the server the tabs show. */
    suspend fun rows(serverId: String, found: List<Book>): List<MediaItem> = books(serverId, found.take(AutoContent.MAX_ITEMS), words.nothingHere)

    /** A row for a book that isn't in any list (the last book, for the system's "recent" root). */
    fun row(serverId: String, book: Book): MediaItem = book(serverId, book, null)

    /** The chapter at [index] of the book playing, for a tap on its row. */
    fun chapter(index: Int): SpokenChapter? = library.playing.chapters.getOrNull(index)

    private suspend fun books(serverId: String, books: List<Book>, empty: String): List<MediaItem> {
        if (books.isEmpty()) return listOf(message(empty))
        val progress = try {
            library.progress(serverId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
        return books.map { book ->
            listed[BookId.of(serverId, book.id).value] = book
            book(serverId, book, progress[book.id])
        }
    }

    private fun entry(entry: AutoContent.RootEntry): MediaItem = when (entry) {
        AutoContent.RootEntry.LIBRARY -> folder(entry.id, words.title(entry), playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        AutoContent.RootEntry.SERIES, AutoContent.RootEntry.AUTHORS -> folder(entry.id, words.title(entry), browsable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        else -> folder(entry.id, words.title(entry))
    }

    /** Only while a book with chapters of its own plays; one per file is no help in a car. */
    private fun chaptersFolder(): MediaItem? {
        val playing = library.playing
        return if (playing.hasBook && !playing.restored && playing.chapters.size > 1) folder(AutoContent.CHAPTERS, words.chapters) else null
    }

    private fun chapters(): List<MediaItem> {
        val playing = library.playing
        if (playing.restored) return emptyList()
        val current = playing.chapterIndex
        return playing.chapters.take(AutoContent.MAX_ITEMS).mapIndexed { index, chapter ->
            MediaItem.Builder()
                .setMediaId("${AutoContent.CHAPTER_PREFIX}$index")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(chapter.title.ifBlank { words.chapter(index + 1) })
                        .setSubtitle(clock(chapter.startMs))
                        .setArtist(if (index == current) playing.title else null)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                        .build(),
                )
                .build()
        }
    }

    private fun folder(id: String, title: String, subtitle: String? = null, browsable: Int? = null, playable: Int = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM): MediaItem {
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
            browsable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun book(serverId: String, book: Book, progress: ListeningProgress?): MediaItem {
        val extras = Bundle()
        val fraction = AutoContent.progress(progress, book.durationMs)
        when {
            progress?.finished == true -> extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
            fraction != null -> {
                extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, fraction)
            }
            else -> extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
        }
        val author = book.authorLine.ifBlank { null }
        return MediaItem.Builder()
            .setMediaId(BookId.of(serverId, book.id).value)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(author)
                    .setSubtitle(author)
                    .setArtworkUri(artwork(serverId, book))
                    .setDurationMs(book.durationMs.takeIf { it > 0 })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun message(text: String): MediaItem = MediaItem.Builder()
        .setMediaId("${AutoContent.MESSAGE_PREFIX}${text.hashCode()}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(text).setIsBrowsable(false).setIsPlayable(false).build())
        .build()

    private companion object {
        /** "1:05:00" or "12:30", where a chapter starts. */
        fun clock(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
