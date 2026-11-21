package audio.own.book.library.catalogue

import audio.own.book.library.Book
import audio.own.book.library.BookFile
import audio.own.book.library.Bookmark
import audio.own.book.library.Chapter
import audio.own.book.library.DevicePosition
import audio.own.book.library.ListeningProgress
import audio.own.book.library.Person
import audio.own.book.library.SeriesRef
import audio.own.book.library.Visibility
import audio.own.kit.ownaudio.Book as KitBook
import audio.own.kit.ownaudio.Bookmark as KitBookmark
import audio.own.kit.ownaudio.Contributor
import audio.own.kit.ownaudio.OwnAudioImages
import audio.own.kit.ownaudio.Progress
import java.time.Instant

/**
 * A book as the catalogue holds it: its row and the rows that hang off it. Records from the
 * server (the feed, the detail, a collection's list) become one of these first, so there is one
 * way from own.audio's records to the app's.
 */
internal data class BookRecord(
    val book: BookRow,
    val files: List<BookFileRow>,
    val chapters: List<ChapterRow>,
    val credits: List<BookCreditRow>,
    val series: List<SeriesBookRow>,
) {
    fun toBook(contributors: Map<String, ContributorRow>, withDetail: Boolean = true): Book {
        fun credited(role: String): List<Person> = credits.filter { it.role == role }.sortedBy { it.position }.map { credit ->
            val c = contributors[credit.contributorId]
            Person(id = credit.contributorId, name = credit.creditedAs ?: c?.name ?: "", imageUrl = c?.image?.let { OwnAudioImages.sized(it, 256) })
        }
        val timeline = if (book.hasFiles) {
            BookFile.timeline(files.sortedBy { it.position }.map { BookFile.Entry(it.id, it.title, it.durationMs ?: 0, it.sizeBytes, it.contentType) })
        } else {
            null
        }
        val duration = book.durationMs ?: timeline?.lastOrNull()?.endMs ?: 0
        val marks = if (book.hasChapters) {
            val sorted = chapters.sortedBy { it.startMs }
            sorted.mapIndexed { index, mark ->
                val end = if (index + 1 < sorted.size) sorted[index + 1].startMs else maxOf(duration, mark.startMs)
                // The words screens show for an unnamed chapter are theirs; this is the fallback
                // a record needs when it is shown anywhere else.
                Chapter(title = mark.title ?: "Chapter ${mark.position + 1}", startMs = mark.startMs, endMs = end)
            }
        } else {
            null
        }
        return Book(
            id = book.id,
            title = book.title,
            authors = credited("author"),
            narrators = credited("narrator"),
            series = series.map { SeriesRef(it.seriesId, it.seriesName, it.position?.let(::sequence)) },
            description = book.description,
            durationMs = duration,
            addedAt = Instant.ofEpochMilli(book.createdAt),
            publishedYear = book.publishedYear?.toString(),
            publisher = book.publisher,
            coverUrl = book.image?.let { OwnAudioImages.sized(it, 600) },
            files = if (withDetail) timeline else null,
            chapters = if (withDetail) marks else null,
            ownerId = book.ownerId.ifEmpty { null },
            visibility = Visibility.of(book.visibility),
        )
    }

    companion object {
        fun of(b: KitBook, gen: Long): BookRecord = BookRecord(
            book = BookRow(
                id = b.id, title = b.title, description = b.description, durationMs = b.durationMs, publishedYear = b.publishedYear,
                publisher = b.publisher, visibility = b.visibility, ownerId = b.ownerId, image = b.image,
                createdAt = b.createdAt.toEpochMilli(), updatedAt = b.updatedAt.toEpochMilli(),
                hasFiles = b.files != null, hasChapters = b.chapters != null, gen = gen,
            ),
            files = b.files.orEmpty().map { BookFileRow(it.id, b.id, it.position, it.title, it.durationMs, it.sizeBytes, it.contentType) },
            chapters = b.chapters.orEmpty().map { ChapterRow(it.id, b.id, it.position, it.title, it.startMs) },
            credits = b.credits.map { BookCreditRow(b.id, it.contributorId, it.role, it.position, it.creditedAs) },
            series = b.series.orEmpty().map { SeriesBookRow(it.seriesId, b.id, it.name, it.position) },
        )

        /** A series place as text: "2", or "1.5" where the publisher numbers between two. */
        fun sequence(position: Double): String =
            if (position == Math.rint(position) && !position.isInfinite()) position.toLong().toString() else position.toString()

        fun contributor(c: Contributor, gen: Long = 0) = ContributorRow(c.id, c.name, c.sortName, c.image, gen)
    }
}

internal fun ProgressRow.toProgress() = ListeningProgress(positionMs, finished, Instant.ofEpochMilli(updatedAt))

internal fun BookmarkRow.toBookmark() = Bookmark(id, bookId, positionMs, label, Instant.ofEpochMilli(createdAt))

internal fun Progress.toProgress() = ListeningProgress(
    positionMs = positionMs,
    finished = finished,
    updatedAt = updatedAt,
    devices = devices.orEmpty().map { DevicePosition(it.deviceId, it.deviceName, it.deviceKind, it.positionMs, it.finished, it.at, it.current) },
)

internal fun KitBookmark.toBookmark() = Bookmark(id, itemId, positionMs, label, createdAt)
