package audio.own.bookplayer.library

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import audio.own.book.library.Book
import audio.own.core.designsystem.theme.AppBrand
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * A book cover for Coil, cached by server and book rather than by URL: own.audio's image links
 * carry a signature that changes (docs/android-foundation.md §7.3).
 */
fun bookCoverRequest(context: Context, serverId: String, bookId: String, url: String?): ImageRequest? {
    if (url == null) return null
    val key = coverKey(serverId, bookId)
    return ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .crossfade(AppBrand.COVER_CROSSFADE_MS)
        .build()
}

fun coverKey(serverId: String, bookId: String) = "$serverId|$bookId~cover"

@Composable
fun rememberBookCover(serverId: String, book: Book): ImageRequest? {
    val context = LocalContext.current
    return remember(serverId, book.id, book.coverUrl) { bookCoverRequest(context, serverId, book.id, book.coverUrl) }
}
