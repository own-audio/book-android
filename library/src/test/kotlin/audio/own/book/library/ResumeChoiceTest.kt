package audio.own.book.library

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeChoiceTest {
    private val t0 = Instant.ofEpochSecond(1_792_000_000)

    private fun progress(ms: Long, at: Instant, finished: Boolean = false, devices: List<DevicePosition> = emptyList()) =
        ListeningProgress(ms, finished, at, devices)

    private fun device(name: String, ms: Long, at: Instant, thisDevice: Boolean = false) =
        DevicePosition(name, name, "android", ms, false, at, thisDevice)

    @Test
    fun `nothing known - the server's position, or the start`() {
        assertEquals(ResumeChoice.Start(0), ResumeChoice.decide(null, null))
        assertEquals(ResumeChoice.Start(5000), ResumeChoice.decide(null, progress(5000, t0)))
        assertEquals(ResumeChoice.Start(9000), ResumeChoice.decide(progress(9000, t0), null))
        assertEquals(ResumeChoice.Start(0), ResumeChoice.decide(progress(9000, t0), progress(90_000, t0, finished = true)))
        assertEquals(ResumeChoice.Start(0), ResumeChoice.decide(progress(9000, t0, finished = true), null))
    }

    @Test
    fun `the same place within the tolerance - the server's`() {
        assertEquals(ResumeChoice.Start(110_000), ResumeChoice.decide(progress(100_000, t0), progress(110_000, t0.plusSeconds(60))))
    }

    @Test
    fun `another device went further since this phone saved - ask, naming it`() {
        val server = progress(
            400_000, t0.plusSeconds(3600),
            devices = listOf(device("Pixel", 100_000, t0, thisDevice = true), device("Car", 400_000, t0.plusSeconds(3600))),
        )
        assertEquals(ResumeChoice.Ask(100_000, 400_000, "Car"), ResumeChoice.decide(progress(100_000, t0), server))
    }

    @Test
    fun `the server differs but it is this phone's own older report, or a server without devices - the newer wins`() {
        val onlyMine = progress(400_000, t0, devices = listOf(device("Pixel", 400_000, t0, thisDevice = true)))
        assertEquals(ResumeChoice.Start(100_000), ResumeChoice.decide(progress(100_000, t0.plusSeconds(60)), onlyMine))
        assertEquals(ResumeChoice.Start(400_000), ResumeChoice.decide(progress(100_000, t0), progress(400_000, t0.plusSeconds(60))))
    }

    @Test
    fun `a device without a name is named by its kind`() {
        val server = progress(400_000, t0.plusSeconds(60), devices = listOf(DevicePosition("d", null, "web", 400_000, false, t0.plusSeconds(60), false)))
        assertEquals(ResumeChoice.Ask(100_000, 400_000, "web"), ResumeChoice.decide(progress(100_000, t0), server))
    }
}
