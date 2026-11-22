package audio.own.bookplayer.playback

import audio.own.book.library.Book
import java.text.Normalizer
import java.util.Locale

/**
 * What the assistant, the car's search and a "play from search" intent asked for, matched to the
 * library. Ported from book-ios `BookIntents`: "continue listening" and "continue my book" go on
 * with the book in the player, else the last one, else the first under way; "play ‹book›" looks
 * in the downloads and asks the server, ten at most, the best first. Words are compared without
 * accents or case, so "Babicka" finds "Babička".
 */
object VoiceSearch {
    /** At most this many candidates, as on iOS. */
    const val MAX_RESULTS = 10

    /** Lower case, accents and punctuation gone, single spaces. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
        return decomposed.lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim().replace(SPACES, " ")
    }

    /** The bare request, folded, without "play" in front or the app's name behind. */
    fun strip(query: String): String = fold(bare(query))

    /**
     * The bare request as it was said (accents kept), for the server's own search: "Přehraj
     * Babičku v own.book" → "Babičku".
     */
    fun bare(query: String): String {
        var words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        // Counted in the words said: "own.book" is one word and two folded.
        val suffix = (minOf(MAX_PHRASE_WORDS, words.size - 1) downTo 1).firstOrNull { k -> fold(words.takeLast(k).joinToString(" ")) in APP_SUFFIXES }
        if (suffix != null) words = words.dropLast(suffix)
        val verb = (minOf(MAX_PHRASE_WORDS, words.size - 1) downTo 1).firstOrNull { k -> fold(words.take(k).joinToString(" ")) in VERBS }
        if (verb != null) words = words.drop(verb)
        return words.joinToString(" ")
    }

    /** Nothing named, or only "continue", "resume", "my book": go on with what was playing. */
    fun isContinue(query: String?): Boolean {
        if (query.isNullOrBlank()) return true
        val text = strip(query)
        return text.isEmpty() || text in CONTINUE_PHRASES || fold(query) in CONTINUE_PHRASES
    }

    /** How well [candidate] answers [query], 0 (not at all) to 100 (the same words). */
    fun score(query: String, candidate: String): Int {
        val q = fold(query)
        val c = fold(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c) return 100
        if (c.startsWith(q) || q.startsWith(c)) return 80
        if (c.contains(q)) return 70
        val words = q.split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return 0
        val theirs = c.split(' ').toSet()
        val hits = words.count { word -> theirs.any { it == word || (word.length >= 4 && it.startsWith(word.take(word.length - 1))) } }
        return if (hits == 0) 0 else 60 * hits / words.size
    }

    /** A book's score: by its title, or a little less by its author ("play something by Austen"). */
    fun score(query: String, book: Book): Int {
        val text = strip(query)
        return maxOf(score(text, book.title), score(text, book.authorLine) - 10)
    }

    /** Books that match, the best first; at least half the words must be there. */
    fun ranked(query: String, books: List<Book>): List<Book> =
        books.map { it to score(query, it) }.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }.map { it.first }

    /**
     * What to offer for "play ‹book›": the downloads that match and what the server's search
     * found (it matched on its own terms, so those stay even when the words differ), the best
     * first and a download before a stream when they tie; [MAX_RESULTS] at most. Offline the
     * server isn't asked and [found] is empty.
     */
    fun candidates(query: String, downloads: List<Book>, found: List<Book>): List<Book> {
        val local = ranked(query, downloads)
        val merged = (local + found).distinctBy { it.id }
        val scored = merged.mapIndexed { index, book -> Triple(book, score(query, book), index) }
        return scored.sortedWith(compareByDescending<Triple<Book, Int, Int>> { it.second }.thenBy { it.third }).map { it.first }.take(MAX_RESULTS)
    }

    private const val MIN_SCORE = 30
    private const val MAX_PHRASE_WORDS = 4
    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex(" +")
    private val WHITESPACE = Regex("\\s+")

    /** The longest that fits is taken, so "play the audiobook" goes before "play". */
    private val VERBS = setOf(
        "play the audiobook", "play the book", "play audiobook", "play book", "listen to", "put on", "play", "read",
        "prehraj audioknihu", "prehraj knihu", "pust audioknihu", "pust knihu", "prehraj mi", "pust mi", "prehraj", "prehrat", "pust", "pustit", "poslouchat",
    )

    /** "own.book" folds to "own book". */
    private val APP_SUFFIXES = setOf(
        "on own audio books", "in own audio books", "on own book", "in own book", "v own audio books", "v aplikaci own book", "v own book", "na own book", "own book",
    )

    private val CONTINUE_PHRASES = setOf(
        "play", "prehraj", "pust", "continue", "continue listening", "resume", "resume listening", "keep listening", "my book", "my audiobook", "book", "audiobook", "audiobooks",
        "continue my book", "continue my audiobook", "resume my book", "resume my audiobook", "continue the book",
        "pokracovat", "pokracuj", "pokracovat v poslechu", "pokracuj v poslechu", "pokracuj v knize", "pokracovat v knize", "moje kniha", "moji knihu",
        "pust dal moji knihu", "pokracuj v me knize", "dal", "kniha", "audiokniha",
    )
}
