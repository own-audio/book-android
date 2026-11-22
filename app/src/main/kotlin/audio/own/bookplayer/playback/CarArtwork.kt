package audio.own.bookplayer.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import audio.own.bookplayer.OwnAudioApp
import audio.own.bookplayer.library.coverKey
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A book cover as a small JPEG, through the app's image loader and its cache (keyed by server and
 * book, so a signed link that changed still finds the picture already on the phone): for the
 * car's rows and the widget, which can't load from the web themselves.
 */
class CoverJpegs(private val context: Context) {
    suspend fun jpeg(serverId: String, bookId: String, url: String, px: Int): ByteArray? {
        val key = coverKey(serverId, bookId)
        val request = ImageRequest.Builder(context).data(url).memoryCacheKey(key).diskCacheKey(key).size(px).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 80
    }
}

/**
 * Pictures for Android Auto's lists. The car reads a row's artwork through a content URI rather
 * than from the web, so each cover the browse tree names is handed out as
 * `content://<app>.artwork/art?s=<server>&b=<book>&u=<address>` and served small from the app's
 * cache. Only covers the tree itself named are served: other apps can't make this one fetch
 * what they like.
 */
class CarArtwork(private val authority: String, private val folder: File) {
    private val named: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun uri(serverId: String, bookId: String, url: String?): Uri? {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return null
        named += url
        return Uri.Builder().scheme("content").authority(authority).appendPath("art")
            .appendQueryParameter("s", serverId).appendQueryParameter("b", bookId).appendQueryParameter("u", url).build()
    }

    fun isNamed(url: String): Boolean = url in named

    /** The cached file for a book's cover, made on first use. */
    fun file(serverId: String, bookId: String): File = File(folder, sha1("$serverId|$bookId") + ".jpg")

    private fun sha1(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val PX = 256
        const val LOAD_TIMEOUT_MS = 10_000L
    }
}

class CarArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val container = (context?.applicationContext as? OwnAudioApp)?.container ?: throw FileNotFoundException("not ready")
        val url = uri.getQueryParameter("u") ?: throw FileNotFoundException("no picture")
        val serverId = uri.getQueryParameter("s") ?: throw FileNotFoundException("no server")
        val bookId = uri.getQueryParameter("b") ?: throw FileNotFoundException("no book")
        val car = container.carArtwork
        if (!car.isNamed(url)) throw FileNotFoundException("not one of the app's pictures")
        val file = car.file(serverId, bookId)
        if (!file.exists()) {
            val bytes = runBlocking(Dispatchers.IO) { withTimeoutOrNull(CarArtwork.LOAD_TIMEOUT_MS) { container.coverJpegs.jpeg(serverId, bookId, url, CarArtwork.PX) } }
                ?: throw FileNotFoundException("picture unavailable")
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, file.name + ".part")
            partial.writeBytes(bytes)
            partial.renameTo(file)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
