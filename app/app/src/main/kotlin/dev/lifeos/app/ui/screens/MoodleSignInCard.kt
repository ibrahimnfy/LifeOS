package dev.lifeos.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.MoodleSignInProblem
import dev.lifeos.app.MoodleSignInState
import dev.lifeos.app.R
import dev.lifeos.app.signin.SignInBrowser
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch

/** Start a renewal: ask the computer for the link, then open the site's page in a browser tab. */
suspend fun renewMoodleSignIn(core: LifeOsCore, context: Context) {
    val link = core.startMoodleSignIn() ?: return
    if (!SignInBrowser.open(context, link)) core.dismissMoodleSignIn()
}

/**
 * The Moodle sign-in from the phone (#100): why it is needed ([expired]), the button, and how
 * the renewal goes. The password is typed only on the site's own page; the app never sees it
 * and keeps nothing of the token.
 */
@Composable
fun MoodleSignInCard(core: LifeOsCore, expired: Boolean, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by core.moodleSignIn.collectAsStateWithLifecycle()
    LifeCard(modifier) {
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(if (expired) R.string.courses_sign_in_needed else R.string.signin_explain),
                style = LifeType.body,
                color = colors.text,
            )
            val status = when (val current = state) {
                MoodleSignInState.Idle -> null
                MoodleSignInState.Starting -> stringResource(R.string.signin_starting)
                MoodleSignInState.InBrowser -> stringResource(R.string.signin_in_browser)
                MoodleSignInState.Sending -> stringResource(R.string.signin_sending)
                is MoodleSignInState.Done -> stringResource(R.string.signin_done)
                is MoodleSignInState.Failed -> stringResource(
                    when (current.problem) {
                        MoodleSignInProblem.UNREACHABLE -> R.string.signin_failed_unreachable
                        MoodleSignInProblem.SITE -> R.string.signin_failed_site
                        MoodleSignInProblem.EXPIRED -> R.string.signin_failed_expired
                        MoodleSignInProblem.NOT_THIS_ATTEMPT -> R.string.signin_failed_mismatch
                        MoodleSignInProblem.REFUSED -> R.string.signin_failed_refused
                    },
                )
            }
            if (status != null) {
                Text(status, style = LifeType.small, color = colors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            val busy = state == MoodleSignInState.Starting || state == MoodleSignInState.Sending
            val label = stringResource(if (state == MoodleSignInState.InBrowser) R.string.signin_again else R.string.signin_renew)
            if (expired) {
                PrimaryButton(text = label, enabled = !busy, onClick = { scope.launch { renewMoodleSignIn(core, context) } })
            } else {
                SecondaryButton(text = label, enabled = !busy, onClick = { scope.launch { renewMoodleSignIn(core, context) } })
            }
        }
    }
}
