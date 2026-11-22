package audio.own.bookplayer.imports

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One file of an import, with the storage link it was given, kept so a restart reuses it. */
@Serializable
data class ImportFile(
    val id: String,
    val fileName: String,
    val title: String? = null,
    val contentType: String,
    val sizeBytes: Long,
    /** The picked document (`content://`, its read permission kept) or a copy in the import's folder (`file://`). */
    val uri: String,
    val uploadId: String? = null,
    val uploadUrl: String? = null,
    val uploadMethod: String? = null,
    val uploadHeaders: Map<String, String>? = null,
    val uploadExpiresAtMs: Long? = null,
    val uploaded: Boolean = false,
) {
    /** A link that is still good for a while: a large file takes minutes to go up. */
    fun hasLink(nowMs: Long): Boolean = uploadUrl != null && uploadId != null && (uploadExpiresAtMs ?: 0) > nowMs + LINK_MARGIN_MS

    fun withoutLink() = copy(uploadId = null, uploadUrl = null, uploadMethod = null, uploadHeaders = null, uploadExpiresAtMs = null)

    companion object {
        const val LINK_MARGIN_MS = 5 * 60_000L
    }
}

/** Why an import stopped; the Library row says it in the app's words. */
@Serializable
data class ImportFailure(
    /** One of the constants below. */
    val code: String,
    val status: Int? = null,
    /** The server's own sentence, for [SERVER]. */
    val detail: String? = null,
) {
    companion object {
        const val NO_ROOM = "no_room"
        const val NOT_ALLOWED = "not_allowed"
        const val STORAGE_REFUSED = "storage_refused"
        const val FILE_UNREADABLE = "file_unreadable"
        const val SIGN_IN = "sign_in"
        const val NETWORK = "network"
        const val SERVER_GONE = "server_gone"
        const val SERVER = "server"
        const val HTTP = "http"
    }
}

/** A book on its way from the phone: everything needed to go on after a kill. */
@Serializable
data class ImportRecord(
    /** Also the `Idempotency-Key` of `POST /books`, so a retry makes no second book. */
    val id: String,
    val serverId: String,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val description: String? = null,
    /** `private` or `family`. */
    val visibility: String = "private",
    val cover: ImportFile? = null,
    val files: List<ImportFile>,
    val startedAtMs: Long,
    val bookId: String? = null,
    val failure: ImportFailure? = null,
    /** The documents and folders whose read permission this import holds, given back at the end. */
    val grants: List<String> = emptyList(),
) {
    val allFiles: List<ImportFile> get() = files + listOfNotNull(cover)
    val totalBytes: Long get() = allFiles.sumOf { it.sizeBytes }
    val isDone: Boolean get() = bookId != null

    fun replacing(file: ImportFile): ImportRecord =
        if (cover?.id == file.id) copy(cover = file) else copy(files = files.map { if (it.id == file.id) file else it })
}

/** Every import, in one small JSON file written whole after each change. */
internal class ImportIndex(private val file: File) {
    private val serializer = MapSerializer(String.serializer(), ImportRecord.serializer())

    fun load(): Map<String, ImportRecord> = try {
        if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun save(records: Map<String, ImportRecord>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(serializer, records))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
