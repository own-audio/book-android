package audio.own.bookplayer.servers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import audio.own.bookplayer.app.AppLog
import audio.own.core.servers.ServerRecord
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Audiobookshelf's own icon, loaded from the listener's server as a browser loads a site's icon:
 * its artwork is GPL-licensed and the app carries none of it. Kept in `server-icons/<id>.png`,
 * asked of the server once.
 */
class ServerIcons(
    private val dir: File,
    http: OkHttpClient,
    private val userAgent: String,
    private val isOffline: () -> Boolean,
    private val log: AppLog,
) {
    private val quick = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    private val asking = ConcurrentHashMap.newKeySet<String>()

    // A server without an icon is asked once per run, not on every change of the server list.
    private val askedThisRun = ConcurrentHashMap.newKeySet<String>()
    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons.asStateFlow()

    /** [host] is the address the server answers at now. */
    suspend fun load(record: ServerRecord, host: String) = withContext(Dispatchers.IO) {
        if (record.kind != ServerKinds.AUDIOBOOKSHELF || record.id in _icons.value || !asking.add(record.id)) return@withContext
        try {
            find(record, host)
        } finally {
            asking.remove(record.id)
        }
    }

    private fun find(record: ServerRecord, host: String) {
        val file = File(dir, "${record.id}.png")
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { found ->
            _icons.update { it + (record.id to found.asImageBitmap()) }
            return
        }
        if (isOffline() || !askedThisRun.add(record.id)) return
        for (name in NAMES) {
            val (bytes, bitmap) = fetch("${host.trimEnd('/')}/$name") ?: continue
            _icons.update { it + (record.id to bitmap.asImageBitmap()) }
            // The bytes as served (PNG or ICO); BitmapFactory reads either back.
            runCatching {
                dir.mkdirs()
                val temp = File(dir, "${record.id}.tmp")
                temp.writeBytes(bytes)
                check(temp.renameTo(file))
            }.onFailure { log.error("servers", "the server icon could not be saved") }
            break
        }
    }

    fun remove(id: String) {
        _icons.update { it - id }
        File(dir, "$id.png").delete()
    }

    private fun fetch(url: String): Pair<ByteArray, Bitmap>? = try {
        quick.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful || !type.startsWith("image/")) return null
            val bytes = response.body.bytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width >= 16 }?.let { bytes to it }
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        val NAMES = listOf("icon192.png", "icon64.png", "favicon.ico")
    }
}
