package audio.own.bookplayer.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import audio.own.bookplayer.R
import audio.own.bookplayer.browse.clock
import audio.own.bookplayer.playback.PlaybackModel

/**
 * "Continue from ‹device›?" (book-ios `RootView`): another device went on with the book since
 * this phone last saved, so either place may be the right one. Dismissing starts nothing.
 */
@Composable
fun ResumeDialog(question: PlaybackModel.ResumeQuestion, onOther: () -> Unit, onLocal: () -> Unit, onDismiss: () -> Unit) {
    val device = question.otherDevice
    val other = clock(question.otherMs)
    val local = clock(question.localMs)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.continue_from_question, device ?: stringResource(R.string.another_device))) },
        text = { Text(stringResource(R.string.resume_message, device ?: stringResource(R.string.another_device_start), other, local)) },
        confirmButton = {
            // Two long choices: one under the other, each a whole line.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp.Hairline)) {
                TextButton(onClick = onOther, modifier = Modifier.testTag("resume.other")) {
                    Text(stringResource(R.string.continue_from_at, device ?: stringResource(R.string.there), other), textAlign = TextAlign.End)
                }
                TextButton(onClick = onLocal, modifier = Modifier.testTag("resume.local")) {
                    Text(stringResource(R.string.keep_this_position, local), textAlign = TextAlign.End)
                }
            }
        },
    )
}
