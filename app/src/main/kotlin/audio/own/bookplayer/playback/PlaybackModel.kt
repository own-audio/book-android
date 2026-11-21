package audio.own.bookplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import audio.own.book.library.AudiobookshelfBookSource
import audio.own.book.library.Book
import audio.own.book.library.BookId
import audio.own.book.library.BookSource
import audio.own.book.library.ResumeChoice
import audio.own.bookplayer.R
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.library.BookSources
import audio.own.bookplayer.library.bookCoverRequest
import audio.own.core.servers.MobileData
import audio.own.core.servers.NetworkState
import audio.own.core.servers.OfflineSwitch
import audio.own.player.core.JsonFile
import audio.own.player.core.PlaybackUris
import audio.own.player.core.SleepTimer
import audio.own.player.spoken.ChapterOrigin
import audio.own.player.spoken.PlaybackSpeed
import audio.own.player.spoken.ReportReason
import audio.own.player.spoken.SleepEnd
import audio.own.player.spoken.SmartRewind
import audio.own.player.spoken.SpokenBook
import audio.own.player.spoken.SpokenPart
import audio.own.player.spoken.SpokenPlayer
import audio.own.player.spoken.SpokenPosition
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Playback for the screens: loads a book from its source into the spoken player (where the
 * listener left off, local first, then the server), keeps the media service connected through a
 * [MediaController] so it goes to the foreground when a book plays, writes every position to
 * disk before it is sent, and remembers the last book for the mini player after a relaunch.
 */
