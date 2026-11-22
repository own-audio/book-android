package audio.own.book.library

import java.time.Instant
import java.util.Locale
import okhttp3.RequestBody

// Books from the phone (iOS BookLibrary's BookUploader and SpokenEngine's ImportMetadata): a link
// per file, the bytes put there by the app, then one call that makes the book.

/** Where one file's bytes go; [headers] are part of the signature and are sent exactly. */
data class UploadTarget(
    val uploadId: String,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val expiresAt: Instant,
)

/** A file in storage, named in the book that is made from it, in playing order. */
data class ImportedFile(val uploadId: String, val fileName: String, val title: String?)

enum class UploadKind { AUDIO, COVER }

/** What a new book is told about itself; what is left out the server reads from the files. */
data class NewBookDetails(
    val title: String?,
    val authors: List<String>,
    val narrators: List<String>,
    val description: String?,
    val visibility: Visibility,
)

/** A source that takes books from the phone. */
interface BookUploader : BookSource {
    /** [sizeBytes] must be exact: the server refuses anything else. */
    suspend fun uploadTarget(kind: UploadKind, fileName: String, contentType: String, sizeBytes: Long): UploadTarget

    /** Puts [body] where [target] says, with exactly its headers and no token. */
    suspend fun upload(target: UploadTarget, body: RequestBody)

    /** [importId] makes a retry answer the same book. */
    suspend fun createBook(details: NewBookDetails, coverUploadId: String?, files: List<ImportedFile>, importId: String): Book
}

/** What the import form needs from a file's name and tags. */
object ImportMetadata {
    val audioExtensions: Set<String> = setOf("mp3", "m4a", "m4b", "mp4", "aac", "flac", "wav", "aiff", "aif", "ogg", "oga", "opus")

    fun isAudio(fileName: String, mimeType: String? = null): Boolean =
        extension(fileName) in audioExtensions || (extension(fileName).isEmpty() && mimeType?.startsWith("audio/") == true)

    /** The MIME type from the file's extension, which is what storage and the server go by. */
    fun contentType(fileName: String, fallback: String? = null): String = when (extension(fileName)) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aiff", "aif" -> "audio/aiff"
        "ogg", "oga", "opus" -> "audio/ogg"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> fallback?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    }

    /** A picture's type from its first bytes: covers come out of tags and pickers without a name. */
    fun imageType(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    /** "Jane Austen; Anne Bell" as two names. */
    fun names(text: String): List<String> = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** Sets the files' order on the server and names them: `001 - Chapter one.mp3`. */
    fun relativePath(index: Int, fileName: String): String = String.format(Locale.ROOT, "%03d - %s", index + 1, fileName)

    /** A folder's audio files in the order a person reads their names: 2 before 10. */
    fun <T> inNameOrder(items: List<T>, locale: Locale = Locale.getDefault(), name: (T) -> String): List<T> {
        val order = NaturalOrder(locale)
        return items.sortedWith { a, b -> order.compare(name(a), name(b)) }
    }

    private fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
