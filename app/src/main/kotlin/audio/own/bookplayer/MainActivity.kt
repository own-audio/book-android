package audio.own.bookplayer

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import audio.own.bookplayer.playback.PlayRequests
import audio.own.bookplayer.playback.VoiceSearch
import audio.own.bookplayer.ui.AppRoot
import audio.own.bookplayer.widget.WidgetSnapshot
import kotlinx.coroutines.launch

/** The one activity. AppCompat, so the in-app language applies below Android 13 too. */
class MainActivity : AppCompatActivity() {
    private val container get() = (application as OwnAudioApp).container

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppRoot(container) }
        // After a rotation the same intent comes back; it was handled the first time.
        if (savedInstanceState == null) handle(intent)
        // The media notification needs the permission from Android 13; asked on the first play only.
        lifecycleScope.launch {
            container.playbackModel.askNotifications.collect {
                val prefs = getSharedPreferences("permissions", Context.MODE_PRIVATE)
                if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean(ASKED, false) &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.edit { putBoolean(ASKED, true) }
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /**
     * The widget's and other apps' `ownbook://play/<id>` plays that book (and may ask where to
     * continue); the Continue Listening shortcut and the assistant's "play ‹book›" play what
     * they name, from the newer position.
     */
    private fun handle(intent: Intent?) {
        intent ?: return
        val requests = container.playRequests
        when (intent.action) {
            ACTION_CONTINUE -> lifecycleScope.launch { if (!requests.continueListening()) say(R.string.voice_nothing_started) }
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)
                lifecycleScope.launch {
                    if (!requests.playFromSearch(query)) say(if (VoiceSearch.isContinue(query)) R.string.voice_nothing_started else R.string.voice_nothing_found)
                }
            }
            Intent.ACTION_VIEW -> {
                val id = intent.data?.let(WidgetSnapshot::bookId) ?: return
                lifecycleScope.launch {
                    when (requests.playLink(id)) {
                        PlayRequests.LinkResult.PLAYING -> Unit
                        PlayRequests.LinkResult.NO_SERVER -> say(R.string.link_no_server)
                        PlayRequests.LinkResult.NOT_FOUND -> say(R.string.link_book_gone)
                    }
                }
            }
        }
    }

    private fun say(text: Int) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        // Sends what is queued, and brings the catalogue and other devices' positions up to date.
        container.playbackModel.setForeground(true)
        container.sync.liveUpdates.setForeground(true)
        container.narrations.setForeground(true)
    }

    override fun onStop() {
        // Going to the background saves the position at once.
        container.playbackModel.saveNow()
        container.playbackModel.setForeground(false)
        container.sync.liveUpdates.setForeground(false)
        container.narrations.setForeground(false)
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "openPlayer"
        const val ACTION_CONTINUE = "audio.own.bookplayer.CONTINUE_LISTENING"
        private const val ASKED = "notificationsAsked"
    }
}
