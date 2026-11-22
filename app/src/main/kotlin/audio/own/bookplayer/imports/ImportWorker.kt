package audio.own.bookplayer.imports

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import audio.own.bookplayer.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * One import as a foreground job with a progress notification. WorkManager keeps the job across
 * a kill and a reboot and runs it again; [BookImports.run] goes on from what it wrote down.
 */
class ImportWorker(
    context: Context,
    params: WorkerParameters,
    private val imports: BookImports,
) : CoroutineWorker(context, params) {
    private var title: String? = null

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override suspend fun doWork(): Result = coroutineScope {
        val id = inputData.getString(KEY) ?: return@coroutineScope Result.failure()
        val record = imports.state.value.records[id] ?: return@coroutineScope Result.success()
        title = record.title ?: record.files.firstOrNull()?.fileName
        foreground(null)
        val progress = launch {
            imports.state.map { s -> s.records[id]?.let(s::fraction) }.distinctUntilChanged().sample(1_000).collect { foreground(it) }
        }
        val outcome = imports.run(id, runAttemptCount)
        progress.cancel()
        when (outcome) {
            BookImports.Outcome.DONE -> Result.success()
            BookImports.Outcome.RETRY -> Result.retry()
            BookImports.Outcome.FAILED -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, id.hashCode(), title, null)

    /** Android 12+ may refuse a foreground start from the background; the upload still runs then. */
    private suspend fun foreground(fraction: Float?) {
        try {
            setForeground(foregroundInfo(applicationContext, id.hashCode(), title, fraction))
        } catch (_: IllegalStateException) {
        }
    }

    companion object {
        const val KEY = "import"
        const val TAG = "import"
        const val CHANNEL = "imports"
        fun workName(importId: String) = "import:$importId"

        fun foregroundInfo(context: Context, id: Int, title: String?, fraction: Float?): ForegroundInfo {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.importing), NotificationManager.IMPORTANCE_LOW))
            }
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(if (title != null) context.getString(R.string.uploading_title, title) else context.getString(R.string.importing))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
                .build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(id, notification)
            }
        }

        fun factory(imports: () -> BookImports) = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == ImportWorker::class.java.name) ImportWorker(appContext, workerParameters, imports()) else null
        }
    }
}

/** One unique job per import, on any network: the listener already said yes to mobile data. */
class WorkManagerImportScheduler(private val workManager: () -> WorkManager) : ImportScheduler {
    override fun enqueue(importId: String, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(ImportWorker.KEY to importId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ImportWorker.TAG)
            .build()
        workManager().enqueueUniqueWork(ImportWorker.workName(importId), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(importId: String) {
        workManager().cancelUniqueWork(ImportWorker.workName(importId))
    }
}
