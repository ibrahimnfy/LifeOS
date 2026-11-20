package dev.lifeos.app.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.activity.SleepPrompt
import dev.lifeos.app.push.PushSetup
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * Settings → Notifications (#38): whether push works in this build, the permission (Android
 * 13+), and the way to the system's settings of the four channels (chat, courses, coach,
 * reminders), where the owner mutes a kind. The app keeps no switches of its own for them, so
 * there is one place only where a kind is on or off.
 */
@Composable
fun NotificationsSection() {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(SleepPrompt.mayNotify(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(if (PushSetup.ready(context)) R.string.notifications_push_ready else R.string.notifications_push_missing),
            style = LifeType.body,
            color = colors.text,
        )
        Text(stringResource(R.string.notifications_text), style = LifeType.small, color = colors.muted)
        if (!allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SecondaryButton(text = stringResource(R.string.sleep_prompt_allow), onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) })
        }
        SecondaryButton(
            text = stringResource(R.string.notifications_open_system),
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                } catch (_: ActivityNotFoundException) {
                }
            },
        )
    }
}
