package audio.own.book.library.catalogue

import audio.own.book.library.Book
import audio.own.book.library.BookCollection
import audio.own.book.library.Bookmark
import audio.own.book.library.ListeningProgress
import audio.own.book.library.NaturalOrder
import audio.own.book.library.Person
import audio.own.book.library.SeriesSummary
import audio.own.kit.ownaudio.OwnAudioImages

/**
 * The catalogue read into memory for browsing: the books without their files and chapters (the
 * detail reads those), the people credited on them, the listener's state. A library of a few
 * thousand books is a few megabytes; sorting and matching text in the reader's language is then
 * simple and exact, which SQLite's ASCII-only `LIKE` and `NOCASE` are not.
 */
internal class CatalogueLibrary(
    val books: List<Book>,
    val authors: List<Person>,
    val narrators: List<Person>,
    val series: List<SeriesSummary>,
    val collections: List<BookCollection>,
    val progress: Map<String, ListeningProgress>,
    val bookmarks: List<Bookmark>,
    val favourites: Set<String>,
) {
    val byId: Map<String, Book> = books.associateBy { it.id }

    companion object {
        suspend fun load(dao: CatalogueDao, order: NaturalOrder): CatalogueLibrary {
            val contributors = dao.contributors().associateBy { it.id }
            val credits = dao.credits().groupBy { it.bookId }
            val places = dao.seriesBooks().groupBy { it.bookId }
            val books = dao.books().map { row ->
                BookRecord(row, emptyList(), emptyList(), credits[row.id].orEmpty(), places[row.id].orEmpty()).toBook(contributors, withDetail = false)
            }
            val byId = books.associateBy { it.id }
            val seriesNames = dao.seriesRows().associate { it.id to it.name }
            val collectionBooks = dao.collectionBooks().groupBy { it.collectionId }
            return CatalogueLibrary(
                books = books,
                authors = people("author", books, contributors, order),
                narrators = people("narrator", books, contributors, order),
                series = seriesOf(books, order, seriesNames),
                collections = dao.collections().map { c ->
                    BookCollection(c.id, c.name, c.description, collectionBooks[c.id].orEmpty().sortedBy { it.position }.mapNotNull { byId[it.bookId] })
                },
                progress = dao.progress().associate { it.bookId to it.toProgress() },
                bookmarks = dao.bookmarks().map { it.toBookmark() },
                favourites = dao.favourites().mapTo(HashSet()) { it.bookId },
            )
        }

        /** One book with its files and chapters; null when the catalogue doesn't have it. */
        suspend fun detail(dao: CatalogueDao, id: String): Book? {
            val row = dao.book(id) ?: return null
            val credits = dao.creditsOf(id)
            val contributors = dao.contributors(credits.map { it.contributorId }.distinct()).associateBy { it.id }
            return BookRecord(row, dao.filesOf(id), dao.chaptersOf(id), credits, dao.seriesOf(id)).toBook(contributors)
        }

        /** Contributors credited in a role on at least one book, with how many. */
        fun people(role: String, books: List<Book>, contributors: Map<String, ContributorRow>, order: NaturalOrder): List<Person> {
            val counts = LinkedHashMap<String, Int>()
            for (book in books) {
                for (person in if (role == "author") book.authors else book.narrators) counts[person.id] = (counts[person.id] ?: 0) + 1
            }
            return counts.map { (id, count) ->
                val c = contributors[id]
                Person(id = id, name = c?.name ?: "", bookCount = count, imageUrl = c?.image?.let { OwnAudioImages.sized(it, 256) })
            }.sortedWith(compareBy(order) { it.name })
        }

        /** The series the books name, each with its books in series order. */
        fun seriesOf(books: List<Book>, order: NaturalOrder, names: Map<String, String> = emptyMap()): List<SeriesSummary> {
            val byId = LinkedHashMap<String, Pair<String, MutableList<Book>>>()
            for (book in books) {
                for (ref in book.series) byId.getOrPut(ref.id) { (names[ref.id] ?: ref.name) to mutableListOf() }.second += book
            }
            return byId.map { (id, entry) ->
                val ordered = entry.second.sortedWith(inSeries(id, order))
                SeriesSummary(id, entry.first, ordered.size, ordered)
            }.sortedWith(compareBy(order) { it.name })
        }

        fun inSeries(seriesId: String, order: NaturalOrder): Comparator<Book> =
            compareBy<Book> { b -> b.series.firstOrNull { it.id == seriesId }?.position ?: 0.0 }.thenBy(order) { it.title }
    }
}
