package audio.own.bookplayer.playback

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import audio.own.bookplayer.OwnAudioApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends positions still queued while the app is away (a server that was down when the book was
 * paused): the periodic job of docs/android-foundation.md §9.2, for positions.
 */
class ProgressFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? OwnAudioApp ?: return Result.success()
        // The servers are read from disk at start; until then no entry has a server to go to.
        withTimeoutOrNull(30_000) { app.container.servers.state.first { it.loaded } } ?: return Result.retry()
        app.container.playbackModel.outbox.flush()
        return Result.success()
    }

    companion object {
        private const val NAME = "progress-flush"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProgressFlushWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            // Tests and some system states have no WorkManager; the flush at launch still runs.
            runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request) }
        }
    }
}
