package audio.own.bookplayer.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import audio.own.bookplayer.MainActivity
import audio.own.bookplayer.OwnAudioApp
import audio.own.bookplayer.R
import java.io.File
import kotlinx.coroutines.launch
import audio.own.core.strings.R as StringsR

/**
 * The home-screen widget "Continue Listening": the last books, a tap away; one on the small size
 * (cover, progress, time left), three on the medium. Everything comes from the snapshot the app
 * writes ([WidgetStore]); a tap opens the app on that book. Ported from book-ios
 * `ContinueListeningWidget`.
 */
class ContinueListeningWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = WidgetStore(folder(context))
        val entries = store.load()?.entries.orEmpty()
        val covers = entries.mapNotNull { entry -> entry.cover?.let { name -> decode(store.cover(name))?.let { name to it } } }.toMap()
        provideContent { ContinueListeningContent(entries, covers) }
    }

    private fun decode(file: File): Bitmap? = if (file.exists()) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)

        fun folder(context: Context) = File(context.filesDir, "widget")

        val accent = ColorProvider(day = Color(0xFF7A4A2B), night = Color(0xFFD7A97C))
        val accentSoft = ColorProvider(day = Color(0x267A4A2B), night = Color(0x26D7A97C))
        val background = ColorProvider(day = Color(0xFFF7F7F8), night = Color(0xFF0C0C0E))
        val foreground = ColorProvider(day = Color(0xFF1D1D1F), night = Color(0xFFF5F5F7))
        val muted = ColorProvider(day = Color(0xFF6E6E73), night = Color(0xFF98989D))
    }
}

class ContinueListeningWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueListeningWidget()

    /** A widget just placed: the snapshot is brought up to date (the app writes it only while one exists). */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val container = (context.applicationContext as? OwnAudioApp)?.container ?: return
        container.scope.launch { container.widget.refresh() }
    }
}

@Composable
private fun ContinueListeningContent(entries: List<WidgetSnapshot.Entry>, covers: Map<String, Bitmap>) {
    val context = LocalContext.current
    val medium = LocalSize.current.width >= ContinueListeningWidget.MEDIUM.width
    Box(GlanceModifier.fillMaxSize().background(ContinueListeningWidget.background).padding(12.dp)) {
        when {
            entries.isEmpty() -> Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_widget_book), contentDescription = null, colorFilter = ColorFilter.tint(ContinueListeningWidget.accent), modifier = GlanceModifier.size(28.dp))
                Spacer(GlanceModifier.height(6.dp))
                Text(context.getString(R.string.nothing_started_yet), style = TextStyle(color = ContinueListeningWidget.muted, fontSize = 12.sp, textAlign = TextAlign.Center))
            }
            !medium -> Small(entries.first(), covers)
            else -> Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
                entries.take(WidgetSnapshot.MAX_ENTRIES).forEachIndexed { index, entry ->
                    if (index > 0) Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(open(context, entry))), verticalAlignment = Alignment.CenterVertically) {
                        Cover(covers[entry.cover], 40)
                        Spacer(GlanceModifier.width(10.dp))
                        Column(GlanceModifier.defaultWeight()) {
                            Text(entry.title, maxLines = 1, style = TextStyle(color = ContinueListeningWidget.foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                            if (entry.author.isNotEmpty()) Text(entry.author, maxLines = 1, style = TextStyle(color = ContinueListeningWidget.muted, fontSize = 11.sp))
                            Bar(entry)
                        }
                        Spacer(GlanceModifier.width(8.dp))
                        Text(remaining(context, entry), style = TextStyle(color = ContinueListeningWidget.muted, fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Small(entry: WidgetSnapshot.Entry, covers: Map<String, Bitmap>) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize().clickable(actionStartActivity(open(context, entry)))) {
        Cover(covers[entry.cover], 56)
        Spacer(GlanceModifier.defaultWeight())
        Text(entry.title, maxLines = 2, style = TextStyle(color = ContinueListeningWidget.foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        Bar(entry)
        Text(remaining(context, entry), style = TextStyle(color = ContinueListeningWidget.muted, fontSize = 11.sp))
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, size: Int) {
    if (bitmap != null) {
        Image(ImageProvider(bitmap), contentDescription = null, contentScale = ContentScale.Crop, modifier = GlanceModifier.size(size.dp).cornerRadius(8.dp))
    } else {
        Box(GlanceModifier.size(size.dp).cornerRadius(8.dp).background(ContinueListeningWidget.accentSoft), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_widget_book), contentDescription = null, colorFilter = ColorFilter.tint(ContinueListeningWidget.accent), modifier = GlanceModifier.size((size / 2).dp))
        }
    }
}

@Composable
private fun Bar(entry: WidgetSnapshot.Entry) {
    LinearProgressIndicator(
        progress = entry.fraction.toFloat().coerceAtLeast(0.03f),
        color = ContinueListeningWidget.accent,
        backgroundColor = ContinueListeningWidget.accentSoft,
        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
    )
}

private fun remaining(context: Context, entry: WidgetSnapshot.Entry): String {
    val minutes = (entry.remainingMs / 60_000).toInt()
    val text = if (minutes >= 60) context.getString(StringsR.string.duration_hours_minutes, minutes / 60, minutes % 60) else context.getString(StringsR.string.duration_minutes, minutes)
    return context.getString(R.string.time_left, text)
}

private fun open(context: Context, entry: WidgetSnapshot.Entry): Intent =
    Intent(Intent.ACTION_VIEW, WidgetSnapshot.playLink(entry.id)).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
