package audio.own.book.library

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTest {
    @Test
    fun `a timeline lays files end to end, a position finds its file and offset, and back`() {
        val files = BookFile.timeline(
            listOf(BookFile.Entry("a", "One", 1000, contentType = "audio/mpeg"), BookFile.Entry("b", "Two", 2000, contentType = "audio/mpeg"), BookFile.Entry("c", "Three", 500)),
        )
        assertEquals(listOf(0L, 1000L, 3000L), files.map { it.startMs })
        val book = Book(id = "x", title = "X", durationMs = 3500, addedAt = Instant.EPOCH, files = files)
        assertEquals("a", book.fileAt(0)!!.first.id)
        assertEquals("a" to 999L, book.fileAt(999)!!.let { it.first.id to it.second })
        assertEquals("b" to 0L, book.fileAt(1000)!!.let { it.first.id to it.second })
        assertEquals("c" to 400L, book.fileAt(3400)!!.let { it.first.id to it.second })
        assertEquals("c" to 500L, book.fileAt(9000)!!.let { it.first.id to it.second })
        assertEquals(1010L, book.position("b", 10))
        assertNull(book.position("zz", 10))
    }

    @Test
    fun `a series sequence is read as a number where it has one`() {
        val cases = listOf("1" to 1.0, "1.5" to 1.5, "2,5" to 2.5, "1-3" to 1.0, "Book 2" to null, "" to null)
        for ((text, position) in cases) assertEquals(text, position, SeriesRef("s", "S", text).position)
        assertNull(SeriesRef("s", "S", null).position)
    }

    @Test
    fun `book ids are composite and split back`() {
        val id = BookId.of("server-1", "b|1")
        assertEquals("server-1|b|1", id.value)
        assertEquals("server-1", id.serverId)
        assertEquals("b|1", id.bookId)
    }

    @Test
    fun `a plan's parts become the spoken engine's parts`() {
        val plan = PlayPlan(
            "b1",
            listOf(PlayPlan.Part("f1", "https://s/1", 0, 1000, "audio/mpeg", isHls = false), PlayPlan.Part("h", "https://s/h.m3u8", 0, 5000, null, isHls = true)),
            null, null, null,
        )
        val parts = plan.spokenParts()
        assertEquals(listOf("f1", "h"), parts.map { it.id })
        assertEquals(listOf(true, false), parts.map { it.isWindowable })
        assertEquals(listOf(false, true), parts.map { it.isHls })
    }

    @Test
    fun `natural order - numbers by value, case second`() {
        val order = NaturalOrder(java.util.Locale.ENGLISH)
        assertEquals(listOf("Part 1", "part 2", "Part 10"), listOf("Part 10", "part 2", "Part 1").sortedWith(order))
    }
}
