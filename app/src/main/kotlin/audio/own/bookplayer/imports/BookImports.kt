package audio.own.bookplayer.imports

import audio.own.book.library.Book
import audio.own.book.library.BookUploader
import audio.own.book.library.ImportMetadata
import audio.own.book.library.ImportedFile
import audio.own.book.library.NewBookDetails
import audio.own.book.library.SourceException
import audio.own.book.library.UploadKind
import audio.own.book.library.UploadTarget
import audio.own.book.library.Visibility
import audio.own.bookplayer.app.AppLog
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink

/** Where an import's work runs: a foreground WorkManager job per import in the app. */
interface ImportScheduler {
    fun enqueue(importId: String, replace: Boolean)
    fun cancel(importId: String)
}

/** The picked documents, read again for each upload, and their permissions. */
interface ImportFileAccess {
    /** Null when the document is gone or its permission was taken away. */
    fun open(uri: String): InputStream?

    /** Gives back the read permission of a document or folder this import held. */
    fun release(grant: String)
}

/** What the import screen hands over. */
data class ImportDraft(
    val serverId: String,
    val title: String?,
    val authors: List<String>,
    val narrators: List<String>,
    val visibility: Visibility,
    /** A picture chosen or taken from a file's tags; written into the import's folder. */
    val cover: ByteArray?,
    val files: List<PickedFile>,
)

/** Every import with the bytes sent so far of the files going up now. */
data class ImportsSnapshot(val records: Map<String, ImportRecord> = emptyMap(), val sent: Map<String, Long> = emptyMap()) {
    /** Not finished yet, newest first: the Library's Importing rows. */
    fun active(serverId: String): List<ImportRecord> = records.values.filter { it.serverId == serverId && !it.isDone }.sortedByDescending { it.startedAtMs }

