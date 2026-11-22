package audio.own.bookplayer.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import audio.own.book.library.Book
import audio.own.bookplayer.R
import audio.own.bookplayer.browse.Samples
import audio.own.bookplayer.downloads.BookDownloadsSnapshot
import audio.own.bookplayer.downloads.DownloadButton
import audio.own.bookplayer.downloads.DownloadState
import audio.own.bookplayer.downloads.DownloadedBook
import audio.own.bookplayer.downloads.DownloadsContent
import audio.own.bookplayer.downloads.LocalBookDownloads
import audio.own.bookplayer.downloads.SetDownloadButton
import audio.own.core.designsystem.components.OfflineBanner
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.core.servers.ServerAuth
import audio.own.core.servers.ServerRecord
import audio.own.core.servers.StoredTokens
import audio.own.bookplayer.servers.ServerKinds
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Downloads tab, the download buttons in each state and the offline banners. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi")
class DownloadsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean, snapshot: BookDownloadsSnapshot = this.snapshot, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                CompositionLocalProvider(LocalBookDownloads provides snapshot) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val home = ServerRecord(id = "s1", kind = ServerKinds.OWN_AUDIO, displayName = "Home", host = "https://books.example.com", auth = ServerAuth.OwnAudio("alex@example.com", StoredTokens("a", "r", 0)))
    private val shelf = ServerRecord(id = "s3", kind = ServerKinds.AUDIOBOOKSHELF, displayName = "Shelf", host = "http://192.168.1.20:13378", auth = ServerAuth.Audiobookshelf("kim", StoredTokens("a", "r", 0)))

    private fun entry(server: String, book: Book, state: DownloadState, bytes: Long, at: Long) =
        DownloadedBook("$server|${book.id}", server, book, bytes, at, if (state == DownloadState.Downloaded) at else null, null, state)

    private val snapshot = BookDownloadsSnapshot(
        listOf(
            entry("s1", Samples.pride, DownloadState.Downloaded, 482_000_000, 30),
            entry("s1", Samples.emma, DownloadState.Downloading(0.42f), 120_000_000, 40),
            entry("s3", Samples.hound, DownloadState.Failed("network"), 0, 20),
            entry("gone", Samples.pride.copy(id = "b9", title = "Persuasion"), DownloadState.Downloaded, 300_000_000, 10),
        ).associateBy { it.compositeId },
        loaded = true,
    )

    @Composable
    private fun Downloads() = DownloadsContent(snapshot, listOf(home, shelf), onOpen = {}, onRemove = {}, onRetryFailed = {}, onRemoveAll = {})

    /** The book page's button and the set button, in every state, with the banners under them. */
    @Composable
    private fun Controls() {
        val states = BookDownloadsSnapshot(
            mapOf(
                "s1|b1" to entry("s1", Samples.pride, DownloadState.Downloaded, 1, 1),
                "s1|b2" to entry("s1", Samples.emma, DownloadState.Downloading(0.6f), 1, 1),
                "s1|b3" to entry("s1", Samples.hound, DownloadState.Failed(null), 1, 1),
            ),
            loaded = true,
        )
        CompositionLocalProvider(LocalBookDownloads provides states) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DownloadButton("s1", Samples.pride.copy(id = "new"))
                    DownloadButton("s1", Samples.emma)
                    DownloadButton("s1", Samples.pride)
                    DownloadButton("s1", Samples.hound)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SetDownloadButton("s1", listOf(Samples.pride.copy(id = "x"), Samples.emma.copy(id = "y")))
                    SetDownloadButton("s1", listOf(Samples.pride, Samples.emma, Samples.hound))
                    SetDownloadButton("s1", listOf(Samples.pride))
                }
                for (text in listOf(R.string.banner_offline_mode, R.string.banner_no_network, R.string.banner_offline_mode_whole, R.string.banner_no_network_whole)) {
                    OfflineBanner(
                        stringResource(text),
                        actionLabel = if (text == R.string.banner_offline_mode || text == R.string.banner_offline_mode_whole) stringResource(audio.own.core.strings.R.string.turn_off) else null,
                        onAction = {},
                    )
                }
                Text(" ")
            }
        }
    }

    @Test fun downloadsLight() = shoot("downloads_light", false) { Downloads() }
    @Test fun downloadsDark() = shoot("downloads_dark", true) { Downloads() }
    @Test @Config(qualifiers = "+cs") fun downloadsCzech() = shoot("downloads_cs", false) { Downloads() }
    @Test @Config(fontScale = 2f) fun downloadsLargestText() = shoot("downloads_font200", false) { Downloads() }
    @Test fun downloadsEmpty() = shoot("downloads_empty", false, BookDownloadsSnapshot(loaded = true)) {
        DownloadsContent(BookDownloadsSnapshot(loaded = true), emptyList(), onOpen = {}, onRemove = {}, onRetryFailed = {}, onRemoveAll = {})
    }

    @Test fun downloadControlsLight() = shoot("download_controls_light", false) { Controls() }
    @Test fun downloadControlsDark() = shoot("download_controls_dark", true) { Controls() }
    @Test @Config(qualifiers = "+cs") fun downloadControlsCzech() = shoot("download_controls_cs", false) { Controls() }
}
