package audio.own.book.library.catalogue

import audio.own.kit.ownaudio.Bookmark as KitBookmark
import audio.own.kit.ownaudio.Book as KitBook
import audio.own.kit.ownaudio.Collection as KitCollection
import audio.own.kit.ownaudio.Contributor
import audio.own.kit.ownaudio.Favourite
import audio.own.kit.ownaudio.OwnAudioJson
import audio.own.kit.ownaudio.Progress
import audio.own.kit.ownaudio.Series as KitSeries
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * Writes the feed's records into the catalogue. Call it inside a transaction: a book is several
 * rows (its files, chapters, credits and series places), replaced together.
 */
internal class CatalogueWriter(private val dao: CatalogueDao) {

    /** Writes one record of the feed; kinds a book library doesn't keep are skipped. */
    suspend fun upsert(kind: String, record: JsonObject, gen: Long) {
        when (kind) {
            "book" -> decode(KitBook.serializer(), record)?.let { book(it, gen) }
            "contributor" -> decode(Contributor.serializer(), record)?.let { contributor(it, gen) }
            "series" -> decode(KitSeries.serializer(), record)?.let { dao.upsertSeries(SeriesRow(it.id, it.name, it.description, gen)) }
            "collection" -> decode(KitCollection.serializer(), record)?.let { collection(it, gen) }
            FAVOURITE -> decode(Favourite.serializer(), record)?.takeIf { it.kind == "book" }?.let {
                dao.upsertFavourite(FavouriteRow(it.id, it.createdAt.toEpochMilli(), gen))
            }
            PROGRESS -> decode(Progress.serializer(), record)?.takeIf { it.kind == "book" }?.let { progress(it, gen) }
            "bookmark" -> decode(KitBookmark.serializer(), record)?.takeIf { it.kind == "book" }?.let { bookmark(it, gen) }
        }
    }

    /** Removes what the feed says is gone, or no longer visible. */
    suspend fun delete(kind: String, id: String) {
        when (kind) {
            "book" -> {
                dao.deleteBook(id)
                children(id)
            }
            "contributor" -> dao.deleteContributor(id)
            "series" -> dao.deleteSeries(id)
            "collection" -> {
                dao.deleteCollection(id)
                dao.deleteCollectionBooks(id)
            }
            FAVOURITE -> dao.deleteFavourite(id)
            PROGRESS -> dao.deleteProgress(id)
            "bookmark" -> dao.deleteBookmark(id)
        }
    }

    suspend fun book(b: KitBook, gen: Long) = record(BookRecord.of(b, gen))

    suspend fun record(r: BookRecord) {
        dao.upsertBook(r.book)
        children(r.book.id)
        dao.insertCredits(r.credits)
        dao.insertFiles(r.files)
        dao.insertChapters(r.chapters)
        dao.insertSeriesBooks(r.series)
    }

    private suspend fun children(bookId: String) {
        dao.deleteFilesOf(bookId)
        dao.deleteChaptersOf(bookId)
        dao.deleteCreditsOf(bookId)
        dao.deleteSeriesOf(bookId)
    }

    suspend fun contributor(c: Contributor, gen: Long) = dao.upsertContributor(BookRecord.contributor(c, gen))

    suspend fun collection(c: KitCollection, gen: Long) = dao.upsertCollection(
        CollectionRow(c.id, c.name, c.description, c.visibility, c.ownerId, c.bookCount, c.image, c.updatedAt.toEpochMilli(), gen),
    )

    /** The books of a collection, replacing what was kept. */
    suspend fun collectionBooks(collectionId: String, rows: List<CollectionBookRow>) {
        dao.deleteCollectionBooks(collectionId)
        dao.insertCollectionBooks(rows)
    }

    suspend fun favourite(bookId: String, at: Instant, gen: Long) = dao.upsertFavourite(FavouriteRow(bookId, at.toEpochMilli(), gen))

    suspend fun progress(p: Progress, gen: Long) = dao.upsertProgress(
        ProgressRow(p.id, p.positionMs, p.finished, p.updatedAt.toEpochMilli(), p.fileId, p.filePositionMs, gen),
    )

    suspend fun bookmark(b: KitBookmark, gen: Long) = dao.upsertBookmark(
        BookmarkRow(b.id, b.itemId, b.positionMs, b.label, b.createdAt.toEpochMilli(), gen),
    )

    /** Every row of the catalogue, in one go. */
    suspend fun clear() {
        dao.clearBooks()
        dao.clearFiles()
        dao.clearChapters()
        dao.clearCredits()
        dao.clearContributors()
        dao.clearSeries()
        dao.clearSeriesBooks()
        dao.clearCollections()
        dao.clearCollectionBooks()
        dao.clearFavourites()
        dao.clearProgress()
        dao.clearBookmarks()
        dao.clearFeedState()
    }

    /** After a snapshot of generation [gen] ended: whatever it didn't send is gone from the server. */
    suspend fun dropOlderThan(gen: Long) {
        dao.deleteBooksBefore(gen)
        dao.deleteContributorsBefore(gen)
        dao.deleteSeriesBefore(gen)
        dao.deleteCollectionsBefore(gen)
        dao.deleteFavouritesBefore(gen)
        dao.deleteProgressBefore(gen)
        dao.deleteBookmarksBefore(gen)
        dao.deleteOrphanFiles()
        dao.deleteOrphanChapters()
        dao.deleteOrphanCredits()
        dao.deleteOrphanSeriesBooks()
        dao.deleteOrphanCollectionBooks()
    }

    // A record this version can't read is skipped rather than failing the whole sync: the API
    // only adds fields, so it is a server bug or a kind of record reused for something else.
    private fun <T> decode(serializer: KSerializer<T>, record: JsonObject): T? = try {
        OwnAudioJson.decodeFromJsonElement(serializer, record)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        /** The feed names state by what it is about. */
        const val FAVOURITE = "favourite.book"
        const val PROGRESS = "progress.book"
    }
}
