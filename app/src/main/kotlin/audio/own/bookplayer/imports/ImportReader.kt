package audio.own.bookplayer.imports

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import audio.own.book.library.ImportMetadata
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An audio file the listener picked, with what its tags say. */
data class PickedFile(
    /** The document itself, read again when it goes up. */
    val uri: String,
    val fileName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val contentType: String,
    /** The document or the folder whose read permission covers this file. */
    val grant: String,
    /** The embedded picture, read only while the form has no cover yet. */
    val artwork: ByteArray? = null,
) {
    override fun equals(other: Any?) = other is PickedFile && other.uri == uri && other.title == title && other.sizeBytes == sizeBytes
    override fun hashCode() = uri.hashCode()
}

/** Reads what the pickers hand back: documents, a folder's audio, a picture. */
interface ImportReader {
    suspend fun documents(uris: List<String>, wantArtwork: Boolean): List<PickedFile>

    /** The audio files directly in the folder, in name order. */
    suspend fun folder(tree: String, wantArtwork: Boolean): List<PickedFile>

    suspend fun picture(uri: String): ByteArray?

    /** Permissions taken for files that were not imported after all. */
    fun release(grants: Collection<String>)
}

/**
 * The Storage Access Framework: a picked document's or folder's read permission is kept
 * (`takePersistableUriPermission`), so the upload can read it again after a restart; the tags come
 * from [MediaMetadataRetriever].
 */
class DocumentImportReader(context: Context) : ImportReader, ImportFileAccess {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver

    override suspend fun documents(uris: List<String>, wantArtwork: Boolean): List<PickedFile> = withContext(Dispatchers.IO) {
        var needArtwork = wantArtwork
        uris.mapNotNull { text ->
            val uri = text.toUri()
            keep(uri)
            val (name, size, mime) = describe(uri) ?: return@mapNotNull null
            if (!ImportMetadata.isAudio(name, mime)) {
                release(listOf(text))
                return@mapNotNull null
            }
            read(uri, name, size, mime, text, needArtwork).also { if (it.artwork != null) needArtwork = false }
        }
    }

    override suspend fun folder(tree: String, wantArtwork: Boolean): List<PickedFile> = withContext(Dispatchers.IO) {
        val treeUri = tree.toUri()
        keep(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val found = mutableListOf<Triple<Uri, String, Pair<Long, String?>>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR || !ImportMetadata.isAudio(name, mime)) continue
                found += Triple(DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)), name, (if (cursor.isNull(2)) -1L else cursor.getLong(2)) to mime)
            }
        }
        if (found.isEmpty()) release(listOf(tree))
        var needArtwork = wantArtwork
        ImportMetadata.inNameOrder(found) { it.second }.map { (uri, name, extra) ->
            read(uri, name, extra.first, extra.second, tree, needArtwork).also { if (it.artwork != null) needArtwork = false }
        }
    }

    override suspend fun picture(uri: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { resolver.openInputStream(uri.toUri())?.use { it.readBytes() } }.getOrNull()
    }

    override fun release(grants: Collection<String>) {
        grants.forEach(::release)
    }

    override fun release(grant: String) {
        runCatching { resolver.releasePersistableUriPermission(grant.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override fun open(uri: String): InputStream? = uri.toUri().let { parsed ->
        if (parsed.scheme == "file") java.io.File(parsed.path ?: return null).takeIf { it.isFile }?.inputStream() else resolver.openInputStream(parsed)
    }

    private fun keep(uri: Uri) {
        // Some providers give no persistable permission; the import then works while the app lives.
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun describe(uri: Uri): Triple<String, Long, String?>? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            Triple(c.getString(0) ?: uri.lastPathSegment.orEmpty(), if (c.isNull(1)) -1L else c.getLong(1), resolver.getType(uri))
        }
    }.getOrNull()

    private fun read(uri: Uri, name: String, listedSize: Long, mime: String?, grant: String, wantArtwork: Boolean): PickedFile {
        // The size goes into the upload link and must be exact: the provider's figure, else the bytes counted.
        val size = listedSize.takeIf { it >= 0 } ?: runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 }
            ?: runCatching { resolver.openInputStream(uri)?.use { s -> s.skip(Long.MAX_VALUE) } }.getOrNull() ?: 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(app, uri)
            PickedFile(
                uri = uri.toString(),
                fileName = name,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                sizeBytes = size,
                contentType = ImportMetadata.contentType(name, mime),
                grant = grant,
                artwork = if (wantArtwork) retriever.embeddedPicture else null,
            )
        } catch (_: Exception) {
            PickedFile(uri.toString(), name, null, null, null, 0, size, ImportMetadata.contentType(name, mime), grant)
        } finally {
            runCatching { retriever.release() }
        }
    }
}
