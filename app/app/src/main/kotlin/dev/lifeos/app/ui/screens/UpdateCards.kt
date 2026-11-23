package dev.lifeos.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.data.Interaction
import dev.lifeos.app.data.UpdateRequest
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

// ---------------------------------------------------------------------------- applying an update (#180)

/**
 * A chat in the LifeOS program folder asks for its committed changes to be applied: what a yes
 * starts (tests, the app update, a restart of the computer side, once no run is active), the
 * commit it applies, and Allow / Don't allow like the other approval cards. It runs code a
 * phone chat changed outside the sandbox, so it stands out like an MCP server's card.
 */
@Composable
internal fun ColumnScope.ApplyUpdateCard(interaction: Interaction, open: Boolean, onApprove: (Boolean) -> Unit) {
    val colors = LifeTheme.colors
    val update = interaction.update ?: return
    CardLabel(stringResource(R.string.apply_update_label))
    Text(text = stringResource(R.string.apply_update_title), style = LifeType.rowTitle, color = colors.text)
    update.problem?.let { problem ->
        Text(text = stringResource(problem.textRes()), style = LifeType.label, color = colors.text)
        return
    }
    Text(text = stringResource(R.string.apply_update_steps), style = LifeType.label, color = colors.text)
    Exact(stringResource(R.string.apply_update_commit), update.shortCommit)
    if (update.shortRunning.isNotEmpty()) {
        Text(text = stringResource(R.string.apply_update_running, update.shortRunning), style = LifeType.small, color = colors.muted)
    }
    Text(text = stringResource(R.string.apply_update_warning), style = LifeType.small, color = colors.warn)
    when (interaction.status) {
        Interaction.Status.ALLOWED -> Notice(stringResource(R.string.apply_update_allowed), alert = false)
        Interaction.Status.NOT_DONE -> Notice(stringResource((update.notDone ?: UpdateRequest.NotDone.OTHER).textRes()), alert = true)
        else -> Unit
    }
    if (!open) return
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(text = stringResource(R.string.approval_allow), onClick = { onApprove(true) }, modifier = Modifier.weight(1f))
        SecondaryButton(text = stringResource(R.string.approval_deny), onClick = { onApprove(false) }, modifier = Modifier.weight(1f))
    }
    Text(text = stringResource(R.string.apply_update_note), style = LifeType.small, color = colors.muted)
}

/** What the card says about a request the computer refused at once. */
private fun UpdateRequest.Problem.textRes(): Int = when (this) {
    UpdateRequest.Problem.NOT_PROGRAM_FOLDER -> R.string.apply_update_problem_not_program_folder
    UpdateRequest.Problem.IN_PROGRESS -> R.string.apply_update_problem_in_progress
    UpdateRequest.Problem.NO_COMMIT -> R.string.apply_update_problem_no_commit
    UpdateRequest.Problem.UNRECORDED -> R.string.apply_update_problem_unrecorded
    UpdateRequest.Problem.OTHER -> R.string.apply_update_problem_other
}

/** Why a yes was not acted on. */
private fun UpdateRequest.NotDone.textRes(): Int = when (this) {
    UpdateRequest.NotDone.CHANGED -> R.string.apply_update_not_done_changed
    UpdateRequest.NotDone.IN_PROGRESS -> R.string.apply_update_not_done_in_progress
    UpdateRequest.NotDone.OTHER -> R.string.apply_update_not_done_other
}

/** What an update card says once it is no longer open; a yes and a "not done" are the card's own lines. */
internal fun Interaction.Status.updateResultRes(): Int? = when (this) {
    Interaction.Status.SENDING -> R.string.interaction_sending
    Interaction.Status.REFUSED -> R.string.apply_update_refused
    Interaction.Status.EXPIRED -> R.string.apply_update_expired
    Interaction.Status.GONE -> R.string.interaction_gone
    else -> null
}