@OptIn(UnstableApi::class)
class PlaybackModel(
    private val context: Context,
    private val sources: BookSources,
    private val settings: PlaybackSettings,
    private val network: StateFlow<NetworkState>,
    private val offline: OfflineSwitch,
    private val log: AppLog,
    filesDir: File,
    private val engineFactory: () -> PlaybackEngine,
) : PlaybackControls {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Serializable
    data class LastBook(
        val serverId: String,
        val bookId: String,
        val title: String,
        val author: String,
        val coverUrl: String? = null,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float = 1f,
    )

    private val lastBookFile = JsonFile(File(filesDir, "last-book.json"), LastBook.serializer())
    private var lastBook: LastBook? = lastBookFile.read()

    val outbox = ProgressOutbox(
        file = File(filesDir, "pending-progress.json"),
        send = { serverId, report -> (sources.source(serverId) ?: throw ProgressOutbox.NoSource()).report(report) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        log = log,
    )

    private val _state = MutableStateFlow(restoredState())
    override val state: StateFlow<PlaybackUi> = _state.asStateFlow()

    private val _askNotifications = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Once per play: the activity asks for the notification permission the first time. */
    val askNotifications: SharedFlow<Unit> = _askNotifications.asSharedFlow()

    private var engineOrNull: PlaybackEngine? = null

    /** Built on first use, on the main thread. */
    val engine: PlaybackEngine
        get() = engineOrNull ?: engineFactory().also {
            engineOrNull = it
            wire(it)
        }

    private var controller: ListenableFuture<MediaController>? = null
    private var serverId: String? = null
    /** Audiobookshelf's play session of each book played in this run. */
    private val sessions = HashMap<String, String?>()
    private var starting = false
    private var startJob: Job? = null

    // region PlaybackControls

    override fun play(serverId: String, book: Book, atMs: Long?) {
        val composite = BookId.of(serverId, book.id).value
        val spoken = engine.spoken
        if (spoken.bookId == composite && atMs == null) {
            spoken.play()
            return
        }
        startJob?.cancel()
        startJob = main.launch { start(serverId, book, atMs) }
    }

    override fun toggle() {
        val spoken = engineOrNull?.spoken
        if (spoken?.bookId != null) {
            spoken.toggle()
            return
        }
        val last = lastBook ?: return
        startJob?.cancel()
        startJob = main.launch {
            val source = sources.source(last.serverId) ?: return@launch fail(null)
            val book = try {
                source.book(last.bookId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@launch fail(e)
            }
            start(last.serverId, book, atMs = null)
        }
    }

    override fun pause() {
        engineOrNull?.spoken?.pause()
    }

    override fun seek(positionMs: Long) {
        engineOrNull?.spoken?.seek(positionMs)
    }

    override fun skipBack() {
        engineOrNull?.spoken?.skipBack()
    }

    override fun skipForward() {
        engineOrNull?.spoken?.skipForward()
    }

    override fun nextChapter() {
        engineOrNull?.spoken?.nextChapter()
    }

    override fun previousChapter() {
        engineOrNull?.spoken?.previousChapter()
    }

    override fun setSpeed(speed: Float) {
        val spoken = engineOrNull?.spoken ?: return
        val value = PlaybackSpeed.normalized(speed)
        spoken.setSpeed(value)
        spoken.bookId?.let { id -> main.launch { settings.rememberSpeed(id, value) } }
    }

    override fun setSleep(preset: SleepTimer.Preset) {
        engineOrNull?.spoken?.sleep(preset)
    }

    override fun setSleepEnd(end: SleepEnd) {
        engineOrNull?.spoken?.sleepAt(end)
    }

    override fun extendSleep() {
        engineOrNull?.spoken?.extendSleep()
    }

    override fun cancelSleep() {
        engineOrNull?.spoken?.cancelSleep()
    }

    override fun stop() {
        startJob?.cancel()
        engineOrNull?.spoken?.stop()
        lastBook = null
        lastBookFile.delete()
        serverId = null
        _state.value = PlaybackUi()
    }

    // endregion

    /** Going to the background: the position is saved at once. */
    fun saveNow() {
        engineOrNull?.spoken?.saveForTeardown()
    }

    /** Sends what is queued (launch, foreground, a network that came back). */
    fun flush() = outbox.flushSoon()

    private suspend fun start(serverId: String, book: Book, atMs: Long?) {
        val composite = BookId.of(serverId, book.id).value
        starting = true
        _state.update {
            it.copy(
                bookId = composite, serverId = serverId, title = book.title, author = book.authorLine,
                cover = bookCoverRequest(context, serverId, book.id, book.coverUrl), isLoading = true, error = null, restored = false,
                durationMs = book.durationMs,
            )
        }
        connect()
        try {
            val source = sources.source(serverId) ?: throw IllegalStateException("no source for $serverId")
            val full = if (book.files.isNullOrEmpty()) source.book(book.id) else book
            val files = full.files.orEmpty()
            if (files.isEmpty()) throw IllegalStateException("${full.id} has no files")
            val start = atMs ?: resumePosition(source, full, composite)
            val prefs = settings.prefs.value
            val net = network.value
            val saver = net.metered && prefs.mobileData == MobileData.SAVER
            val kbps = if (saver) PlaybackPrefs.SAVER_BITRATE_KBPS else null
            val parts: List<SpokenPart> = if (source is AudiobookshelfBookSource) {
                // The play session is what Audiobookshelf counts listening by; Saver makes it one HLS stream.
                val plan = source.playPlan(full, kbps)
                sessions[composite] = plan.sessionId
                if (plan.parts.any { it.isHls }) plan.spokenParts() else files.map { placeholder(composite, it, null) }
            } else {
                files.map { placeholder(composite, it, kbps) }
            }
            this.serverId = serverId
            val spokenBook = SpokenBook(
                id = composite, title = full.title, author = full.authorLine.ifBlank { null }, artworkUri = full.coverUrl,
                parts = parts, serverChapters = full.spokenChapters(),
                partTitles = if (parts.size == files.size) files.map { it.title } else null,
            )
            val spoken = engine.spoken
            applyPrefs(spoken, prefs)
            spoken.load(spokenBook, start, autoplay = true, speed = prefs.speedFor(composite))
            remember(spoken, full.coverUrl)
            _askNotifications.tryEmit(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.error("playback", "couldn't start ${book.id}: ${e.message}")
            fail(e)
        } finally {
            starting = false
            engineOrNull?.spoken?.refresh()
        }
    }

    private fun placeholder(composite: String, file: audio.own.book.library.BookFile, kbps: Int?) = SpokenPart(
        id = file.id,
        uri = PlaybackUris.forItem(composite, file.id, kbps).toString(),
        durationMs = file.durationMs,
        streamsByRange = kbps == null,
        contentType = file.contentType,
    )

    /** book-ios's resume choice: this phone's position against the server's; B7 adds the question. */
    private suspend fun resumePosition(source: BookSource, book: Book, composite: String): Long {
        val local = outbox.local(composite)
        val server = if (offline.isOffline) null else runCatching { source.progress(book.id) }.getOrNull()
        return when (val choice = ResumeChoice.decide(local, server)) {
            is ResumeChoice.Start -> choice.positionMs
            // Until the "Continue from ‹device›?" dialog (B7), the other device's newer place wins.
            is ResumeChoice.Ask -> choice.otherMs
        }
    }

    private fun fail(error: Exception?) {
        val message = context.getString(R.string.playback_failed)
        if (error != null) log.error("playback", error.message ?: error.javaClass.simpleName)
        _state.update { it.copy(isLoading = false, isPlaying = false, error = message) }
    }

    private fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, BookPlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }

    private fun wire(engine: PlaybackEngine) {
        val spoken = engine.spoken
        spoken.partTitle = { context.getString(R.string.part_n, it + 1) }
        spoken.chapterTitle = { context.getString(R.string.chapter_n, it + 1) }
        spoken.onPosition = ::onPosition
        spoken.canPrefetchWhole = { network.value.let { it.connected && !it.metered && !it.dataSaver } }
        main.launch {
            settings.prefs.collect { prefs ->
                applyPrefs(spoken, prefs)
                val minutes = if (network.value.dataSaver) PlaybackPrefs.CONSTRAINED_LOOK_AHEAD_MINUTES else prefs.lookAheadMinutes
                engine.loadControl.lookAheadMs = minutes * 60_000L
            }
        }
        main.launch {
            network.collect { net ->
                spoken.networkChanged(net.connected)
                if (net.connected) outbox.flushSoon()
            }
        }
        main.launch {
            combine(spoken.state, settings.prefs) { s, prefs -> s to prefs }.collect { (s, prefs) -> publish(s, prefs) }
        }
    }

    private fun applyPrefs(spoken: SpokenPlayer, prefs: PlaybackPrefs) {
        spoken.skipBackSecs = prefs.skipBackSecs
        spoken.skipForwardSecs = prefs.skipForwardSecs
        spoken.smartRewind = SmartRewind(prefs.smartRewind)
    }

    private fun onPosition(position: SpokenPosition) {
        val id = BookId(position.bookId)
        val server = id.serverId
        val closes = position.reason == ReportReason.STOP || position.reason == ReportReason.FINISHED
        outbox.record(
            ProgressOutbox.Entry(
                serverId = server, bookId = id.bookId, sessionId = sessions[position.bookId], positionMs = position.positionMs,
                durationMs = position.durationMs, listenedMs = position.listenedMs, finished = position.finished,
                atMs = position.atMs, closes = closes,
            ),
            immediate = position.reason != ReportReason.PERIODIC,
        )
        lastBook?.takeIf { it.bookId == id.bookId && it.serverId == server }?.let {
            lastBook = it.copy(positionMs = position.positionMs, speed = engineOrNull?.spoken?.state?.value?.speed ?: it.speed).also(lastBookFile::write)
        }
    }

    private fun remember(spoken: SpokenPlayer, coverUrl: String?) {
        val book = spoken.currentBook ?: return
        val server = serverId ?: return
        lastBook = LastBook(
            serverId = server, bookId = BookId(book.id).bookId, title = book.title, author = book.author.orEmpty(),
            coverUrl = coverUrl, positionMs = spoken.positionMs(), durationMs = spoken.state.value.durationMs,
            speed = spoken.state.value.speed,
        ).also(lastBookFile::write)
    }

    private fun publish(s: SpokenPlayer.State, prefs: PlaybackPrefs) {
        val book = s.book
        if (book == null) {
            // Nothing loaded: keep a book that is starting, else show the last one, paused.
            if (!starting && startJob?.isActive != true && _state.value.error == null) _state.value = restoredState()
            return
        }
        val id = BookId(book.id)
        _state.value = PlaybackUi(
            bookId = book.id,
            serverId = id.serverId,
            title = book.title,
            author = book.author.orEmpty(),
            cover = bookCoverRequest(context, id.serverId, id.bookId, book.artworkUri),
            isPlaying = s.playWhenReady,
            isLoading = s.isLoading || starting,
            waitingForSignal = s.waitingForSignal,
            positionMs = s.positionMs,
            durationMs = s.durationMs,
            chapters = s.chapters,
            hasRealChapters = s.chapterOrigin != ChapterOrigin.PARTS,
            speed = s.speed,
            skipBackSecs = prefs.skipBackSecs,
            skipForwardSecs = prefs.skipForwardSecs,
            sleep = when {
                s.sleepEnd == SleepEnd.END_OF_CHAPTER -> SleepUi.EndOfChapter
                s.sleepEnd == SleepEnd.END_OF_BOOK -> SleepUi.EndOfBook
                s.sleepRemainingSecs != null -> SleepUi.Countdown(s.sleepRemainingSecs!!)
                else -> SleepUi.Off
            },
            savedAheadMs = s.savedAheadMs,
            error = s.error?.let { context.getString(R.string.playback_failed) },
            restored = false,
        )
    }

    private fun restoredState(): PlaybackUi {
        val last = lastBook ?: return PlaybackUi()
        return PlaybackUi(
            bookId = BookId.of(last.serverId, last.bookId).value,
            serverId = last.serverId,
            title = last.title,
            author = last.author,
            cover = bookCoverRequest(context, last.serverId, last.bookId, last.coverUrl),
            positionMs = last.positionMs,
            durationMs = last.durationMs,
            speed = last.speed,
            skipBackSecs = settings.prefs.value.skipBackSecs,
            skipForwardSecs = settings.prefs.value.skipForwardSecs,
            restored = true,
        )
    }
}
