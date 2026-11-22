package audio.own.bookplayer.downloads

import audio.own.book.library.Book
import audio.own.book.library.BookFile
import audio.own.book.library.Chapter
import audio.own.book.library.Person
import audio.own.book.library.SeriesRef
import audio.own.book.library.Visibility
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A book as the downloads index keeps it, with every file and chapter: a downloaded book shows
 * and plays with no network and no catalogue. Each of its files' entries carries the whole
 * record, so any one of them is enough.
 */
@Serializable
data class BookRecordJson(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<PersonJson> = emptyList(),
    val narrators: List<PersonJson> = emptyList(),
    val series: List<SeriesJson> = emptyList(),
    val description: String? = null,
    val durationMs: Long,
    val addedAtMs: Long,
    val publishedYear: String? = null,
    val publisher: String? = null,
    val genres: List<String> = emptyList(),
    val language: String? = null,
    val coverUrl: String? = null,
    val files: List<FileJson> = emptyList(),
    val chapters: List<ChapterJson>? = null,
    val ownerId: String? = null,
    val visibility: String? = null,
) {
    @Serializable
    data class PersonJson(val id: String, val name: String)

    @Serializable
    data class SeriesJson(val id: String, val name: String, val sequence: String? = null)

    @Serializable
    data class FileJson(val id: String, val title: String? = null, val durationMs: Long, val startMs: Long, val sizeBytes: Long? = null, val contentType: String? = null)

    @Serializable
    data class ChapterJson(val title: String, val startMs: Long, val endMs: Long)

    fun book() = Book(
        id = id, title = title, subtitle = subtitle,
        authors = authors.map { Person(it.id, it.name) }, narrators = narrators.map { Person(it.id, it.name) },
        series = series.map { SeriesRef(it.id, it.name, it.sequence) },
        description = description, durationMs = durationMs, addedAt = Instant.ofEpochMilli(addedAtMs),
        publishedYear = publishedYear, publisher = publisher, genres = genres, language = language, coverUrl = coverUrl,
        files = files.map { BookFile(it.id, it.title, it.durationMs, it.startMs, it.sizeBytes, it.contentType) },
        chapters = chapters?.map { Chapter(it.title, it.startMs, it.endMs) },
        ownerId = ownerId, visibility = Visibility.of(visibility),
    )

    companion object {
        fun of(book: Book) = BookRecordJson(
            id = book.id, title = book.title, subtitle = book.subtitle,
            authors = book.authors.map { PersonJson(it.id, it.name) }, narrators = book.narrators.map { PersonJson(it.id, it.name) },
            series = book.series.map { SeriesJson(it.id, it.name, it.sequence) },
            description = book.description, durationMs = book.durationMs, addedAtMs = book.addedAt.toEpochMilli(),
            publishedYear = book.publishedYear, publisher = book.publisher, genres = book.genres, language = book.language,
            coverUrl = book.coverUrl,
            files = book.files.orEmpty().map { FileJson(it.id, it.title, it.durationMs, it.startMs, it.sizeBytes, it.contentType) },
            chapters = book.chapters?.map { ChapterJson(it.title, it.startMs, it.endMs) },
            ownerId = book.ownerId, visibility = book.visibility?.wire,
        )

        fun encode(book: Book): String = recordJson.encodeToString(serializer(), of(book))

        /** Null for a record this version can't read. */
        fun decode(text: String): Book? = runCatching { recordJson.decodeFromString(serializer(), text).book() }.getOrNull()
    }
}

internal val recordJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
