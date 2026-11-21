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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import audio.own.bookplayer.MainActivity
import audio.own.bookplayer.OwnAudioApp
import audio.own.bookplayer.R
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The media session for the notification, the lock screen, headsets and (later) Android Auto:
 * skip back and forward by the listener's intervals with the numbered icons, previous and next
 * as chapters. The player lives in [PlaybackEngine]; this service only puts a session on it.
 */
@UnstableApi
class BookPlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private lateinit var model: PlaybackModel

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
        val built = MediaLibrarySession.Builder(this, engine.spoken.sessionPlayer, Callback(intervals))
            .setSessionActivity(launch)
            .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(scope, ::artwork)))
            .build()
        session = built
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_launcher_monochrome) })
        // New intervals or a book with (or without) chapters change the buttons.
        scope.launch {
            container.playbackSettings.prefs.map { it.skipBackSecs to it.skipForwardSecs }.distinctUntilChanged().collect {
                built.setMediaButtonPreferences(buttons(intervals(), hasChapters = engine.spoken.state.value.chapters.size > 1))
            }
        }
        scope.launch {
            engine.spoken.state.map { it.chapters.size > 1 }.distinctUntilChanged().collect { chapters ->
                built.setMediaButtonPreferences(buttons(intervals(), chapters))
            }
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

    private fun buttons(intervals: SkipIntervals, hasChapters: Boolean): List<CommandButton> {
        val skips = SkipCommands.buttons(intervals, backLabel(intervals.backSecs), forwardLabel(intervals.forwardSecs))
        if (!hasChapters) return skips
        return skips + listOf(
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

    private inner class Callback(private val intervals: () -> SkipIntervals) : PlaybackSessionCallback(
        scope = scope,
        skipIntervals = { intervals() },
        skipLabels = { backLabel(it.backSecs) to forwardLabel(it.forwardSecs) },
    ) {
        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
            val result = super.onConnectAsync(session, controller).get()
            if (!result.isAccepted) return Futures.immediateFuture(result)
            val chapters = model.engine.spoken.state.value.chapters.size > 1
            return Futures.immediateFuture(
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(result.availableSessionCommands)
                    .setAvailablePlayerCommands(result.availablePlayerCommands)
                    .setMediaButtonPreferences(buttons(intervals(), chapters))
                    .build(),
            )
        }

        /** Skips in book time, across files; the core's would move within the playing file only. */
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SkipCommands.ACTION_SKIP_BACK -> model.skipBack()
                SkipCommands.ACTION_SKIP_FORWARD -> model.skipForward()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // Browsing for Android Auto comes in B12; until then an empty root.
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder().setMediaId(ROOT)
                .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
    }

    private companion object {
        const val ROOT = "root"
        const val ARTWORK_PX = 600
    }
}
