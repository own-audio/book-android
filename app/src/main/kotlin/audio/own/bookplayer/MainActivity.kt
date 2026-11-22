package audio.own.bookplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import audio.own.bookplayer.ui.AppRoot
import kotlinx.coroutines.launch

/** The one activity. AppCompat, so the in-app language applies below Android 13 too. */
class MainActivity : AppCompatActivity() {
    private val container get() = (application as OwnAudioApp).container

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppRoot(container) }
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
    }

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
        private const val ASKED = "notificationsAsked"
    }
}
