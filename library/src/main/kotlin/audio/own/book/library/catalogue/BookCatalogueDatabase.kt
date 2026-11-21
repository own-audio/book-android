package audio.own.book.library.catalogue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [
        BookRow::class, BookFileRow::class, ChapterRow::class, BookCreditRow::class, ContributorRow::class,
        SeriesRow::class, SeriesBookRow::class, CollectionRow::class, CollectionBookRow::class,
        FavouriteRow::class, ProgressRow::class, BookmarkRow::class, FeedStateRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BookCatalogueDatabase : RoomDatabase() {
    abstract fun dao(): CatalogueDao

    /** [block] in one transaction: all of it lands, or none. */
    suspend fun <T> inTransaction(block: suspend () -> T): T = withTransaction(block)

    companion object {
        /** The file of one server's catalogue; delete it with the server. */
        fun fileName(serverId: String) = "catalogue-$serverId.db"

        fun open(context: Context, serverId: String): BookCatalogueDatabase =
            Room.databaseBuilder(context.applicationContext, BookCatalogueDatabase::class.java, fileName(serverId))
                // A catalogue is a copy of the server's: when its shape changes, a fresh snapshot
                // is cheaper and safer than a migration.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        /** Removes a server's catalogue from the phone. */
        fun delete(context: Context, serverId: String): Boolean = context.applicationContext.deleteDatabase(fileName(serverId))
    }
}
