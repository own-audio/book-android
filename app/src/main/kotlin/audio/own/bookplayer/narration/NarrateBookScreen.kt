package audio.own.bookplayer.narration

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.net.toUri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.book.library.BookNarrator
import audio.own.book.library.NarrationOptions
import audio.own.book.library.SourceException
import audio.own.bookplayer.R
import audio.own.bookplayer.app.UiText
import audio.own.bookplayer.app.text
import audio.own.bookplayer.browse.sourceMessage
import audio.own.bookplayer.onboarding.FormTextField
import audio.own.bookplayer.ui.ButtonRow
import audio.own.bookplayer.ui.ErrorLine
import audio.own.bookplayer.ui.Page
import audio.own.bookplayer.ui.RowDivider
import audio.own.bookplayer.ui.Section
import audio.own.core.designsystem.components.ValueRow
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.kit.ownaudio.NarrationOutput
import audio.own.kit.ownaudio.NarrationQuote
import audio.own.kit.ownaudio.NarrationVoice
import java.io.File
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The family's credit beside the price. */
data class NarrationCredit(val balance: BigDecimal, val currency: String, val isDepleted: Boolean)

/** An ebook copied into the app's cache: the picker's access ends with the screen. */
data class PickedEbook(val file: File, val displayName: String)

data class NarrateState(
    val options: NarrationOptions? = null,
    val ebook: PickedEbook? = null,
    val title: String = "",
    val sourceLanguage: String = "en",
    val targetLanguage: String = "en",
    val voiceId: String? = null,
    val output: NarrationOutput = NarrationOutput.MULTI_FILE,
    val charCount: Int? = null,
    val quote: NarrationQuote? = null,
    val credit: NarrationCredit? = null,
    val working: Boolean = false,
    val error: UiText? = null,
    val done: Boolean = false,
) {
    val voices: List<NarrationVoice> get() = options?.voices(targetLanguage).orEmpty()
    val canPrice: Boolean get() = ebook != null && voiceId != null && !working
    val canStart: Boolean get() = quote != null && canPrice && title.isNotBlank() && credit?.isDepleted != true
}

/**
 * The Narrate an Ebook form (iOS NarrateBookView): the ebook, the languages and a voice, the
 * server's own count and price against the family's credit, then the job. The count is asked once
 * per file; the quote again for every voice.
 */
