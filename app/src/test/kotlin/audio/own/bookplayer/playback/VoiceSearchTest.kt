package audio.own.bookplayer.playback

import audio.own.book.library.Book
import audio.own.book.library.Person
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the assistant's requests find, in English and Czech, with or without accents. */
class VoiceSearchTest {
    private fun book(id: String, title: String, author: String = "") =
        Book(id = id, title = title, authors = if (author.isEmpty()) emptyList() else listOf(Person("p$id", author)), durationMs = 1, addedAt = Instant.EPOCH)

    private val babicka = book("1", "Babička", "Božena Němcová")
    private val machine = book("2", "The Time Machine", "H. G. Wells")
    private val pride = book("3", "Pride and Prejudice", "Jane Austen")
    private val emma = book("4", "Emma", "Jane Austen")

    @Test
    fun continuePhrasesGoOnWithTheBook() {
        for (phrase in listOf(null, "", "continue listening", "Continue my book", "resume my audiobook on own.book", "play", "Pokračovat v poslechu", "pokracuj", "Pusť dál moji knihu")) {
            assertTrue("$phrase", VoiceSearch.isContinue(phrase))
        }
        assertFalse(VoiceSearch.isContinue("play the time machine"))
        assertFalse(VoiceSearch.isContinue("přehraj Babičku"))
    }

    @Test
    fun theVerbAndTheAppNameAreLeftOut() {
        assertEquals("the time machine", VoiceSearch.strip("Play The Time Machine on own.book"))
        assertEquals("Babičku", VoiceSearch.bare("Přehraj Babičku v own.book"))
        assertEquals("Emma", VoiceSearch.bare("play the audiobook Emma"))
        // A title that is only a verb stays a title.
        assertEquals("Play", VoiceSearch.bare("Play"))
    }

    @Test
    fun wordsMatchWithoutAccentsAndInOtherForms() {
        assertEquals(listOf(babicka), VoiceSearch.ranked("prehraj babicku", listOf(machine, babicka, pride)))
        assertEquals(listOf(machine), VoiceSearch.ranked("play time machine", listOf(babicka, machine)))
        assertEquals(listOf(pride, emma).toSet(), VoiceSearch.ranked("play jane austen", listOf(babicka, pride, emma, machine)).toSet())
        assertTrue(VoiceSearch.ranked("play something else", listOf(babicka, machine, pride)).isEmpty())
    }

    @Test
    fun aTitleBeatsAnAuthor() {
        val austen = book("5", "Austen's Letters")
        assertEquals(austen, VoiceSearch.ranked("austen", listOf(pride, austen)).first())
    }

    @Test
    fun candidatesMergeDownloadsAndTheServerTenAtMost() {
        val server = (1..20).map { book("s$it", "Machine $it") } + machine
        val found = VoiceSearch.candidates("play the time machine", listOf(machine, babicka), server)
        assertEquals(10, found.size)
        assertEquals(machine, found.first())
        assertEquals(1, found.count { it.id == machine.id })
        // What the server matched stays, even where the words differ.
        assertEquals(listOf(emma), VoiceSearch.candidates("play emmah", emptyList(), listOf(emma)))
    }

    @Test
    fun offlineOnlyDownloadsMatch() {
        assertEquals(listOf(babicka), VoiceSearch.candidates("pusť babičku", listOf(babicka, machine), emptyList()))
        assertTrue(VoiceSearch.candidates("play emma", listOf(babicka, machine), emptyList()).isEmpty())
    }
}
