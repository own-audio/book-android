package audio.own.bookplayer.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import audio.own.core.servers.MobileData
import audio.own.player.spoken.PlaybackSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The listener's playback choices (book-ios `PlaybackSettings.swift`). */
data class PlaybackPrefs(
    val skipBackSecs: Int = 15,
    val skipForwardSecs: Int = 30,
    /** The speed a book starts at the first time; each book then keeps its own. */
    val defaultSpeed: Float = PlaybackSpeed.DEFAULT,
    val smartRewind: Boolean = true,
    val mobileData: MobileData = MobileData.ORIGINAL,
    /** "Keep Ahead": minutes of the book kept on the phone ahead of the playhead while streaming. */
    val lookAheadMinutes: Int = 10,
    /** By composite book id. */
    val bookSpeeds: Map<String, Float> = emptyMap(),
) {
    fun speedFor(bookId: String): Float = bookSpeeds[bookId] ?: defaultSpeed

    companion object {
        val SKIP_CHOICES = listOf(5, 10, 15, 30, 45, 60, 90)
        val LOOK_AHEAD_CHOICES = listOf(5, 10, 15)

        /** Under Data Saver the window is the smallest choice, whatever was picked. */
        const val CONSTRAINED_LOOK_AHEAD_MINUTES = 5

        /** The Saver stream's bitrate; enough for speech. */
        const val SAVER_BITRATE_KBPS = 64
    }
}

interface PlaybackSettings {
    val prefs: StateFlow<PlaybackPrefs>

    suspend fun update(change: (PlaybackPrefs) -> PlaybackPrefs)

    suspend fun rememberSpeed(bookId: String, speed: Float) = update { it.copy(bookSpeeds = it.bookSpeeds + (bookId to speed)) }
}

private val Context.playbackStore: DataStore<Preferences> by preferencesDataStore(name = "playback")

class DataStorePlaybackSettings(context: Context, scope: CoroutineScope) : PlaybackSettings {
    private val store = context.applicationContext.playbackStore
    private val speedsSerializer = MapSerializer(String.serializer(), Float.serializer())

    override val prefs: StateFlow<PlaybackPrefs> = store.data.map { p ->
        PlaybackPrefs(
            skipBackSecs = p[SKIP_BACK] ?: 15,
            skipForwardSecs = p[SKIP_FORWARD] ?: 30,
            defaultSpeed = p[DEFAULT_SPEED] ?: PlaybackSpeed.DEFAULT,
            smartRewind = p[SMART_REWIND] ?: true,
            mobileData = p[MOBILE_DATA]?.let { v -> MobileData.entries.firstOrNull { it.name == v } } ?: MobileData.ORIGINAL,
            lookAheadMinutes = p[LOOK_AHEAD] ?: 10,
            bookSpeeds = p[BOOK_SPEEDS]?.let { runCatching { Json.decodeFromString(speedsSerializer, it) }.getOrNull() }.orEmpty(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, PlaybackPrefs())

    override suspend fun update(change: (PlaybackPrefs) -> PlaybackPrefs) {
        store.edit { p ->
            val current = prefs.value
            val next = change(current)
            p[SKIP_BACK] = next.skipBackSecs
            p[SKIP_FORWARD] = next.skipForwardSecs
            p[DEFAULT_SPEED] = next.defaultSpeed
            p[SMART_REWIND] = next.smartRewind
            p[MOBILE_DATA] = next.mobileData.name
            p[LOOK_AHEAD] = next.lookAheadMinutes
            p[BOOK_SPEEDS] = Json.encodeToString(speedsSerializer, next.bookSpeeds)
        }
    }

    private companion object {
        val SKIP_BACK = intPreferencesKey("skipBackward")
        val SKIP_FORWARD = intPreferencesKey("skipForward")
        val DEFAULT_SPEED = floatPreferencesKey("defaultRate")
        val SMART_REWIND = booleanPreferencesKey("smartRewind")
        val MOBILE_DATA = stringPreferencesKey("mobileData")
        val LOOK_AHEAD = intPreferencesKey("lookAheadMinutes")
        val BOOK_SPEEDS = stringPreferencesKey("bookRates")
    }
}

/** For tests and previews. */
class MemoryPlaybackSettings(initial: PlaybackPrefs = PlaybackPrefs()) : PlaybackSettings {
    private val flow = MutableStateFlow(initial)
    override val prefs: StateFlow<PlaybackPrefs> = flow

    override suspend fun update(change: (PlaybackPrefs) -> PlaybackPrefs) = flow.update(change)
}
