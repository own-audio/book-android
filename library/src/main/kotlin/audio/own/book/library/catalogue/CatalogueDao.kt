package audio.own.book.library.catalogue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CatalogueDao {
    // region Feed state

    @Query("SELECT * FROM feed_state WHERE id = 0")
    suspend fun feedState(): FeedStateRow?

    @Upsert
    suspend fun setFeedState(state: FeedStateRow)

    // endregion

    // region Writing records

    @Upsert suspend fun upsertBook(row: BookRow)
    @Upsert suspend fun upsertContributor(row: ContributorRow)
    @Upsert suspend fun upsertSeries(row: SeriesRow)
    @Upsert suspend fun upsertCollection(row: CollectionRow)
    @Upsert suspend fun upsertFavourite(row: FavouriteRow)
    @Upsert suspend fun upsertProgress(row: ProgressRow)
    @Upsert suspend fun upsertBookmark(row: BookmarkRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFiles(rows: List<BookFileRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChapters(rows: List<ChapterRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCredits(rows: List<BookCreditRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSeriesBooks(rows: List<SeriesBookRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCollectionBooks(rows: List<CollectionBookRow>)

    @Query("DELETE FROM book WHERE id = :id") suspend fun deleteBook(id: String)
    @Query("DELETE FROM book_file WHERE book_id = :bookId") suspend fun deleteFilesOf(bookId: String)
    @Query("DELETE FROM chapter WHERE book_id = :bookId") suspend fun deleteChaptersOf(bookId: String)
    @Query("DELETE FROM book_credit WHERE book_id = :bookId") suspend fun deleteCreditsOf(bookId: String)
    @Query("DELETE FROM series_book WHERE book_id = :bookId") suspend fun deleteSeriesOf(bookId: String)
    @Query("DELETE FROM contributor WHERE id = :id") suspend fun deleteContributor(id: String)
    @Query("DELETE FROM series WHERE id = :id") suspend fun deleteSeries(id: String)
    @Query("DELETE FROM collection WHERE id = :id") suspend fun deleteCollection(id: String)
    @Query("DELETE FROM collection_book WHERE collection_id = :collectionId") suspend fun deleteCollectionBooks(collectionId: String)
    @Query("DELETE FROM favourite WHERE book_id = :bookId") suspend fun deleteFavourite(bookId: String)
    @Query("DELETE FROM progress WHERE book_id = :bookId") suspend fun deleteProgress(bookId: String)
    @Query("DELETE FROM bookmark WHERE id = :id") suspend fun deleteBookmark(id: String)

    // endregion

    // region After a snapshot: what it didn't send is gone

    @Query("DELETE FROM book WHERE gen < :gen") suspend fun deleteBooksBefore(gen: Long)
    @Query("DELETE FROM contributor WHERE gen < :gen") suspend fun deleteContributorsBefore(gen: Long)
    @Query("DELETE FROM series WHERE gen < :gen") suspend fun deleteSeriesBefore(gen: Long)
    @Query("DELETE FROM collection WHERE gen < :gen") suspend fun deleteCollectionsBefore(gen: Long)
    @Query("DELETE FROM favourite WHERE gen < :gen") suspend fun deleteFavouritesBefore(gen: Long)
    @Query("DELETE FROM progress WHERE gen < :gen") suspend fun deleteProgressBefore(gen: Long)
    @Query("DELETE FROM bookmark WHERE gen < :gen") suspend fun deleteBookmarksBefore(gen: Long)

    @Query("DELETE FROM book_file WHERE book_id NOT IN (SELECT id FROM book)") suspend fun deleteOrphanFiles()
    @Query("DELETE FROM chapter WHERE book_id NOT IN (SELECT id FROM book)") suspend fun deleteOrphanChapters()
    @Query("DELETE FROM book_credit WHERE book_id NOT IN (SELECT id FROM book)") suspend fun deleteOrphanCredits()
    @Query("DELETE FROM series_book WHERE book_id NOT IN (SELECT id FROM book)") suspend fun deleteOrphanSeriesBooks()
    @Query("DELETE FROM collection_book WHERE collection_id NOT IN (SELECT id FROM collection)") suspend fun deleteOrphanCollectionBooks()

    // endregion

    // region Reading

    @Query("SELECT * FROM book") suspend fun books(): List<BookRow>
    @Query("SELECT * FROM book WHERE id = :id") suspend fun book(id: String): BookRow?
    @Query("SELECT COUNT(*) FROM book") suspend fun bookCount(): Int
    @Query("SELECT * FROM book_credit ORDER BY book_id, role, position") suspend fun credits(): List<BookCreditRow>
    @Query("SELECT * FROM book_credit WHERE book_id = :bookId ORDER BY role, position") suspend fun creditsOf(bookId: String): List<BookCreditRow>
    @Query("SELECT * FROM contributor") suspend fun contributors(): List<ContributorRow>
    @Query("SELECT * FROM contributor WHERE id IN (:ids)") suspend fun contributors(ids: List<String>): List<ContributorRow>
    @Query("SELECT COUNT(DISTINCT contributor_id) FROM book_credit WHERE role = 'author'") suspend fun authorCount(): Int
    @Query("SELECT * FROM book_file WHERE book_id = :bookId ORDER BY position") suspend fun filesOf(bookId: String): List<BookFileRow>
    @Query("SELECT * FROM chapter WHERE book_id = :bookId ORDER BY start_ms") suspend fun chaptersOf(bookId: String): List<ChapterRow>
    @Query("SELECT * FROM series_book") suspend fun seriesBooks(): List<SeriesBookRow>
    @Query("SELECT * FROM series_book WHERE book_id = :bookId") suspend fun seriesOf(bookId: String): List<SeriesBookRow>
    @Query("SELECT * FROM series") suspend fun seriesRows(): List<SeriesRow>
    @Query("SELECT * FROM collection ORDER BY name") suspend fun collections(): List<CollectionRow>
    @Query("SELECT * FROM collection_book ORDER BY collection_id, position") suspend fun collectionBooks(): List<CollectionBookRow>
    @Query("SELECT * FROM favourite") suspend fun favourites(): List<FavouriteRow>
    @Query("SELECT * FROM progress") suspend fun progress(): List<ProgressRow>
    @Query("SELECT * FROM progress WHERE book_id = :bookId") suspend fun progress(bookId: String): ProgressRow?
    @Query("SELECT * FROM bookmark ORDER BY position_ms") suspend fun bookmarks(): List<BookmarkRow>
    @Query("SELECT * FROM bookmark WHERE book_id = :bookId ORDER BY position_ms") suspend fun bookmarksOf(bookId: String): List<BookmarkRow>

    // endregion

    // region Starting over

    @Query("DELETE FROM book") suspend fun clearBooks()
    @Query("DELETE FROM book_file") suspend fun clearFiles()
    @Query("DELETE FROM chapter") suspend fun clearChapters()
    @Query("DELETE FROM book_credit") suspend fun clearCredits()
    @Query("DELETE FROM contributor") suspend fun clearContributors()
    @Query("DELETE FROM series") suspend fun clearSeries()
    @Query("DELETE FROM series_book") suspend fun clearSeriesBooks()
    @Query("DELETE FROM collection") suspend fun clearCollections()
    @Query("DELETE FROM collection_book") suspend fun clearCollectionBooks()
    @Query("DELETE FROM favourite") suspend fun clearFavourites()
    @Query("DELETE FROM progress") suspend fun clearProgress()
    @Query("DELETE FROM bookmark") suspend fun clearBookmarks()
    @Query("DELETE FROM feed_state") suspend fun clearFeedState()

    // endregion
}
