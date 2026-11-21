package audio.own.book.library

import java.text.Collator
import java.util.Locale

/**
 * Titles and names in the order a person expects: by the reader's language, case and accents
 * second, and numbers by value, so "Part 2" comes before "Part 10" (Foundation's
 * `localizedStandardCompare`, which the iOS app sorts with).
 */
class NaturalOrder(locale: Locale = Locale.getDefault()) : Comparator<String> {
    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        val x = chunks(a)
        val y = chunks(b)
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p.first().isDigit() && q.first().isDigit()) compareNumbers(p, q) else collator.compare(p, q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size).takeIf { it != 0 } ?: collator.compare(a, b)
    }

    private fun compareNumbers(p: String, q: String): Int {
        val a = p.trimStart('0')
        val b = q.trimStart('0')
        return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    }

    private fun chunks(s: String): List<String> {
        if (s.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in s) {
            if (current.isNotEmpty() && current.last().isDigit() != ch.isDigit()) {
                out += current.toString()
                current.clear()
            }
            current.append(ch)
        }
        out += current.toString()
        return out
    }
}

/** Case-insensitive containment in the reader's language. */
internal fun String.containsText(needle: String, locale: Locale = Locale.getDefault()): Boolean =
    lowercase(locale).contains(needle.lowercase(locale))
