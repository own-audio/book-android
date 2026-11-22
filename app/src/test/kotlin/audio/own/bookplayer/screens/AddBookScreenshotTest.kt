package audio.own.bookplayer.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import audio.own.book.library.NarrationOptions
import audio.own.book.library.Visibility
import audio.own.bookplayer.imports.AddingActions
import audio.own.bookplayer.imports.AddingRows
import audio.own.bookplayer.imports.ImportBookContent
import audio.own.bookplayer.imports.ImportFailure
import audio.own.bookplayer.imports.ImportFile
import audio.own.bookplayer.imports.ImportFormActions
import audio.own.bookplayer.imports.ImportFormState
import audio.own.bookplayer.imports.ImportRecord
import audio.own.bookplayer.imports.ImportsSnapshot
import audio.own.bookplayer.imports.PickedFile
import audio.own.bookplayer.narration.NarrateActions
import audio.own.bookplayer.narration.NarrateBookContent
import audio.own.bookplayer.narration.NarrateState
import audio.own.bookplayer.narration.NarrationCredit
import audio.own.bookplayer.narration.NarrationEntry
import audio.own.bookplayer.narration.PickedEbook
import audio.own.core.designsystem.theme.AccentChoice
import audio.own.core.designsystem.theme.OwnAudioTheme
import audio.own.kit.ownaudio.NarrationLanguage
import audio.own.kit.ownaudio.NarrationQuote
import audio.own.kit.ownaudio.NarrationVoice
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Import a Book, Narrate an Ebook, and their rows in the Library. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi")
class AddBookScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            OwnAudioTheme(accent = AccentChoice.APP, darkTheme = dark, reduceMotion = true) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val cover: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(140, 90, 60))
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun file(n: Int, title: String?, ms: Long, mb: Long) =
        PickedFile("content://d/$n", "%02d - Emma.mp3".format(n), title, "Jane Austen", "Emma", ms, mb shl 20, "audio/mpeg", "content://d/$n")

    private val filled get() = ImportFormState(
        files = listOf(file(1, "Volume I, Chapter 1", 1_312_000, 21), file(2, "Volume I, Chapter 2", 1_048_000, 17), file(3, null, 2_911_000, 46)),
        title = "Emma",
        author = "Jane Austen",
        narrator = "Kim Bell; Ann Lee",
        visibility = Visibility.FAMILY,
        cover = cover,
    )

    private val formActions = ImportFormActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {})

    @Composable
    private fun Rows() {
        val up = ImportRecord(
            "i1", "s1", title = "Emma",
            files = listOf(ImportFile("a", "01.mp3", null, "audio/mpeg", 300L shl 20, "x", uploaded = true), ImportFile("b", "02.mp3", null, "audio/mpeg", 200L shl 20, "y")),
            startedAtMs = 2,
        )
        val stuck = up.copy(id = "i2", title = "Persuasion", failure = ImportFailure(ImportFailure.NO_ROOM), startedAtMs = 1)
        val stages = listOf("queued", "extracting", "narrating", "assembling")
        Column {
            AddingRows(
                imports = listOf(up, stuck),
                snapshot = ImportsSnapshot(mapOf("i1" to up, "i2" to stuck), sent = mapOf("b" to (50L shl 20))),
                narrations = listOf(
                    NarrationEntry("n1", "s1", "Pride and Prejudice", 3, "narrating", stages, chaptersDone = 2, chaptersTotal = 12, quotedPriceCents = 412),
                    NarrationEntry("n2", "s1", "The Hound of the Baskervilles", 2, "complete", stages, bookId = "b1", quotedPriceCents = 238),
                    NarrationEntry("n3", "s1", "Middlemarch", 1, "failed", stages, error = null),
                ),
                actions = AddingActions({}, {}, {}),
            )
        }
    }

    private val options = NarrationOptions(
        listOf(NarrationLanguage("en", "English"), NarrationLanguage("cs", "Czech")),
        listOf(NarrationVoice("v1", "Ava", "en", "female"), NarrationVoice("v2", "Jan", "cs", "male")),
    )
    private val narrateActions = NarrateActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    private val priced = NarrateState(
        options = options,
        ebook = PickedEbook(File("emma.epub"), "Emma.epub"),
        title = "Emma",
        sourceLanguage = "en",
        targetLanguage = "cs",
        voiceId = "v2",
        charCount = 881_234,
        quote = NarrationQuote(881_234, translationCostCents = 120, ttsCostCents = 290, quotedPriceCents = 410, currency = "USD"),
        credit = NarrationCredit(BigDecimal("12.40"), "USD", false),
    )

    @Test fun importEmpty() = shoot("import_empty_light", false) { ImportBookContent(ImportFormState(), formActions) }
    @Test fun importFilledLight() = shoot("import_filled_light", false) { ImportBookContent(filled, formActions) }
    @Test fun importFilledDark() = shoot("import_filled_dark", true) { ImportBookContent(filled, formActions) }
    @Test @Config(qualifiers = "+cs") fun importFilledCzech() = shoot("import_filled_cs", false) { ImportBookContent(filled, formActions) }
    @Test @Config(fontScale = 2f) fun importFilledLargestText() = shoot("import_filled_font200", false) { ImportBookContent(filled, formActions) }
    @Test fun importMobileQuestion() = shoot("import_mobile_question_light", false) { ImportBookContent(filled.copy(askMobile = 312L shl 20), formActions) }

    @Test fun addingRowsLight() = shoot("adding_rows_light", false) { Rows() }
    @Test fun addingRowsDark() = shoot("adding_rows_dark", true) { Rows() }
    @Test @Config(qualifiers = "+cs") fun addingRowsCzech() = shoot("adding_rows_cs", false) { Rows() }
    @Test @Config(fontScale = 2f) fun addingRowsLargestText() = shoot("adding_rows_font200", false) { Rows() }

    @Test fun narrateLoading() = shoot("narrate_loading_light", false) { NarrateBookContent(NarrateState(), narrateActions) }
    @Test fun narratePricedLight() = shoot("narrate_priced_light", false) { NarrateBookContent(priced, narrateActions) }
    @Test fun narratePricedDark() = shoot("narrate_priced_dark", true) { NarrateBookContent(priced, narrateActions) }
    @Test @Config(qualifiers = "+cs") fun narratePricedCzech() = shoot("narrate_priced_cs", false) { NarrateBookContent(priced, narrateActions) }
    @Test @Config(fontScale = 2f) fun narratePricedLargestText() = shoot("narrate_priced_font200", false) { NarrateBookContent(priced, narrateActions) }
    @Test fun narrateUnpriced() = shoot("narrate_unpriced_light", false) {
        NarrateBookContent(priced.copy(quote = null, charCount = null, targetLanguage = "en", voiceId = "v1"), narrateActions)
    }
}