class NarrateBookViewModel(
    private val serverId: String,
    private val narrator: BookNarrator?,
    private val credit: suspend () -> NarrationCredit?,
    /** Copies the picked document into the cache. */
    private val copyIn: suspend (uri: String) -> PickedEbook?,
    private val narrations: BookNarrations,
) : ViewModel() {
    private val _state = MutableStateFlow(NarrateState())
    val state: StateFlow<NarrateState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val narrator = narrator ?: return
        viewModelScope.launch {
            try {
                val options = io { narrator.narrationOptions() }
                _state.update { s ->
                    val target = s.targetLanguage.takeIf { t -> options.targetLanguages.any { it.code == t } } ?: options.targetLanguages.firstOrNull()?.code ?: s.targetLanguage
                    val source = s.sourceLanguage.takeIf { l -> options.languages.any { it.code == l } } ?: options.languages.firstOrNull()?.code ?: s.sourceLanguage
                    s.copy(options = options, targetLanguage = target, sourceLanguage = source, voiceId = s.voiceId ?: options.voices(target).firstOrNull()?.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.sourceMessage()) }
            }
            val credit = runCatching { io { credit() } }.getOrNull()
            _state.update { it.copy(credit = credit) }
        }
    }

    fun take(uri: String) {
        viewModelScope.launch {
            val picked = try {
                copyIn(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (picked == null) {
                _state.update { it.copy(error = UiText(audio.own.core.strings.R.string.error_generic)) }
                return@launch
            }
            _state.value.ebook?.file?.delete()
            _state.update {
                it.copy(
                    ebook = picked, charCount = null, quote = null, error = null,
                    title = it.title.ifBlank { picked.displayName.substringBeforeLast('.') },
                )
            }
        }
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value) }
    fun setSourceLanguage(code: String) = _state.update { it.copy(sourceLanguage = code, quote = null) }

    fun setTargetLanguage(code: String) = _state.update { s ->
        val voices = s.options?.voices(code).orEmpty()
        s.copy(targetLanguage = code, voiceId = s.voiceId?.takeIf { id -> voices.any { it.id == id } } ?: voices.firstOrNull()?.id, quote = null)
    }

    fun setVoice(id: String) = _state.update { it.copy(voiceId = id, quote = null) }
    fun setOutput(output: NarrationOutput) = _state.update { it.copy(output = output) }

    fun price() {
        val s = _state.value
        val narrator = narrator ?: return
        val ebook = s.ebook ?: return
        val voiceId = s.voiceId ?: return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                val count = s.charCount ?: io { narrator.narrationCharacterCount(ebook.file) }.also { c -> _state.update { it.copy(charCount = c) } }
                val quote = io { narrator.narrationQuote(count, s.sourceLanguage, s.targetLanguage, voiceId) }
                _state.update { it.copy(quote = quote) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = message(e)) }
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun start() {
        val s = _state.value
        val narrator = narrator ?: return
        val ebook = s.ebook ?: return
        val voiceId = s.voiceId ?: return
        if (!s.canStart) return
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                // The same request always gets the same key: a retry after a lost answer finds the job
                // already started instead of paying for a second one.
                val key = "narr-" + java.security.MessageDigest.getInstance("SHA-256")
                    .digest(listOf(ebook.file.absolutePath, ebook.file.length(), s.title.trim(), s.sourceLanguage, s.targetLanguage, voiceId, s.output.wire).joinToString("|").encodeToByteArray())
                    .take(16).joinToString("") { "%02x".format(it) }
                val job = io { narrator.startNarration(ebook.file, s.title.trim(), s.sourceLanguage, s.targetLanguage, voiceId, s.output, key) }
                io { narrations.add(job, serverId) }
                ebook.file.delete()
                _state.update { it.copy(done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = message(e)) }
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    override fun onCleared() {
        if (!_state.value.done) _state.value.ebook?.file?.delete()
    }

    /** The kit reads answers on the caller's thread: never the main one. */
    private suspend fun <T> io(work: suspend () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { work() }

    private fun message(e: Throwable): UiText =
        if (e is SourceException.Http && e.status == 402) UiText(R.string.narration_no_credit) else e.sourceMessage()
}

/** "$1.20" in the listener's locale and the server's currency. */
fun formatPrice(cents: Int, currency: String, locale: Locale = Locale.getDefault()): String = formatMoney(BigDecimal(cents).movePointLeft(2), currency, locale)

fun formatMoney(amount: BigDecimal, currency: String, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getCurrencyInstance(locale)
    runCatching { format.currency = Currency.getInstance(currency) }
    return format.format(amount)
}

class NarrateActions(
    val onCancel: () -> Unit,
    val onPick: () -> Unit,
    val onTitle: (String) -> Unit,
    val onSourceLanguage: (String) -> Unit,
    val onTargetLanguage: (String) -> Unit,
    val onVoice: (String) -> Unit,
    val onOutput: (NarrationOutput) -> Unit,
    val onPrice: () -> Unit,
    val onStart: () -> Unit,
)

/** The ebook picker: EPUB, plain text and MOBI. */
@Composable
fun rememberEbookPicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onPicked(it.toString()) } }
    return remember(launcher) { { launcher.launch(arrayOf("application/epub+zip", "text/plain", "application/x-mobipocket-ebook", "application/octet-stream")) } }
}

