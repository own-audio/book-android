package audio.own.bookplayer.playback

import audio.own.book.library.Book
import audio.own.player.core.SleepTimer
import audio.own.player.spoken.SleepEnd
import kotlinx.coroutines.flow.StateFlow

/** What the screens can ask of playback; [PlaybackModel] in the app, a fake in tests. */
interface PlaybackControls {
    val state: StateFlow<PlaybackUi>

    /**
     * Plays [book] of [serverId]: from [atMs] when given (a chapter or bookmark tap), else where
     * the listener left off. Playing the loaded book again only resumes it.
     */
    fun play(serverId: String, book: Book, atMs: Long? = null)

    /** Plays the restored last book, or toggles the loaded one. */
    fun toggle()

    fun pause()

    fun seek(positionMs: Long)

    fun skipBack()

    fun skipForward()

    fun nextChapter()

    fun previousChapter()

    fun setSpeed(speed: Float)

    fun setSleep(preset: SleepTimer.Preset)

    fun setSleepEnd(end: SleepEnd)

    fun extendSleep()

    fun cancelSleep()

    /** Stops and forgets the last book. */
    fun stop()
}
