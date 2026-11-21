package audio.own.bookplayer.playback

import audio.own.player.spoken.SpokenChapter

/** What the sleep timer is set to, for the player's sheet and the cover badge. */
sealed interface SleepUi {
    data object Off : SleepUi

    data class Countdown(val remainingSecs: Double) : SleepUi

    data object EndOfChapter : SleepUi

    data object EndOfBook : SleepUi

    val isOn: Boolean get() = this != Off
}

/**
 * Everything the mini player, the player and the book page show about playback. Times are book
 * time in milliseconds. A fresh launch shows the last book [restored]: paused, not loaded; a tap
 * on play loads it (an Audiobookshelf play session must not open at launch).
 */
data class PlaybackUi(
    /** Composite id (`server|book`); null when nothing is loaded or remembered. */
    val bookId: String? = null,
    val serverId: String? = null,
    val title: String = "",
    val author: String = "",
    /** Something [audio.own.core.designsystem.components.CoverArt] loads. */
    val cover: Any? = null,
    val isPlaying: Boolean = false,
    /** Starting, or playing while the player waits for bytes: a spinner on the play button. */
    val isLoading: Boolean = false,
    /** "No signal — waiting": the network is gone and the player is held, not paused. */
    val waitingForSignal: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val chapters: List<SpokenChapter> = emptyList(),
    /** The chapters came from the server or the files; false: one per file. */
    val hasRealChapters: Boolean = false,
    val speed: Float = 1f,
    val skipBackSecs: Int = 15,
    val skipForwardSecs: Int = 30,
    val sleep: SleepUi = SleepUi.Off,
    /** "N min saved": how much of the book ahead of the playhead is on the phone. */
    val savedAheadMs: Long? = null,
    /** An error the player stopped on, in the listener's words. */
    val error: String? = null,
    val restored: Boolean = false,
) {
    val hasBook: Boolean get() = bookId != null

    val chapterIndex: Int?
        get() = if (chapters.isEmpty()) null else chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    val chapter: SpokenChapter? get() = chapterIndex?.let { chapters[it] }

    /** Time left in the book at the current speed, as the listener will hear it. */
    val timeLeftAtSpeedMs: Long get() = ((durationMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(0.1f)).toLong()

    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
