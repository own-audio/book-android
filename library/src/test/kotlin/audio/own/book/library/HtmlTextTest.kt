package audio.own.book.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlTextTest {
    @Test
    fun `plain text stays as it is`() {
        assertNull(HtmlText.plain(null))
        assertEquals("A quiet book.", HtmlText.plain("  A quiet book. "))
    }

    @Test
    fun `entities are decoded, also when escaped twice`() {
        assertEquals("Tom & Jerry", HtmlText.plain("Tom &amp; Jerry"))
        assertEquals("Tom & Jerry", HtmlText.plain("Tom &amp;amp; Jerry"))
        assertEquals("“Quoted” — it’s © 2024 é", HtmlText.plain("&ldquo;Quoted&rdquo; &mdash; it&#8217;s &copy; 2024 &#xE9;"))
        assertEquals("&unknown; stays", HtmlText.plain("&unknown; stays"))
    }

    @Test
    fun `tags go, paragraphs and breaks become lines`() {
        assertEquals(
            "First paragraph with bold.\n\nSecond\nline.",
            HtmlText.plain("<p>First paragraph with <b>bold</b>.</p>\n<p>Second<br/>line.</p>"),
        )
        assertEquals("1 < 2", HtmlText.plain("<div>1 &lt; 2</div>"))
    }
}
