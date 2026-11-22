package audio.own.book.library

import audio.own.kit.ownaudio.NarrationJob
import audio.own.kit.ownaudio.NarrationLanguage
import audio.own.kit.ownaudio.NarrationOutput
import audio.own.kit.ownaudio.NarrationQuote
import audio.own.kit.ownaudio.NarrationVoice
import java.io.File
import kotlinx.coroutines.flow.Flow

// Narration: an ebook read by an AI voice into an audiobook, on hosted own.audio servers that
// advertise the `narration` module (iOS BookLibrary/BookNarration.swift).

/** The server's languages and voices, fetched together. */
data class NarrationOptions(val languages: List<NarrationLanguage>, val voices: List<NarrationVoice>) {
    /** Only a language with a voice can be read aloud; the source can be any of them. */
    val targetLanguages: List<NarrationLanguage>
        get() {
            val voiced = voices.map { it.language }.toSet()
            return languages.filter { it.code in voiced }
        }

    fun voices(language: String): List<NarrationVoice> = voices.filter { it.language == language }
}

interface BookNarrator : BookSource {
    suspend fun narrationOptions(): NarrationOptions

    /** The server's count of characters in the file; nothing is charged. */
    suspend fun narrationCharacterCount(file: File): Int
    suspend fun narrationQuote(charCount: Int, sourceLanguage: String, targetLanguage: String, voiceId: String): NarrationQuote
    suspend fun startNarration(file: File, title: String, sourceLanguage: String, targetLanguage: String, voiceId: String, output: NarrationOutput): NarrationJob
    suspend fun narration(id: String): NarrationJob

    /**
     * One element whenever the server says a narration moved on, heard on the same live stream as
     * [LiveLibraryUpdates.libraryChanges] while that is open.
     */
    val narrationEvents: Flow<Unit>
}