@Composable
fun NarrateBookContent(state: NarrateState, actions: NarrateActions) {
    val palette = LocalPalette.current
    Page(title = stringResource(R.string.narrate_an_ebook), onBack = actions.onCancel, closeIsCancel = true) {
        Section(header = stringResource(R.string.book), footer = stringResource(R.string.ebook_footer)) {
            ButtonRow(
                state.ebook?.displayName ?: stringResource(R.string.choose_an_ebook),
                onClick = actions.onPick,
                icon = Icons.Filled.NoteAdd,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("narrate.pick"),
            )
            RowDivider()
            FormTextField(state.title, actions.onTitle, stringResource(R.string.title), null, KeyboardType.Text, Modifier.testTag("narrate.title"))
        }
        Section(header = stringResource(R.string.voice)) {
            val options = state.options
            if (options == null) {
                Row(Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.loading_voices), modifier = Modifier.weight(1f))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else {
                PickerRow(stringResource(R.string.written_in), options.languages.map { it.code to it.label }, state.sourceLanguage, actions.onSourceLanguage)
                RowDivider()
                PickerRow(stringResource(R.string.read_in), options.targetLanguages.map { it.code to it.label }, state.targetLanguage, actions.onTargetLanguage, Modifier.testTag("narrate.targetLanguage"))
                RowDivider()
                PickerRow(stringResource(R.string.voice), state.voices.map { it.id to voiceLine(it) }, state.voiceId, actions.onVoice, Modifier.testTag("narrate.voice"))
                RowDivider()
                PickerRow(
                    stringResource(R.string.files),
                    listOf(NarrationOutput.MULTI_FILE.wire to stringResource(R.string.one_per_chapter), NarrationOutput.SINGLE_M4B.wire to stringResource(R.string.one_for_the_whole_book)),
                    state.output.wire,
                    { wire -> actions.onOutput(NarrationOutput.entries.first { it.wire == wire }) },
                )
            }
        }
        Section(
            header = stringResource(R.string.price),
            footer = stringResource(if (state.sourceLanguage == state.targetLanguage) R.string.price_footer else R.string.price_footer_translated),
        ) {
            val quote = state.quote
            if (quote != null) {
                ValueRow(stringResource(R.string.characters), java.text.NumberFormat.getIntegerInstance().format(quote.charCount), null)
                if (quote.translationCostCents > 0) {
                    RowDivider()
                    ValueRow(stringResource(R.string.translation), formatPrice(quote.translationCostCents, quote.currency), null)
                }
                RowDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.price), modifier = Modifier.weight(1f))
                    Text(formatPrice(quote.quotedPriceCents, quote.currency), fontWeight = FontWeight.Bold, modifier = Modifier.testTag("narrate.price"))
                }
                state.credit?.let { credit ->
                    RowDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget).padding(horizontal = Tokens.Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.family_credit), modifier = Modifier.weight(1f))
                        Text(formatMoney(credit.balance, credit.currency), color = if (credit.isDepleted) palette.error else palette.foreground)
                    }
                }
            } else {
                ButtonRow(
                    stringResource(if (state.charCount == null) R.string.get_the_price else R.string.price_this_voice),
                    onClick = actions.onPrice,
                    enabled = state.canPrice,
                    working = state.working,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("narrate.price.get"),
                )
            }
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm)) }
        Section(header = null, footer = stringResource(R.string.narrate_footer)) {
            Box(Modifier.fillMaxWidth()) {
                ButtonRow(
                    state.quote?.let { stringResource(R.string.narrate_for, formatPrice(it.quotedPriceCents, it.currency)) } ?: stringResource(R.string.narrate),
                    onClick = actions.onStart,
                    enabled = state.canStart,
                    working = state.working && state.quote != null,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("narrate.start"),
                )
            }
        }
    }
}

@Composable
private fun voiceLine(voice: NarrationVoice): String =
    stringResource(R.string.voice_line, voice.displayName, stringResource(if (voice.gender == "female") R.string.female else R.string.male))

/** A row that shows its value and opens the choices in a menu, as iOS's menu pickers do. */
@Composable
private fun PickerRow(label: String, choices: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ValueRow(label, choices.firstOrNull { it.first == selected }?.second, onClick = if (choices.isEmpty()) null else ({ open = true }))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                choices.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text, fontWeight = if (value == selected) FontWeight.SemiBold else null) }, onClick = { onSelect(value); open = false })
                }
            }
        }
    }
}

/**
 * Copies a picked ebook into the app's cache, keeping its name (the server reads the type from
 * it): the picker's access ends with the screen, and the kit sends files from disk.
 */
suspend fun copyEbook(context: android.content.Context, uri: String): PickedEbook? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val parsed = uri.toUri()
    val resolver = context.contentResolver
    val name = resolver.query(parsed, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: parsed.lastPathSegment?.substringAfterLast('/') ?: "ebook"
    val safe = name.replace(Regex("[/\\\\:\"]"), "_")
    // Its own folder, so two ebooks of one name don't meet; the kit sends the file under its name.
    val copy = File(File(context.cacheDir, "narrate/${java.util.UUID.randomUUID()}").apply { mkdirs() }, safe)
    val input = resolver.openInputStream(parsed) ?: return@withContext null
    input.use { source -> copy.outputStream().use { source.copyTo(it) } }
    PickedEbook(copy, name)
}
