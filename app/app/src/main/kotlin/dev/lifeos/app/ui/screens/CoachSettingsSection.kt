package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.ConnectionState
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.CoachSettingRules
import dev.lifeos.app.data.CoachSettings
import dev.lifeos.app.data.changeCoachSettings
import dev.lifeos.app.data.coachSettings
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch

/**
 * The coach's settings (#76), kept on the computer: how many notifications a day (off, the daily
 * note, up to three), quiet hours, and whether the coach asks questions. The computer enforces
 * the limits; this only reads and changes them.
 */
@Composable
fun CoachSettingsSection(core: LifeOsCore) {
    val colors = LifeTheme.colors
    val scope = rememberCoroutineScope()
    val connection by core.connection.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf<CoachSettings?>(null) }
    var problem by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connection) {
        if (connection != ConnectionState.CONNECTED || settings != null) return@LaunchedEffect
        problem = try {
            settings = core.api.coachSettings().let { it.copy(frequency = CoachSettingRules.frequencyOf(it.frequency)) }
            false
        } catch (_: ApiException) {
            true
        }
    }

    fun change(after: CoachSettings) {
        val before = settings ?: return
        if (before == after || saving) return
        settings = after // shown at once; put back when the computer says no
        saving = true
        scope.launch {
            try {
                val saved = core.api.changeCoachSettings(CoachSettingRules.changes(before, after))
                settings = saved.copy(frequency = CoachSettingRules.frequencyOf(saved.frequency))
                problem = false
            } catch (_: ApiException) {
                settings = before
                problem = true
            } finally {
                saving = false
            }
        }
    }

    val current = settings
    if (current == null) {
        Text(
            text = stringResource(if (problem) R.string.coach_settings_unreachable else R.string.coach_settings_waiting),
            style = LifeType.body,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
        return
    }
    Column(Modifier.selectableGroup()) {
        CoachSettingRules.FREQUENCIES.forEachIndexed { index, option ->
            if (index > 0) Divider()
            FrequencyRow(
                label = stringResource(
                    when (option) {
                        CoachSettingRules.OFF -> R.string.coach_frequency_off
                        CoachSettingRules.ONCE -> R.string.coach_frequency_once
                        else -> R.string.coach_frequency_three
                    },
                ),
                selected = current.frequency == option,
                enabled = !saving,
            ) { change(current.copy(frequency = option)) }
        }
    }
    Divider(0.dp)
    TimeRow(stringResource(R.string.coach_quiet_start), current.quietStart, enabled = !saving) { picking = "start" }
    Divider()
    TimeRow(stringResource(R.string.coach_quiet_end), current.quietEnd, enabled = !saving) { picking = "end" }
    Divider(0.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = current.questions, enabled = !saving, role = Role.Switch) { change(current.copy(questions = it)) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.coach_questions_switch), style = LifeType.body, color = colors.text)
            Text(stringResource(R.string.coach_questions_note), style = LifeType.small, color = colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = current.questions, onCheckedChange = null)
    }
    Text(
        text = stringResource(
            when {
                problem -> R.string.coach_settings_not_saved
                !CoachSettingRules.hasQuietHours(current) -> R.string.coach_quiet_none
                else -> R.string.coach_settings_note
            },
        ),
        style = LifeType.small,
        color = colors.muted,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )

    picking?.let { which ->
        ClockPicker(
            initial = if (which == "start") current.quietStart else current.quietEnd,
            onDismiss = { picking = null },
            dismissLabel = R.string.action_cancel,
        ) { picked ->
            picking = null
            change(if (which == "start") current.copy(quietStart = picked) else current.copy(quietEnd = picked))
        }
    }
}

@Composable
private fun FrequencyRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(1.5.dp, if (selected) colors.primary else colors.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).background(colors.primary, CircleShape))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = LifeType.body, color = colors.text)
    }
}

@Composable
private fun TimeRow(label: String, time: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = LifeType.body, color = colors.text)
        Text(time, style = LifeType.body, color = colors.muted)
    }
}

@Composable
private fun Divider(startIndent: androidx.compose.ui.unit.Dp = 52.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .heightIn(min = LifeDimens.hairline)
            .background(LifeTheme.colors.divider),
    )
}
