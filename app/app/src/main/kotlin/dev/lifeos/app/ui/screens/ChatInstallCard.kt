package dev.lifeos.app.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.data.Interaction
import dev.lifeos.app.data.SkillInstall
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * A skill the assistant proposed for the session's folder (#97): where it comes from, what
 * is in it (every file with its size, and what is notable about it) and where it would go,
 * all before anything can be approved. The skill's own words are someone else's text: they
 * are shown as plain text, whole and labelled. It is installed only by the tap on "Install",
 * which exists only once the computer has listed its contents.
 */
@Composable
internal fun ColumnScope.SkillInstallCard(interaction: Interaction, open: Boolean, onApprove: (Boolean) -> Unit) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val install = interaction.install ?: return
    CardLabel(stringResource(R.string.install_label))
    Text(
        text = if (install.name.isBlank()) stringResource(R.string.install_title) else stringResource(R.string.install_title_named, install.name),
        style = LifeType.rowTitle,
        color = colors.text,
    )
    Text(text = stringResource(R.string.install_untrusted), style = LifeType.small, color = colors.muted)
    Exact(stringResource(R.string.install_source), install.source)
    when (install.state) {
        SkillInstall.State.DOWNLOADING -> if (open) Notice(stringResource(R.string.install_downloading), alert = false)
        SkillInstall.State.READY -> {
            if (install.description.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(text = stringResource(R.string.install_description), style = LifeType.small, color = colors.muted)
                    SelectionContainer { Text(text = install.description, style = LifeType.body, color = colors.text) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = pluralStringResource(R.plurals.install_files, install.files.size, install.files.size, Formatter.formatShortFileSize(context, install.totalSize)),
                    style = LifeType.small,
                    color = colors.muted,
                )
                // Every file, whole: nothing of a path is cut off.
                install.files.forEach { file ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = file.path, style = LifeType.small.copy(fontFamily = FontFamily.Monospace), color = colors.text, modifier = Modifier.weight(1f))
                        Text(text = Formatter.formatShortFileSize(context, file.size), style = LifeType.small, color = colors.muted)
                    }
                }
            }
            install.warnings.forEach { warning ->
                val text = when (warning) {
                    "shell_lines" -> R.string.install_warn_shell
                    "requests_tools" -> R.string.install_warn_tools
                    "has_scripts" -> R.string.install_warn_scripts
                    else -> null
                }
                if (text != null) Text(text = stringResource(text), style = LifeType.label, color = colors.warn)
            }
            Exact(stringResource(R.string.install_target), install.target)
        }
        SkillInstall.State.FAILED, SkillInstall.State.REFUSED -> Text(
            text = stringResource((install.problem ?: SkillInstall.Problem.OTHER).textRes()),
            style = LifeType.label,
            color = colors.text,
        )
    }
    if (!open) return
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (install.state) {
            SkillInstall.State.READY -> {
                PrimaryButton(text = stringResource(R.string.install_approve), onClick = { onApprove(true) }, modifier = Modifier.weight(1f))
                SecondaryButton(text = stringResource(R.string.install_deny), onClick = { onApprove(false) }, modifier = Modifier.weight(1f))
            }
            // Not listed yet: it can be declined, never installed unseen.
            SkillInstall.State.DOWNLOADING -> SecondaryButton(text = stringResource(R.string.install_deny), onClick = { onApprove(false) }, modifier = Modifier.weight(1f))
            // Nothing to install: putting it away tells the computer to drop it.
            SkillInstall.State.FAILED -> SecondaryButton(text = stringResource(R.string.install_dismiss), onClick = { onApprove(false) }, modifier = Modifier.weight(1f))
            SkillInstall.State.REFUSED -> Unit
        }
    }
    if (install.state == SkillInstall.State.READY) Text(text = stringResource(R.string.install_note), style = LifeType.small, color = colors.muted)
}

private fun SkillInstall.Problem.textRes(): Int = when (this) {
    SkillInstall.Problem.NOT_HTTPS -> R.string.install_problem_https
    SkillInstall.Problem.UNRECORDED -> R.string.install_problem_unrecorded
    SkillInstall.Problem.SOURCE_REFUSED -> R.string.install_problem_refused
    SkillInstall.Problem.TOO_LARGE -> R.string.install_problem_large
    SkillInstall.Problem.UNREADABLE -> R.string.install_problem_unreadable
    SkillInstall.Problem.EXISTS -> R.string.install_problem_exists
    SkillInstall.Problem.DOWNLOAD -> R.string.install_problem_download
    SkillInstall.Problem.OTHER -> R.string.install_problem_other
}
