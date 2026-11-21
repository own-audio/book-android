package audio.own.bookplayer.library

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import audio.own.book.library.Book
import audio.own.book.library.BookSource
import audio.own.core.designsystem.theme.AppBrand
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A book cover for Coil, cached by server and book rather than by URL: own.audio's image links
 * carry a signature that changes (docs/android-foundation.md §7.3). [onError] hears of a link
 * that didn't load.
 */
fun bookCoverRequest(context: Context, serverId: String, bookId: String, url: String?, onError: (() -> Unit)? = null): ImageRequest? {
    if (url == null) return null
    val key = coverKey(serverId, bookId)
    return ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .crossfade(AppBrand.COVER_CROSSFADE_MS)
        .apply { if (onError != null) listener(onError = { _, _ -> onError() }) }
        .build()
}

fun coverKey(serverId: String, bookId: String) = "$serverId|$bookId~cover"

/**
 * Covers whose link no longer loads (signed links run out; the server's address changed): the
 * book's current link is asked for once per run, and the cover loads from it.
 */
class CoverRefresher(private val source: (serverId: String) -> BookSource?, private val scope: CoroutineScope) {
    private val tried = ConcurrentHashMap.newKeySet<String>()
    private val _fresh = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Fresh links by [coverKey]. */
    val fresh: StateFlow<Map<String, String>> = _fresh.asStateFlow()

    fun failed(serverId: String, bookId: String) {
        val key = coverKey(serverId, bookId)
        if (!tried.add(key)) return
        scope.launch {
            val url = source(serverId)?.currentCoverUrl(bookId) ?: return@launch
            _fresh.update { it + (key to url) }
        }
    }
}

val LocalCoverRefresher = staticCompositionLocalOf<CoverRefresher?> { null }

@Composable
fun rememberBookCover(serverId: String, book: Book): ImageRequest? {
    val context = LocalContext.current
    val refresher = LocalCoverRefresher.current
    val fresh = refresher?.let { r ->
        val links by r.fresh.collectAsStateWithLifecycle()
        links[coverKey(serverId, book.id)]
    }
    val url = fresh ?: book.coverUrl
    return remember(serverId, book.id, url) {
        bookCoverRequest(context, serverId, book.id, url, onError = if (fresh == null && refresher != null) ({ refresher.failed(serverId, book.id) }) else null)
    }
}
