package audio.own.book.library.catalogue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// One own.audio server's library on the phone, as its change feed brought it. One database per
// server, so removing a server deletes one file and no query needs a server column.
//
// The records a feed sends whole (book, contributor, series, collection, favourite, progress,
// bookmark) carry `gen`: the snapshot that last wrote them. A fresh snapshot writes the new
// generation over the old rows in batches, and only once its `end` line has arrived are the rows
// it didn't send deleted. Until then the old library stays readable, and a snapshot cut short
// costs nothing but a resume. Times are milliseconds since 1970.

@Entity(tableName = "book")
data class BookRow(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    /** Null when the server doesn't know it; then the files' sum. */
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "published_year") val publishedYear: Int?,
    val publisher: String?,
    val visibility: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    /** The signed link as it came; sized when read. */
    val image: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** False when the record came without its files (then the detail is asked for). */
    @ColumnInfo(name = "has_files") val hasFiles: Boolean,
    @ColumnInfo(name = "has_chapters") val hasChapters: Boolean,
    val gen: Long,
)

@Entity(tableName = "book_file", indices = [Index("book_id")])
data class BookFileRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val position: Int,
    val title: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "content_type") val contentType: String?,
)

@Entity(tableName = "chapter", indices = [Index("book_id")])
data class ChapterRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val position: Int,
    val title: String?,
    @ColumnInfo(name = "start_ms") val startMs: Long,
)

@Entity(tableName = "book_credit", primaryKeys = ["book_id", "role", "position", "contributor_id"], indices = [Index("contributor_id")])
data class BookCreditRow(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "contributor_id") val contributorId: String,
    /** `author`, `narrator`, … */
    val role: String,
    val position: Int,
    @ColumnInfo(name = "credited_as") val creditedAs: String?,
)

@Entity(tableName = "contributor")
data class ContributorRow(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "sort_name") val sortName: String?,
    val image: String?,
    val gen: Long,
)

@Entity(tableName = "series")
data class SeriesRow(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val gen: Long,
)

/**
 * A book's place in a series, from the book's record: it names the series too, so the series
 * shows even before (or without) the series record.
 */
@Entity(tableName = "series_book", primaryKeys = ["series_id", "book_id"], indices = [Index("book_id")])
data class SeriesBookRow(
    @ColumnInfo(name = "series_id") val seriesId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "series_name") val seriesName: String,
    val position: Double?,
)

@Entity(tableName = "collection")
data class CollectionRow(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val visibility: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "book_count") val bookCount: Long,
    val image: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val gen: Long,
)

/** The feed's collection record has no books; these come from `GET /collections/{id}/books`. */
@Entity(tableName = "collection_book", primaryKeys = ["collection_id", "book_id"])
data class CollectionBookRow(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val position: Int,
)

@Entity(tableName = "favourite")
data class FavouriteRow(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val gen: Long,
)

@Entity(tableName = "progress")
data class ProgressRow(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    val finished: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "file_id") val fileId: String?,
    @ColumnInfo(name = "file_position_ms") val filePositionMs: Long?,
    val gen: Long,
)

@Entity(tableName = "bookmark", indices = [Index("book_id")])
data class BookmarkRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    val label: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val gen: Long,
)

/**
 * Where the feed was read up to: one row. [cursor] is null until a snapshot has ended. While a
 * snapshot is under way, it writes [snapshotGen]; [snapshotCursor] is its `begin` cursor and
 * [snapshotAfter] the last record written, so a snapshot cut short resumes from there.
 */
@Entity(tableName = "feed_state")
data class FeedStateRow(
    @PrimaryKey val id: Int = 0,
    val cursor: String?,
    /** The generation of the last snapshot that ended. */
    val gen: Long,
    @ColumnInfo(name = "snapshot_gen") val snapshotGen: Long?,
    @ColumnInfo(name = "snapshot_cursor") val snapshotCursor: String?,
    @ColumnInfo(name = "snapshot_after") val snapshotAfter: String?,
    @ColumnInfo(name = "synced_at") val syncedAt: Long?,
)