    /** 0…1 of the bytes sent. */
    fun fraction(record: ImportRecord): Float {
        val total = record.totalBytes
        if (total <= 0) return 0f
        val have = record.allFiles.sumOf { if (it.uploaded) it.sizeBytes else (sent[it.id] ?: 0L) }
        return (have.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Books on their way from the phone to an own.audio server (iOS ImportManager): per file a link
 * (`POST /uploads` with the exact size), the bytes streamed from the picked document with exactly
 * the link's headers, and when every file is in storage `POST /books` with the import's id as its
 * `Idempotency-Key`. Every step is written to [directory] before the next, so a job killed
 * half-way starts again where it was, with the same links and the same key: a retry cannot make
 * two books.
 */
class BookImports(
    private val directory: File,
    /** The server's uploader once the saved servers are read; null when it is gone. */
    private val uploader: suspend (serverId: String) -> BookUploader?,
    private val access: ImportFileAccess,
    private val scheduler: ImportScheduler,
    private val log: AppLog,
    /** A book was made: the catalogue catches up. */
    private val onImported: (serverId: String, book: Book) -> Unit = { _, _ -> },
    /** The server said this account may not upload: the + goes. */
    private val onRefused: (serverId: String) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
) {
    private val index = ImportIndex(File(directory, "index.json"))
    private val lock = Mutex()
    private val _state = MutableStateFlow(ImportsSnapshot(index.load()))
    val state: StateFlow<ImportsSnapshot> = _state.asStateFlow()

    enum class Outcome { DONE, RETRY, FAILED }

    // region Changing

    suspend fun start(draft: ImportDraft): ImportRecord {
        val id = UUID.randomUUID().toString()
        val folder = File(directory, id).apply { mkdirs() }
        val cover = draft.cover?.let { bytes ->
            val type = ImportMetadata.imageType(bytes)
            val name = "cover." + when (type) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val file = File(folder, name).apply { writeBytes(bytes) }
            ImportFile(UUID.randomUUID().toString(), name, null, type, file.length(), file.toURI().toString())
        }
        val record = ImportRecord(
            id = id,
            serverId = draft.serverId,
            title = draft.title?.trim()?.takeIf { it.isNotEmpty() },
            authors = draft.authors,
            narrators = draft.narrators,
            visibility = draft.visibility.wire,
            cover = cover,
            files = draft.files.map { ImportFile(UUID.randomUUID().toString(), it.fileName, it.title, it.contentType, it.sizeBytes, it.uri) },
            startedAtMs = clock.millis(),
            grants = draft.files.map { it.grant }.distinct(),
        )
        change { it + (id to record) }
        log.info("imports", "import $id: ${record.files.size} files, ${record.totalBytes} bytes")
        scheduler.enqueue(id, replace = false)
        return record
    }

    suspend fun retry(importId: String) {
        change { records -> records[importId]?.let { records + (importId to it.copy(failure = null)) } ?: records }
        scheduler.enqueue(importId, replace = true)
    }

    /** Stops it and forgets it, with its folder and permissions. */
    suspend fun cancel(importId: String) {
        scheduler.cancel(importId)
        val record = lock.withLock {
            val r = _state.value.records[importId]
            val next = _state.value.records - importId
            index.save(next)
            _state.update { it.copy(records = next) }
            r
        }
        record?.let(::cleanUp)
    }

    /** At launch: what is still to do goes on (WorkManager keeps its jobs; this is for the ones it lost). */
    fun resume() {
        _state.value.records.values.filter { !it.isDone && it.failure == null }.forEach { scheduler.enqueue(it.id, replace = false) }
    }

    // endregion

    // region The work

    /**
     * Takes the import as far as it goes. [attempt] counts the runs so far: a passing failure is
     * tried again a few times before the row asks the listener.
     */
    suspend fun run(importId: String, attempt: Int = 0): Outcome {
        var record = _state.value.records[importId] ?: return Outcome.DONE
        if (record.isDone) return Outcome.DONE
        val uploader = uploader(record.serverId) ?: return fail(importId, ImportFailure(ImportFailure.SERVER_GONE))
        try {
            for (pending in record.allFiles.filter { !it.uploaded }) {
                var file = pending
                if (!file.hasLink(clock.millis())) {
                    val target = uploader.uploadTarget(
                        if (file.id == record.cover?.id) UploadKind.COVER else UploadKind.AUDIO, file.fileName, file.contentType, file.sizeBytes,
                    )
                    file = file.copy(
                        uploadId = target.uploadId, uploadUrl = target.url, uploadMethod = target.method,
                        uploadHeaders = target.headers, uploadExpiresAtMs = target.expiresAt.toEpochMilli(),
                    )
                    record = update(importId) { it.replacing(file) } ?: return Outcome.DONE
                }
                // Asked before the request: a failure inside it would read as a lost connection.
                readable(file)
                val body = DocumentBody(file, { access.open(file.uri) }) { sent -> _state.update { it.copy(sent = it.sent + (file.id to sent)) } }
                try {
                    uploader.upload(target(file), body)
                } catch (e: SourceException) {
                    if (transient(e)) throw e
                    // Storage said no to this link (expired, or the file changed): a retry asks for a new one.
                    update(importId) { it.replacing(file.withoutLink()) }
                    return fail(importId, ImportFailure(ImportFailure.STORAGE_REFUSED, status(e)))
                }
                record = update(importId) { it.replacing(file.copy(uploaded = true)) } ?: return Outcome.DONE
                _state.update { it.copy(sent = it.sent - file.id) }
            }
            val book = uploader.createBook(
                NewBookDetails(record.title, record.authors, record.narrators, record.description, Visibility.of(record.visibility) ?: Visibility.PRIVATE),
                coverUploadId = record.cover?.uploadId,
                files = record.files.map { ImportedFile(it.uploadId ?: throw SourceException.Unreadable, it.fileName, it.title) },
                importId = record.id,
            )
            update(importId) { it.copy(bookId = book.id, failure = null) }
            log.info("imports", "import $importId made book ${book.id}")
            finish(importId)
            onImported(record.serverId, book)
            return Outcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnreadableDocument) {
            return fail(importId, ImportFailure(ImportFailure.FILE_UNREADABLE))
        } catch (e: Exception) {
            if (transient(e) && attempt < MAX_ATTEMPTS) {
                log.info("imports", "import $importId waits: ${e.message}")
                return Outcome.RETRY
            }
            return fail(importId, failure(e))
        }
    }

    // endregion

    private fun readable(file: ImportFile) {
        val stream = try {
            access.open(file.uri)
        } catch (_: SecurityException) {
            null
        } ?: throw UnreadableDocument()
        stream.close()
    }

    private fun target(file: ImportFile) = UploadTarget(
        file.uploadId!!, file.uploadUrl!!, file.uploadMethod ?: "PUT", file.uploadHeaders.orEmpty(), Instant.ofEpochMilli(file.uploadExpiresAtMs ?: 0),
    )

    private suspend fun fail(importId: String, failure: ImportFailure): Outcome {
        log.error("imports", "import $importId stopped: ${failure.code} ${failure.status ?: ""}")
        if (failure.code == ImportFailure.NOT_ALLOWED) _state.value.records[importId]?.let { onRefused(it.serverId) }
        update(importId) { it.copy(failure = failure) }
        _state.update { s -> s.copy(sent = s.sent - (s.records[importId]?.allFiles?.map { it.id }.orEmpty().toSet())) }
        return Outcome.FAILED
    }

    /** A made book's import is forgotten: its row goes, the book comes through the change feed. */
    private suspend fun finish(importId: String) {
        val record = lock.withLock {
            val r = _state.value.records[importId]
            val next = _state.value.records - importId
            index.save(next)
            _state.update { it.copy(records = next, sent = it.sent - r?.allFiles?.map { f -> f.id }.orEmpty().toSet()) }
            r
        }
        record?.let(::cleanUp)
    }

    private fun cleanUp(record: ImportRecord) {
        File(directory, record.id).deleteRecursively()
        record.grants.forEach { grant ->
            // Another import may hold the same folder.
            if (_state.value.records.values.none { grant in it.grants }) runCatching { access.release(grant) }
        }
    }

    private suspend fun update(importId: String, change: (ImportRecord) -> ImportRecord): ImportRecord? = lock.withLock {
        val record = _state.value.records[importId] ?: return@withLock null
        val changed = change(record)
        val next = _state.value.records + (importId to changed)
        index.save(next)
        _state.update { it.copy(records = next) }
        changed
    }

    private suspend fun change(block: (Map<String, ImportRecord>) -> Map<String, ImportRecord>) = lock.withLock {
        val next = block(_state.value.records)
        index.save(next)
        _state.update { it.copy(records = next) }
    }

    private fun transient(e: Throwable): Boolean = when (e) {
        is IOException -> true
        SourceException.Network, SourceException.Offline -> true
        is SourceException.Http -> e.status >= 500 || e.status == 429
        else -> false
    }

    private fun status(e: SourceException): Int? = when (e) {
        is SourceException.Http -> e.status
        SourceException.NotAllowed -> 403
        SourceException.NotFound -> 404
        else -> null
    }

    private fun failure(e: Throwable): ImportFailure = when (e) {
        is SourceException.Http -> if (e.status == 402) ImportFailure(ImportFailure.NO_ROOM) else if (transient(e)) ImportFailure(ImportFailure.NETWORK) else ImportFailure(ImportFailure.HTTP, e.status)
        SourceException.NotAllowed -> ImportFailure(ImportFailure.NOT_ALLOWED)
        SourceException.SignInRejected -> ImportFailure(ImportFailure.SIGN_IN)
        is SourceException.Server -> ImportFailure(ImportFailure.SERVER, detail = e.text)
        else -> if (transient(e)) ImportFailure(ImportFailure.NETWORK) else ImportFailure(ImportFailure.HTTP, null, e.message)
    }

    companion object {
        /** Runs that end in a passing failure before the row shows Try Again. */
        const val MAX_ATTEMPTS = 5
    }
}

/** A document that can't be opened any more. */
internal class UnreadableDocument : IOException("document can't be read")

/**
 * A picked document's bytes, read anew each time the request is written: exactly [ImportFile.sizeBytes]
 * bytes, with the type the link was signed with so OkHttp sends that header as it is.
 */
internal class DocumentBody(
    private val file: ImportFile,
    private val open: () -> InputStream?,
    private val onSent: (Long) -> Unit,
) : RequestBody() {
    private val type: MediaType? = file.uploadHeaders?.entries?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.toMediaTypeOrNull()

    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = file.sizeBytes

    override fun writeTo(sink: BufferedSink) {
        val input = try {
            open()
        } catch (_: SecurityException) {
            null
        } ?: throw UnreadableDocument()
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            var sent = 0L
            var reported = 0L
            while (sent < file.sizeBytes) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), file.sizeBytes - sent).toInt())
                if (read < 0) throw UnreadableDocument()
                sink.write(buffer, 0, read)
                sent += read
                if (sent - reported >= REPORT_EVERY || sent == file.sizeBytes) {
                    reported = sent
                    onSent(sent)
                }
            }
        }
    }

    private companion object {
        const val REPORT_EVERY = 256 * 1024L
    }
}
