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
import audio.own.book.library.ChangeFeedCatalogue
import audio.own.book.library.ListeningProgress
import audio.own.book.library.PlaybackReport
import audio.own.book.library.ProgressBatch
import audio.own.book.library.ResumeChoice
import audio.own.book.library.SourceException
import audio.own.bookplayer.R
import audio.own.bookplayer.app.AppLog
import audio.own.bookplayer.downloads.BookDownloads
import audio.own.bookplayer.library.PlaybackSources
import audio.own.bookplayer.library.bookCoverRequest
import audio.own.bookplayer.servers.ServersModel
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
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val sources: PlaybackSources,
    private val servers: ServersModel?,
    private val settings: PlaybackSettings,
    private val network: StateFlow<NetworkState>,
    private val offline: OfflineSwitch,
    private val log: AppLog,
    filesDir: File,
    private val engineFactory: () -> PlaybackEngine,
    /** The books on the phone: their files play first, and they play with no network. */
    private val downloads: BookDownloads? = null,
    /** Off in tests, where no media service runs. */
    private val connectsService: Boolean = true,
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

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val outbox = ProgressOutbox(
        file = File(filesDir, "pending-progress.json"),
        send = { entry -> (sources.source(entry.serverId) ?: throw ProgressOutbox.NoSource()).report(entry.report()) },
        log = log,
        scope = io,
        isOffline = { offline.isOffline },
        sendBatch = { serverId, entries ->
            when (val source = sources.source(serverId)) {
                null -> throw ProgressOutbox.NoSource()
                is ProgressBatch -> source.reportAll(entries.map { it.report() })
                else -> null
            }
        },
    ).also { box ->
        box.onAnswer = { entry, answer ->
            when {
                answer != null && !answer.applied -> takeServerPosition(entry.compositeId, answer)
                // A pause's position reached the server: the screens, which read again on the
                // pause itself (before the server had it), read once more.
                stoppedPositionOnItsWay -> {
                    stoppedPositionOnItsWay = false
                    _progressChanged.tryEmit(Unit)
                }
            }
        }
        box.onStale = { entry -> sources.source(entry.serverId)?.progress(entry.bookId)?.let { takeServerPosition(entry.compositeId, it) } }
        box.onSignInRefused = { serverId -> servers?.markRefused(serverId) }
    }

    /** A pause, stop or end was recorded and not yet answered by the server. */
    @Volatile
    private var stoppedPositionOnItsWay = false

    /** Positions changed outside this screen: another device's, a newer one the server kept, a book marked finished. */
    private val _progressChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val progressChanged: SharedFlow<Unit> = _progressChanged.asSharedFlow()

    /** "Continue from ‹device›?": another device went on with the book since this phone last saved. */
    data class ResumeQuestion(val serverId: String, val book: Book, val localMs: Long, val otherMs: Long, val otherDevice: String?)

    private val _resumeQuestion = MutableStateFlow<ResumeQuestion?>(null)
    val resumeQuestion: StateFlow<ResumeQuestion?> = _resumeQuestion.asStateFlow()

    /** A message over whatever screen is open, until dismissed: the book in the player is gone from its server. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    private var inForeground = false
    private var progressRefresh: Job? = null

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

    init {
        // The player moves the part playing over at the next pause or seek, never mid-sentence.
        downloads?.onFileReady = { id ->
            main.launch {
                val spoken = engineOrNull?.spoken ?: return@launch
                val book = spoken.currentBook?.takeIf { it.id == id } ?: return@launch
                book.parts.forEachIndexed { index, part ->
                    if (part.isHls) return@forEachIndexed
                    downloads.localFile(id, part.id)?.let { spoken.useLocalCopy(index, it.toString()) }
                }
            }
        }
        // A network that came back gets a moment to settle before the queue goes out.
        main.launch {
            network.map { it.connected }.distinctUntilChanged().collectLatest { connected ->
                if (!connected) return@collectLatest
                delay(NETWORK_SETTLE_MS)
                outbox.flush()
            }
        }
    }

    // region PlaybackControls

    override fun play(serverId: String, book: Book, atMs: Long?) = play(serverId, book, atMs, mayAsk = true)

    /** [mayAsk] false where nobody can answer the question (Android Auto): the other device's place wins. */
    fun play(serverId: String, book: Book, atMs: Long?, mayAsk: Boolean) {
        val composite = BookId.of(serverId, book.id).value
        val spoken = engine.spoken
        if (spoken.bookId == composite && atMs == null) {
            spoken.play()
            return
        }
        _resumeQuestion.value = null
        startJob?.cancel()
        startJob = main.launch { start(serverId, book, atMs, mayAsk) }
    }

    /** The listener's answer: the other device's place, or this one's. */
    fun answerResume(useOther: Boolean) {
        val question = _resumeQuestion.value ?: return
        _resumeQuestion.value = null
        play(question.serverId, question.book, if (useOther) question.otherMs else question.localMs)
    }

    fun dismissResume() {
        _resumeQuestion.value = null
    }

    override fun toggle() {
        val spoken = engineOrNull?.spoken
        if (spoken?.bookId != null) {
            spoken.toggle()
            return
        }
        val last = lastBook ?: return
        startJob?.cancel()
        startJob = main.launch { startLast(last, mayAsk = true) }
    }

    private suspend fun startLast(last: LastBook, mayAsk: Boolean) {
        val composite = BookId.of(last.serverId, last.bookId).value
        val kept = downloads?.record(composite)
        val book = if (kept != null && (offline.isOffline || downloads?.downloadedBook(composite) != null)) {
            kept
        } else {
            val source = sources.source(last.serverId) ?: return fail(null)
            try {
                source.book(last.bookId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e == SourceException.NotFound) return bookGone(last.serverId, last.bookId)
                kept ?: return fail(e)
            }
        }
        start(last.serverId, book, atMs = null, mayAsk = mayAsk)
    }

    /** The cover link of the book in the player, or of the last one. */
    val coverUrl: String? get() = engineOrNull?.spoken?.currentBook?.artworkUri ?: lastBook?.coverUrl

    /** The book listened to last, as remembered on disk: what a headset's Play after a kill resumes. */
    val lastPlayed: LastBook? get() = lastBook

    /**
     * For callers nobody can answer the resume question for (the car, the assistant, a headset
     * after a kill): plays [book] from the newer position and waits until it is loaded. False
     * when it didn't start.
     */
    suspend fun playNow(serverId: String, book: Book): Boolean {
        play(serverId, book, atMs = null, mayAsk = false)
        startJob?.join()
        return engine.spoken.bookId == BookId.of(serverId, book.id).value
    }

    /**
     * Goes on with the book in the player, else the last one (also after the app was ended), and
     * waits until it plays; the newer position wins. False when there is none or it didn't start.
     */
    suspend fun resumeLast(): Boolean {
        val spoken = engine.spoken
        if (spoken.bookId != null) {
            spoken.play()
            return true
        }
        servers?.state?.first { it.loaded }
        downloads?.let { d -> withTimeoutOrNull(LOAD_WAIT_MS) { d.state.first { it.loaded } } }
        val last = lastBook ?: return false
        startJob?.cancel()
        startJob = main.launch { startLast(last, mayAsk = false) }
        startJob?.join()
        return spoken.bookId == BookId.of(last.serverId, last.bookId).value
    }

    /** The ids of the parts in the player, as the session's player carries them. */
    fun loadedItemIds(): List<String> {
        val player = engineOrNull?.spoken?.player ?: return emptyList()
        return (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
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

    /**
     * The app in front or not: in front, positions made elsewhere are read now and every three
     * minutes while a book plays (nobody sees them in the background).
     */
    fun setForeground(foreground: Boolean) {
        inForeground = foreground
        progressRefresh?.cancel()
        if (!foreground) return
        flush()
        progressRefresh = main.launch {
            refreshProgress()
            while (true) {
                delay(PROGRESS_REFRESH_MS)
                if (_state.value.isPlaying) refreshProgress()
            }
        }
    }

    /**
     * Positions made on other devices, from the active server: own.audio's come with its change
     * feed; Audiobookshelf's screens read them again.
     */
    suspend fun refreshProgress() {
        if (offline.isOffline) return
        val library = sources.active.value ?: return
        val source = library.source
        if (source is ChangeFeedCatalogue) {
            sources.sync(source)
            checkStillThere(library.serverId, source)
        }
        _progressChanged.tryEmit(Unit)
    }

    /**
     * The catalogue caught up: if it no longer has the book in the player (or the mini player),
     * the server is asked, and a book it doesn't know either is let go of.
     */
    private suspend fun checkStillThere(serverId: String, source: BookSource) {
        val loaded = _state.value.bookId?.let(::BookId) ?: lastBook?.let { BookId.of(it.serverId, it.bookId) } ?: return
        if (loaded.serverId != serverId) return
        val gone = try {
            source.book(loaded.bookId)
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e == SourceException.NotFound
        }
        if (gone) kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { bookGone(serverId, loaded.bookId) }
    }

    /**
     * The server says [bookId] is not there (deleted, or no longer shared): the player stops
     * without reporting, the mini player and the saved place forget it, and the listener is told.
     */
    private fun bookGone(serverId: String, bookId: String) {
        val composite = BookId.of(serverId, bookId).value
        val shown = _state.value.bookId == composite || lastBook?.let { it.serverId == serverId && it.bookId == bookId } == true
        log.info("playback", "the book in the player is gone from the server")
        forgetPlayback(serverId, bookId)
        if (shown) _notice.value = context.getString(R.string.book_gone)
    }

    /**
     * Marks [book] finished or not: the engine lets go of it without reporting where it was,
     * nothing queued for it is sent afterwards, then the server is told. Throws what the server
     * answers.
     */
    suspend fun markFinished(serverId: String, book: Book, finished: Boolean) {
        val source = sources.source(serverId) ?: throw SourceException.NotFound
        forgetPlayback(serverId, book.id)
        val position = if (finished) book.durationMs else 0
        source.saveProgress(book.id, position, book.durationMs, finished, Instant.now())
        _progressChanged.tryEmit(Unit)
    }

    /** Forgets the position on every device; the engine lets go of the book first, as for [markFinished]. */
    suspend fun resetProgress(serverId: String, book: Book) {
        val source = sources.source(serverId) ?: throw SourceException.NotFound
        forgetPlayback(serverId, book.id)
        source.clearProgress(book.id)
        _progressChanged.tryEmit(Unit)
    }

    private fun forgetPlayback(serverId: String, bookId: String) {
        val composite = BookId.of(serverId, bookId).value
        val spoken = engineOrNull?.spoken
        if (spoken?.bookId == composite) {
            startJob?.cancel()
            spoken.stop(reporting = false)
        }
        if (lastBook?.let { it.serverId == serverId && it.bookId == bookId } == true || _state.value.bookId == composite) {
            lastBook = null
            lastBookFile.delete()
            this.serverId = null
            _state.value = PlaybackUi()
        }
        sessions.remove(composite)
        outbox.drop(serverId, bookId)
    }

    /**
     * A server was removed: a book of it in the player stops (nothing is reported to a server
     * that is gone), the mini player forgets it, and nothing queued for it is sent.
     */
    fun forgetServer(serverId: String) {
        main.launch {
            val playing = _state.value.serverId == serverId || lastBook?.serverId == serverId
            if (playing) {
                startJob?.cancel()
                engineOrNull?.spoken?.takeIf { id -> id.bookId?.let { BookId(it).serverId } == serverId }?.stop(reporting = false)
                lastBook = null
                lastBookFile.delete()
                this@PlaybackModel.serverId = null
                _state.value = PlaybackUi()
            }
            _resumeQuestion.update { it?.takeIf { q -> q.serverId != serverId } }
            sessions.keys.removeAll { BookId(it).serverId == serverId }
            outbox.forgetServer(serverId)
        }
    }

    /** A newer position the server kept: this phone takes it, unless the book is playing here. */
    private fun takeServerPosition(composite: String, progress: ListeningProgress) {
        if (engineOrNull?.spoken?.bookId == composite) return
        outbox.setLocal(composite, progress)
        lastBook?.takeIf { BookId.of(it.serverId, it.bookId).value == composite }?.let {
            lastBook = it.copy(positionMs = progress.positionMs).also(lastBookFile::write)
            if (_state.value.restored) _state.value = restoredState()
        }
        _progressChanged.tryEmit(Unit)
    }

    private suspend fun start(serverId: String, book: Book, atMs: Long?, mayAsk: Boolean) {
        val composite = BookId.of(serverId, book.id).value
        val before = _state.value
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
            val kept = downloads?.record(composite)
            val local = downloads?.downloadedBook(composite)
            if (kept != null) io.launch { downloads?.notePlayed(composite) }
            val full = when {
                // From the phone: the files as downloaded, the record as it was.
                local != null -> local
                offline.isOffline -> kept ?: book.takeIf { !it.files.isNullOrEmpty() } ?: throw NotOnDevice()
                book.files.isNullOrEmpty() -> source.book(book.id)
                else -> book
            }
            val files = full.files.orEmpty()
            if (files.isEmpty()) throw IllegalStateException("${full.id} has no files")
            // Offline, what is on the phone plays; a file that isn't waits for the network.
            if (offline.isOffline && files.none { downloads?.localFile(composite, it.id) != null }) throw NotOnDevice()
            val start = atMs ?: when (val choice = resumeChoice(source, full, composite)) {
                is ResumeChoice.Start -> choice.positionMs
                is ResumeChoice.Ask -> if (mayAsk) {
                    _resumeQuestion.value = ResumeQuestion(serverId, full, choice.localMs, choice.otherMs, choice.otherDevice)
                    _state.value = before
                    return
                } else {
                    choice.otherMs
                }
            }
            val prefs = settings.prefs.value
            val net = network.value
            val saver = net.metered && prefs.mobileData == MobileData.SAVER
            val kbps = if (saver) PlaybackPrefs.SAVER_BITRATE_KBPS else null
            val parts: List<SpokenPart> = if (local == null && !offline.isOffline && source is AudiobookshelfBookSource) {
                // The play session is what Audiobookshelf counts listening by; Saver makes it one HLS stream.
                val plan = source.playPlan(full, kbps)
                sessions[composite] = plan.sessionId
                if (plan.parts.any { it.isHls }) plan.spokenParts() else files.map { part(composite, it, null) }
            } else {
                // No play session: an old one would be long closed; positions go as plain saves.
                sessions.remove(composite)
                files.map { part(composite, it, kbps) }
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
            if (e == SourceException.NotFound) bookGone(serverId, book.id) else fail(e)
        } finally {
            starting = false
            engineOrNull?.spoken?.refresh()
        }
    }

    /** A file on the phone plays from there; any other through a placeholder the resolver links. */
    private fun part(composite: String, file: audio.own.book.library.BookFile, kbps: Int?): SpokenPart {
        val local = downloads?.localFile(composite, file.id)
            ?: return placeholder(composite, file, kbps)
        return SpokenPart(id = file.id, uri = local.toString(), durationMs = file.durationMs, streamsByRange = true, contentType = file.contentType)
    }

    private fun placeholder(composite: String, file: audio.own.book.library.BookFile, kbps: Int?) = SpokenPart(
        id = file.id,
        uri = PlaybackUris.forItem(composite, file.id, kbps).toString(),
        durationMs = file.durationMs,
        streamsByRange = kbps == null,
        contentType = file.contentType,
    )

    /**
     * book-ios's resume choice: this phone's position against the server's, which carries every
     * device's (own.audio). Offline, this phone's.
     */
    private suspend fun resumeChoice(source: BookSource, book: Book, composite: String): ResumeChoice {
        val local = outbox.local(composite)
        val server = if (offline.isOffline) null else runCatching { source.progress(book.id) }.getOrNull()
        return ResumeChoice.decide(local, server)
    }

    /** Offline, and none of the book is on the phone. */
    class NotOnDevice : Exception("not on this device")

    private fun fail(error: Exception?) {
        val message = context.getString(if (error is NotOnDevice) R.string.not_on_device else R.string.playback_failed)
        if (error != null) log.error("playback", error.message ?: error.javaClass.simpleName)
        _state.update { it.copy(isLoading = false, isPlaying = false, error = message) }
    }

    private fun connect() {
        if (controller != null || !connectsService) return
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
                engine.loadControl?.lookAheadMs = minutes * 60_000L
            }
        }
        main.launch { network.collect { net -> spoken.networkChanged(net.connected) } }
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
        val closes = position.reason == ReportReason.STOP || position.reason == ReportReason.FINISHED || position.reason == ReportReason.TEARDOWN
        if (position.reason == ReportReason.PAUSE || position.reason == ReportReason.STOP || position.reason == ReportReason.FINISHED) {
            stoppedPositionOnItsWay = true
        }
        val due = outbox.record(
            ProgressOutbox.Entry(
                serverId = server, bookId = id.bookId, sessionId = sessions[position.bookId], positionMs = position.positionMs,
                durationMs = position.durationMs, listenedMs = position.listenedMs, finished = position.finished,
                atMs = position.atMs, closes = closes,
            ),
            position.reason,
        )
        if (due) outbox.flushSoon()
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

    private companion object {
        const val PROGRESS_REFRESH_MS = 180_000L
        const val NETWORK_SETTLE_MS = 2_000L
        const val LOAD_WAIT_MS = 5_000L
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

/** What the server is told about a queued position. */
fun ProgressOutbox.Entry.report() = PlaybackReport(
    bookId = bookId, sessionId = sessionId, positionMs = positionMs, listenedMs = listenedMs, durationMs = durationMs,
    finished = finished, at = Instant.ofEpochMilli(atMs), closes = closes,
)
