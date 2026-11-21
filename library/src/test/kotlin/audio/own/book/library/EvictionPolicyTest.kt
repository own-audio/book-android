package audio.own.book.library

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvictionPolicyTest {
    private val now = Instant.ofEpochSecond(1_792_000_000)
    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    private val candidates = listOf(
        EvictionPolicy.Candidate("playing", 400, false, now),
        EvictionPolicy.Candidate("old", 300, false, daysAgo(30)),
        EvictionPolicy.Candidate("finished", 200, true, daysAgo(1)),
        EvictionPolicy.Candidate("recent", 100, false, daysAgo(2)),
    )

    @Test
    fun `no cap, nothing goes, under the cap, nothing goes`() {
        assertTrue(EvictionPolicy(null).evictions(candidates, null).isEmpty())
        assertTrue(EvictionPolicy(1000).evictions(candidates, null).isEmpty())
    }

    @Test
    fun `finished books go first, then the longest unplayed, until it fits, the playing book never`() {
        assertEquals(listOf("finished"), EvictionPolicy(900).evictions(candidates, "playing"))
        assertEquals(listOf("finished", "old"), EvictionPolicy(500).evictions(candidates, "playing"))
        assertEquals(listOf("finished", "old", "recent"), EvictionPolicy(50).evictions(candidates, "playing"))
        assertEquals(listOf("finished", "old", "recent", "playing"), EvictionPolicy(50).evictions(candidates, null))
    }
}
