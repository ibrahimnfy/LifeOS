package dev.lifeos.app.ui.screens

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.UpdateProblem
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.PillButton
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import dev.lifeos.app.update.UpdateState

/**
 * The "update ready" prompt (#138): version and size, one tap to download, check and install.
 * Shown at the top of Today and in Settings while there is something to say.
 */
@Composable
fun UpdateCard(core: LifeOsCore, modifier: Modifier = Modifier) {
    val state by core.updater.updates.collectAsStateWithLifecycle()
    val current = state
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val release = when (current) {
        UpdateState.None -> return
        is UpdateState.Ready -> current.release
        is UpdateState.NotThisBuild -> current.release
        is UpdateState.Downloading -> current.release
        is UpdateState.NeedsPermission -> current.release
        is UpdateState.Installing -> current.release
        is UpdateState.Failed -> current.release
    }
    val size = Formatter.formatShortFileSize(context, release.size)
    LifeCard(modifier) {
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.update_title), style = LifeType.rowTitle, color = colors.text)
            Text(
                stringResource(R.string.update_version, release.versionName.ifBlank { release.versionCode.toString() }, size),
                style = LifeType.small,
                color = colors.muted,
            )
            val status = when (current) {
                is UpdateState.NotThisBuild -> stringResource(R.string.update_not_this_build)
                is UpdateState.Downloading -> stringResource(R.string.update_downloading, Formatter.formatShortFileSize(context, current.bytes), size)
                is UpdateState.NeedsPermission -> stringResource(R.string.update_permission_explain)
                is UpdateState.Installing -> stringResource(R.string.update_installing)
                is UpdateState.Failed -> stringResource(
                    when (current.problem) {
                        UpdateProblem.UNREACHABLE -> R.string.update_failed_unreachable
                        UpdateProblem.DAMAGED -> R.string.update_failed_damaged
                        UpdateProblem.NOT_SIGNED_ALIKE -> R.string.update_failed_signer
                        UpdateProblem.INSTALL -> R.string.update_failed_install
                    },
                )
                else -> stringResource(R.string.update_explain)
            }
            Text(status, style = LifeType.body, color = colors.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            when (current) {
                is UpdateState.Ready, is UpdateState.Failed -> {
                    PrimaryButton(
                        text = stringResource(if (current is UpdateState.Failed) R.string.update_try_again else R.string.update_install),
                        onClick = { core.installUpdate(release) },
                    )
                    Later(core)
                }
                is UpdateState.NeedsPermission -> {
                    PrimaryButton(
                        text = stringResource(R.string.update_permission_open),
                        onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            }
                        },
                    )
                    SecondaryButton(text = stringResource(R.string.update_install), onClick = { core.installUpdate(release) })
                }
                is UpdateState.Installing -> current.confirm?.let { confirm ->
                    SecondaryButton(
                        text = stringResource(R.string.update_open_installer),
                        onClick = {
                            try {
                                context.startActivity(confirm)
                            } catch (_: Exception) {
                                core.updater.installFinished(false)
                            }
                        },
                    )
                }
                is UpdateState.NotThisBuild -> Later(core)
                else -> Unit
            }
        }
    }
}

@Composable
private fun Later(core: LifeOsCore) {
    Row {
        PillButton(text = stringResource(R.string.update_later), onClick = core.updater::dismiss)
    }
}
