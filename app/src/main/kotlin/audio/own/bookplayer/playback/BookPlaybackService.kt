package audio.own.bookplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import audio.own.bookplayer.MainActivity
import audio.own.bookplayer.OwnAudioApp
import audio.own.bookplayer.R
import audio.own.bookplayer.app.userMessage
import audio.own.book.library.Book
import audio.own.book.library.BookId
import audio.own.player.spoken.SpeedText
import audio.own.player.core.ArtworkBitmapLoader
import audio.own.player.core.PlaybackSessionCallback
import audio.own.player.core.SkipCommands
import audio.own.player.core.SkipIntervals
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The media session for the notification, the lock screen, headsets, the assistant and Android
 * Auto: skip back and forward by the listener's intervals with the numbered icons, previous and
 * next as chapters, the speed in the overflow, and the browse tree of [AutoLibrary]. The player
 * lives in [PlaybackEngine]; this service only puts a session on it. Nobody can answer the
 * resume question here: the newer position wins.
 */
@UnstableApi
class BookPlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private lateinit var model: PlaybackModel
    private lateinit var library: AutoLibrary

    override fun onCreate() {
        super.onCreate()
        val container = (application as OwnAudioApp).container
        model = container.playbackModel
        val engine = model.engine
        val intervals = { container.playbackSettings.prefs.value.let { SkipIntervals(it.skipBackSecs, it.skipForwardSecs) } }
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        library = AutoLibrary(
            library = object : AutoLibrary.Library {
                override val serverId: String? get() = container.sources.active.value?.serverId
                override val source get() = container.sources.active.value?.source?.let(container::screenSource)
                override fun downloaded(serverId: String) = container.downloads.state.value.downloadedBooks(serverId)
                override val playing: PlaybackUi get() = model.state.value
                override suspend fun progress(serverId: String) = container.progressOf(serverId)
            },
            words = AutoWords(),
            artwork = { serverId, book -> container.carArtwork.uri(serverId, book.id, book.coverUrl) },
        )
        val sessionPlayer = BookSessionPlayer(engine.spoken.sessionPlayer) { model.loadedItemIds() }
        val built = MediaLibrarySession.Builder(this, sessionPlayer, Callback(intervals))
            .setSessionActivity(launch)
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(scope, ::artwork)))
            .build()
        session = built
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_launcher_monochrome) })
        // New intervals, a book with (or without) chapters and a new speed change the buttons.
        scope.launch {
            container.playbackSettings.prefs.map { it.skipBackSecs to it.skipForwardSecs }.distinctUntilChanged().collect {
                built.setMediaButtonPreferences(buttons(intervals()))
            }
        }
        scope.launch {
            engine.spoken.state.map { (it.chapters.size > 1) to it.speed }.distinctUntilChanged().collect {
                built.setMediaButtonPreferences(buttons(intervals()))
            }
        }
        // The car's lists follow the server shown, the downloads and the book playing.
        scope.launch {
            container.sources.active.map { it?.serverId to (it?.source != null) }.distinctUntilChanged().drop(1).collect {
                for (id in listOf(AutoContent.ROOT, AutoContent.CONTINUE, AutoContent.DOWNLOADS, AutoContent.LIBRARY, AutoContent.SERIES, AutoContent.AUTHORS)) built.notifyChildrenChanged(id, Int.MAX_VALUE, null)
            }
        }
        scope.launch {
            container.downloads.state.map { snapshot -> snapshot.books.values.filter { it.isComplete }.map { it.compositeId } }.distinctUntilChanged().drop(1).collect {
                built.notifyChildrenChanged(AutoContent.DOWNLOADS, Int.MAX_VALUE, null)
            }
        }
        scope.launch {
            model.state.map { it.bookId to (if (it.restored) 0 else it.chapters.size) }.distinctUntilChanged().drop(1).collect {
                built.notifyChildrenChanged(AutoContent.ROOT, Int.MAX_VALUE, null)
                built.notifyChildrenChanged(AutoContent.CHAPTERS, Int.MAX_VALUE, null)
                built.notifyChildrenChanged(AutoContent.CONTINUE, Int.MAX_VALUE, null)
            }
        }
        scope.launch {
            model.progressChanged.collect { built.notifyChildrenChanged(AutoContent.CONTINUE, Int.MAX_VALUE, null) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            model.saveNow()
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        model.saveNow()
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun artwork(uri: Uri): Bitmap? {
        val request = ImageRequest.Builder(this).data(uri.toString()).size(ARTWORK_PX).allowHardware(false).build()
        return (SingletonImageLoader.get(this).execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    /** The skip buttons, the speed in the overflow (a car shows it next to them), and the chapters when there are some. */
    private fun buttons(intervals: SkipIntervals): List<CommandButton> {
        val state = model.engine.spoken.state.value
        val skips = SkipCommands.buttons(intervals, backLabel(intervals.backSecs), forwardLabel(intervals.forwardSecs))
        val speed = CommandButton.Builder(speedIcon(state.speed))
            .setDisplayName(getString(R.string.speed_label, SpeedText.text(state.speed)))
            .setSessionCommand(SPEED_COMMAND)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        if (state.chapters.size <= 1) return skips + speed
        return skips + speed + listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setDisplayName(getString(R.string.previous_chapter))
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName(getString(R.string.next_chapter))
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun backLabel(secs: Int) = resources.getQuantityString(R.plurals.back_seconds, secs, secs)

    private fun forwardLabel(secs: Int) = resources.getQuantityString(R.plurals.forward_seconds, secs, secs)

    private inner class AutoWords : AutoLibrary.Words {
        override fun title(entry: AutoContent.RootEntry): String = getString(
            when (entry) {
                AutoContent.RootEntry.CONTINUE_LISTENING -> R.string.shelf_continue_listening
                AutoContent.RootEntry.DOWNLOADS -> R.string.tab_downloads
                AutoContent.RootEntry.LIBRARY -> R.string.tab_library
                AutoContent.RootEntry.SERIES -> R.string.series
                AutoContent.RootEntry.AUTHORS -> R.string.authors
            },
        )

        override val chapters: String get() = getString(R.string.chapters)
        override val addServer: String get() = getString(R.string.auto_add_server)
        override val nothingStarted: String get() = getString(R.string.nothing_started_yet)
        override val nothingDownloaded: String get() = getString(R.string.nothing_downloaded)
        override val nothingHere: String get() = getString(R.string.auto_nothing_here)
        override val noChapters: String get() = getString(R.string.no_chapters)

        override fun books(count: Int): String = resources.getQuantityString(R.plurals.books_count, count, count)

        override fun chapter(number: Int): String = getString(R.string.chapter_n, number)

        override fun failure(error: Throwable): String = error.userMessage().resolve(this@BookPlaybackService)
    }

    private inner class Callback(private val intervals: () -> SkipIntervals) : PlaybackSessionCallback(
        scope = scope,
        skipIntervals = { intervals() },
        skipLabels = { backLabel(it.backSecs) to forwardLabel(it.forwardSecs) },
    ) {
        private val requests get() = (application as OwnAudioApp).container.playRequests

        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
            val result = super.onConnectAsync(session, controller).get()
            if (!result.isAccepted) return Futures.immediateFuture(result)
            return Futures.immediateFuture(
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(result.availableSessionCommands.buildUpon().add(SPEED_COMMAND).build())
                    .setAvailablePlayerCommands(result.availablePlayerCommands)
                    .setMediaButtonPreferences(buttons(intervals()))
                    .build(),
            )
        }

        /** Skips in book time, across files; the core's would move within the playing file only. */
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SkipCommands.ACTION_SKIP_BACK -> model.skipBack()
                SkipCommands.ACTION_SKIP_FORWARD -> model.skipForward()
                ACTION_SPEED -> model.setSpeed(AutoContent.nextRate(model.engine.spoken.state.value.speed))
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * A headset's Play, the system's media controls or the assistant after the app was ended:
         * the last book, from the newer position. Asked only for what to show, nothing is opened.
         */
        override suspend fun resumptionItems(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? {
            if (!isForPlayback) {
                val last = model.lastPlayed ?: return null
                val item = MediaItem.Builder()
                    .setMediaId(BookId.of(last.serverId, last.bookId).value)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(last.title).setArtist(last.author.ifBlank { null })
                            .setArtworkUri(last.coverUrl?.let(Uri::parse)).setIsPlayable(true).setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).build(),
                    )
                    .build()
                return MediaSession.MediaItemsWithStartPosition(listOf(item), 0, last.positionMs)
            }
            return if (requests.continueListening()) loaded() else null
        }

        /** What the player holds now, as the session hands it on (and [BookSessionPlayer] lets go). */
        private fun loaded(): MediaSession.MediaItemsWithStartPosition? {
            val player = model.engine.spoken.player
            if (player.mediaItemCount == 0) return null
            val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            return MediaSession.MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            // The system's "recent" root (resumption after a reboot) gets the last book only.
            if (params?.isRecent == true) {
                val recent = MediaItem.Builder().setMediaId(AutoContent.RECENT)
                    .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build()).build()
                return Futures.immediateFuture(LibraryResult.ofItem(recent, params))
            }
            return Futures.immediateFuture(LibraryResult.ofItem(library.root(), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            ready()
            val items = if (parentId == AutoContent.RECENT) recent() else library.children(parentId)
            LibraryResult.ofItemList(paged(items, page, pageSize), params)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = library.item(mediaId)
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val count = runCatching { searchRows(query).size }.getOrDefault(0)
                session.notifySearchResultChanged(browser, query, count, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(paged(searchRows(query), page, pageSize), params)
        }

        private suspend fun searchRows(query: String): List<MediaItem> {
            ready()
            val serverId = (application as OwnAudioApp).container.sources.active.value?.serverId ?: return emptyList()
            return library.rows(serverId, requests.find(query))
        }

        private fun recent(): List<MediaItem> {
            val last = model.lastPlayed ?: return emptyList()
            val book = Book(id = last.bookId, title = last.title, durationMs = last.durationMs, addedAt = java.time.Instant.EPOCH, coverUrl = last.coverUrl)
            return listOf(library.row(last.serverId, book))
        }

        /**
         * A controller picked something: a row in the car, a chapter, or a spoken request ("play
         * ‹book› on own.book", which arrives as a search query). The model plays it; the session
         * then gets back what the player loaded.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    val picked = mediaItems.getOrNull(startIndex.takeIf { it in mediaItems.indices } ?: 0)
                    val id = picked?.mediaId.orEmpty()
                    val query = picked?.requestMetadata?.searchQuery
                    val loadedIds = model.loadedItemIds()
                    val playing = when {
                        // The session handing back what is loaded (a controller that kept the parts).
                        loadedIds.isNotEmpty() && mediaItems.map { it.mediaId } == loadedIds -> true
                        id.startsWith(AutoContent.CHAPTER_PREFIX) -> {
                            val chapter = id.removePrefix(AutoContent.CHAPTER_PREFIX).toIntOrNull()?.let(library::chapter)
                            if (chapter != null) {
                                model.seek(chapter.startMs)
                                model.engine.spoken.play()
                            }
                            chapter != null
                        }
                        id == AutoContent.RECENT -> requests.continueListening()
                        id.isNotEmpty() && !id.startsWith("@") -> {
                            // A part's id is `<book>#<file>`: the book it belongs to.
                            val bookId = id.substringBefore('#')
                            requests.playInCar(bookId, library.known(bookId))
                        }
                        else -> requests.playFromSearch(query)
                    }
                    val loaded = if (playing) loaded() else null
                    if (loaded == null) result.setException(UnsupportedOperationException("Nothing to play")) else result.set(loaded)
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }

        private suspend fun ready() {
            (application as OwnAudioApp).container.servers.state.first { it.loaded }
        }

        private fun <T : Any> future(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
            val result = SettableFuture.create<LibraryResult<T>>()
            scope.launch {
                try {
                    result.set(block())
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }
    }

    private companion object {
        const val ARTWORK_PX = 600
        const val ACTION_SPEED = "audio.own.bookplayer.SPEED"
        val SPEED_COMMAND = SessionCommand(ACTION_SPEED, Bundle.EMPTY)

        fun paged(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
            if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(items)
            return ImmutableList.copyOf(items.drop(page * pageSize).take(pageSize))
        }

        /** The numbered speed icon nearest the speed. */
        fun speedIcon(rate: Float): Int = when {
            rate < 0.9f -> CommandButton.ICON_PLAYBACK_SPEED_0_8
            rate < 1.1f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
            rate < 1.4f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
            rate < 1.65f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
            rate < 1.9f -> CommandButton.ICON_PLAYBACK_SPEED_1_8
            else -> CommandButton.ICON_PLAYBACK_SPEED_2_0
        }
    }
}
