package audio.own.bookplayer.servers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import audio.own.bookplayer.R
import audio.own.core.designsystem.theme.LocalPalette

/** The kind's name, the same in every language. */
fun kindTitle(kind: String): String = when (kind) {
    ServerKinds.OWN_AUDIO -> "own.audio"
    ServerKinds.AUDIOBOOKSHELF -> "Audiobookshelf"
    else -> kind
}

/** The icons Audiobookshelf servers served, by server id ([ServerIcons]). */
val LocalServerIcons = compositionLocalOf<Map<String, ImageBitmap>> { emptyMap() }

/**
 * The server at a glance: own.audio's logo, which ships with the app; Audiobookshelf's own
 * icon once the server has given it ([LocalServerIcons]), a book symbol until then.
 */
@Composable
fun ServerKindIcon(kind: String, modifier: Modifier = Modifier, size: Dp = 28.dp, serverId: String? = null) {
    val shape = RoundedCornerShape(size * 0.22f)
    val served = serverId?.let { LocalServerIcons.current[it] }
    when {
        kind == ServerKinds.OWN_AUDIO -> Image(painterResource(R.drawable.ic_server_own_audio), contentDescription = kindTitle(kind), modifier = modifier.size(size).clip(shape))
        served != null -> Image(served, contentDescription = kindTitle(kind), contentScale = ContentScale.Fit, modifier = modifier.size(size).clip(shape))
        else -> Box(modifier.size(size).clip(shape).background(LocalPalette.current.backgroundAlt), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = kindTitle(kind), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.55f))
        }
    }
}
