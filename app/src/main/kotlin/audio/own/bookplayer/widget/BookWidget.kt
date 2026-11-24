package audio.own.bookplayer.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import audio.own.bookplayer.OwnAudioApp
import kotlinx.coroutines.launch

/**
 * What the three widgets share: they read [WidgetState] when drawn and again on every redraw
 * ([WidgetData.version]); the picker shows them with [WidgetSamples] from Android 15.
 */
abstract class BookWidget : GlanceAppWidget() {
    /** Whether the shelf's covers are needed, not only the book playing. */
    protected abstract val shelfCovers: Boolean

    /** How large the pictures are decoded, in pixels across. */
    protected abstract val coverPx: Int

    @Composable
    abstract fun Content(state: WidgetState)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val readAt = WidgetData.version.value
        val first = read(context)
        provideContent {
            val version by WidgetData.version.collectAsState()
            val state by produceState(first, version) { if (version != readAt) value = read(context) }
            WidgetLook.Theme { Content(state) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val sample = WidgetSamples.state()
        provideContent { WidgetLook.Theme { Content(sample) } }
    }

    private suspend fun read(context: Context): WidgetState {
        val playback = (context.applicationContext as? OwnAudioApp)?.container?.playbackModel?.state?.value
        return WidgetData.load(context, playback, shelfCovers, coverPx)
    }
}

/** A widget just placed: the snapshot is brought up to date (the app writes it only while one exists). */
abstract class BookWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val container = (context.applicationContext as? OwnAudioApp)?.container ?: return
        container.scope.launch { container.widget.refresh() }
    }
}

/** Redraws the widgets placed on the home screen; widgets nobody placed cost nothing. */
object WidgetRedraw {
    suspend fun players(context: Context) {
        WidgetData.version.value++
        PlayerStripWidget().updateAll(context)
        NowPlayingWidget().updateAll(context)
    }

    suspend fun all(context: Context) {
        players(context)
        ContinueListeningWidget().updateAll(context)
    }

    /** Whether any of the three is on a home screen. */
    suspend fun anyPlaced(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return listOf(PlayerStripWidget::class.java, NowPlayingWidget::class.java, ContinueListeningWidget::class.java)
            .any { manager.getGlanceIds(it).isNotEmpty() }
    }
}

/**
 * The picker's previews drawn by the widgets themselves (Android 15 and later), with made-up
 * books. Sent once per app version: the system allows only a few a day.
 */
object WidgetPreviews {
    suspend fun publish(context: Context, versionCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = context.getSharedPreferences("widget-previews", Context.MODE_PRIVATE)
        if (prefs.getInt(SENT, 0) == versionCode) return
        val manager = GlanceAppWidgetManager(context)
        val results = listOf(PlayerStripWidgetReceiver::class, NowPlayingWidgetReceiver::class, ContinueListeningWidgetReceiver::class).map { receiver ->
            runCatching { manager.setWidgetPreviews(receiver) }.getOrDefault(GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_RATE_LIMITED)
        }
        if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) prefs.edit { putInt(SENT, versionCode) }
    }

    private const val SENT = "sentForVersion"
}
