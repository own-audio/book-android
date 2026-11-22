package audio.own.book.library

/**
 * Descriptions as plain text. Audiobookshelf keeps them as HTML (`<p>`, `<br>`, `&amp;`), and
 * some come escaped twice (`&amp;amp;`), so the screens would show the markup: paragraphs and
 * line breaks become new lines, other tags go, entities are decoded (book-ios `strippingHTML`).
 */
object HtmlText {
    private val breaks = Regex("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>|</h[1-6]\\s*>")
    private val tags = Regex("<[^>]*>")
    private val entity = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val blankLines = Regex("\\n\\s*\\n\\s*(\\n\\s*)+")

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
        "rdquo" to "”", "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®", "trade" to "™",
        "shy" to "", "bull" to "•", "middot" to "·", "eacute" to "é", "aacute" to "á", "iacute" to "í",
        "oacute" to "ó", "uacute" to "ú", "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "szlig" to "ß",
    )

    /** [html] as text; null stays null, and text with no markup comes back trimmed. */
    fun plain(html: String?): String? {
        if (html == null) return null
        if (!html.contains('<') && !html.contains('&')) return html.trim()
        val text = html.replace("\r\n", "\n")
            .replace(breaks, "\n")
            .replace(tags, "")
        // Twice at most: an escaped "&amp;amp;" is one ampersand, a literal "&amp;" in the text is rare.
        var decoded = decode(text)
        if (decoded != text && entity.containsMatchIn(decoded)) decoded = decode(decoded)
        return decoded.lines().joinToString("\n") { it.replace(spaces, " ").trim() }
            .replace(blankLines, "\n\n")
            .trim()
    }

    private fun decode(text: String): String = entity.replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> named[name]
        } ?: match.value
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null
}
