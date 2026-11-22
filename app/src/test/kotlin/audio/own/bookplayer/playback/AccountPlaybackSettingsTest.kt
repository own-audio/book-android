package audio.own.bookplayer.playback

import audio.own.kit.ownaudio.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountPlaybackSettingsTest {
    @Test
    fun `the account's values become the nearest choices the app offers`() {
        assertEquals(Triple(15, 30, 1.25f), AccountPlaybackSettings.choices(Settings.Audiobook(speed = 1.2, skipBackMs = 15_000, skipForwardMs = 30_000)))
        assertEquals(Triple(10, 90, 2f), AccountPlaybackSettings.choices(Settings.Audiobook(speed = 3.0, skipBackMs = 9_000, skipForwardMs = 120_000)))
        assertEquals(Triple(5, 5, 0.75f), AccountPlaybackSettings.choices(Settings.Audiobook(speed = 0.5, skipBackMs = 0, skipForwardMs = 1_000)))
    }

    @Test
    fun `what is sent is the audiobook part only, in milliseconds`() {
        val patch = AccountPlaybackSettings.patch(Triple(15, 45, 1.5f))
        assertEquals(1.5, patch.audiobook?.speed)
        assertEquals(15_000L, patch.audiobook?.skipBackMs)
        assertEquals(45_000L, patch.audiobook?.skipForwardMs)
        assertEquals(null, patch.playback)
    }
}
