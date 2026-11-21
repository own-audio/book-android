package audio.own.bookplayer.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import audio.own.core.designsystem.components.GoogleSignInButton
import audio.own.bookplayer.ui.Section
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.core.strings.R
import audio.own.bookplayer.R as AppR
import audio.own.bookplayer.app.text
import audio.own.bookplayer.ui.ButtonRow
import audio.own.bookplayer.ui.ErrorLine
import audio.own.bookplayer.ui.Page
import audio.own.bookplayer.ui.RowDivider

/** Add Server from Settings: the welcome screen's form on a page of its own. */
@Composable
fun AddServerContent(
    state: ServerFormState,
    showGoogle: Boolean,
    onCancel: () -> Unit,
    onAddress: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onName: (String) -> Unit,
    onConnect: () -> Unit,
    onGoogle: () -> Unit,
) {
    Page(title = stringResource(R.string.add_server), onBack = onCancel, closeIsCancel = true) {
        Section(header = null, footer = stringResource(AppR.string.add_server_footer)) {
            FormTextField(state.address, onAddress, stringResource(R.string.address), stringResource(AppR.string.add_server_placeholder), KeyboardType.Uri)
            RowDivider()
            FormTextField(state.username, onUsername, stringResource(AppR.string.user_name_or_email), null, KeyboardType.Email)
            RowDivider()
            FormTextField(
                state.password, onPassword, stringResource(R.string.password), null, KeyboardType.Password, secret = true, ime = ImeAction.Go,
                onGo = { if (state.canConnect) onConnect() },
            )
        }
        Section(header = null) {
            FormTextField(state.name, onName, stringResource(R.string.name_optional), null, KeyboardType.Text, ime = ImeAction.Done)
        }
        state.error?.let { ErrorLine(it.text(), Modifier.padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm)) }
        Section(header = null) {
            ButtonRow(stringResource(R.string.connect), onClick = onConnect, enabled = state.canConnect, working = state.connecting, color = MaterialTheme.colorScheme.primary)
        }
        if (showGoogle && state.googleClientId != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs),
            ) {
                GoogleSignInButton(onClick = onGoogle, enabled = !state.busy)
                Text(stringResource(AppR.string.with_your_own_audio_account), style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.muted)
            }
        }
    }
}
