package dev.lifeos.app.ui.screens

import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.ConnectionState
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.TestProblem
import dev.lifeos.app.TestResult
import dev.lifeos.app.lock.LockTimeout
import dev.lifeos.app.ui.components.SecondaryButton
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import dev.lifeos.app.BuildConfig
import dev.lifeos.app.R
import dev.lifeos.app.settings.LanguageSetting
import dev.lifeos.app.settings.ThemeSetting
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.StatusDot
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import java.util.Locale

@Composable
fun SettingsScreen(
    core: LifeOsCore,
    theme: ThemeSetting,
    onThemeChange: (ThemeSetting) -> Unit,
    onPair: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LifeTheme.colors
    val scope = rememberCoroutineScope()
    val lockTimeout by core.settings.lockTimeout.collectAsStateWithLifecycle(initialValue = LockTimeout.Default)
    // The per-app locale kept by the platform is the source of truth for the language.
    var language by remember {
        mutableStateOf(
            LanguageSetting.fromLanguageTags(AppCompatDelegate.getApplicationLocales().toLanguageTags()),
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.more_settings), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad)
                .padding(top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsGroup(stringResource(R.string.settings_language), selectable = true) {
                LanguageSetting.entries.forEachIndexed { index, option ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(label = option.label(), selected = option == language) {
                        language = option
                        // Switches the app language at once; the activity is recreated.
                        AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(option.toLanguageTags()),
                        )
                    }
                }
            }

            SettingsGroup(stringResource(R.string.settings_appearance), selectable = true) {
                val options = listOf(ThemeSetting.LIGHT, ThemeSetting.DARK, ThemeSetting.SYSTEM)
                options.forEachIndexed { index, option ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(label = option.label(), selected = option == theme) { onThemeChange(option) }
                }
            }

            SettingsGroup(stringResource(R.string.settings_connection)) {
                ConnectionSection(core = core, onPair = onPair)
            }

            SettingsGroup(stringResource(R.string.settings_lock), selectable = true) {
                LockTimeout.entries.forEachIndexed { index, option ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(label = option.label(), selected = option == lockTimeout) {
                        scope.launch { core.settings.setLockTimeout(option) }
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_lock_note),
                style = LifeType.small,
                color = colors.muted,
                modifier = Modifier.padding(top = 0.dp),
            )

            SettingsGroup(stringResource(R.string.settings_about)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LifeDimens.minTouch)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_version),
                        style = LifeType.body,
                        color = colors.text,
                    )
                    Text(text = BuildConfig.VERSION_NAME, style = LifeType.body, color = colors.muted)
                }
            }
        }
    }
}

/** Connection: not paired → "Pair with computer"; paired → state, name, last contact, test, unpair. */
@Composable
private fun ConnectionSection(core: LifeOsCore, onPair: () -> Unit) {
    val colors = LifeTheme.colors
    val scope = rememberCoroutineScope()
    val pairing by core.pairing.collectAsStateWithLifecycle()
    val connection by core.connection.collectAsStateWithLifecycle()
    val lastContact by core.lastContactMillis.collectAsStateWithLifecycle()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    val paired = pairing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state = connection)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(
                when (connection) {
                    ConnectionState.NOT_PAIRED -> R.string.settings_not_paired
                    ConnectionState.CONNECTING -> R.string.settings_state_connecting
                    ConnectionState.CONNECTED -> R.string.settings_state_connected
                    ConnectionState.UNREACHABLE -> R.string.settings_state_unreachable
                },
            ),
            style = LifeType.rowTitle,
            color = colors.text,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    if (paired == null) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(text = stringResource(R.string.settings_pair), onClick = onPair)
            Text(text = stringResource(R.string.settings_pair_hint), style = LifeType.small, color = colors.muted)
        }
        return
    }

    GroupDivider(startIndent = 16.dp)
    ValueRow(stringResource(R.string.settings_paired_name), paired.name)
    GroupDivider(startIndent = 16.dp)
    ValueRow(
        stringResource(R.string.settings_last_contact),
        lastContact?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } ?: stringResource(R.string.settings_last_contact_never),
    )
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrimaryButton(
            text = stringResource(if (testing) R.string.settings_testing else R.string.settings_test),
            enabled = !testing,
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    testResult = core.testConnection()
                    testing = false
                }
            },
        )
        testResult?.let { result ->
            Text(
                text = result.text(),
                style = LifeType.body,
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface2, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        SecondaryButton(text = stringResource(R.string.settings_unpair), danger = true, onClick = { confirmUnpair = true })
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.settings_unpair_title)) },
            text = { Text(stringResource(R.string.settings_unpair_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        testResult = null
                        scope.launch { core.unpair() }
                    },
                ) { Text(stringResource(R.string.settings_unpair_confirm), color = colors.badge) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun TestResult.text(): String = when (this) {
    is TestResult.Ok -> stringResource(R.string.settings_test_ok, millis.toInt(), version)
    is TestResult.Failed -> when (problem) {
        TestProblem.NOT_PAIRED -> stringResource(R.string.settings_test_not_paired)
        TestProblem.RELAY_UNREACHABLE -> stringResource(R.string.settings_test_relay)
        TestProblem.REFUSED -> stringResource(R.string.settings_test_refused)
        TestProblem.NO_ANSWER -> stringResource(R.string.settings_test_no_answer)
        TestProblem.BAD_ANSWER -> stringResource(R.string.settings_test_bad_answer, status)
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = label, style = LifeType.body, color = colors.text)
        Text(
            text = value,
            style = LifeType.body,
            color = colors.muted,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LockTimeout.label(): String = stringResource(
    when (this) {
        LockTimeout.IMMEDIATELY -> R.string.settings_lock_immediately
        LockTimeout.ONE_MINUTE -> R.string.settings_lock_1_min
        LockTimeout.FIVE_MINUTES -> R.string.settings_lock_5_min
    },
)

@Composable
private fun ThemeSetting.label(): String = stringResource(
    when (this) {
        ThemeSetting.LIGHT -> R.string.settings_theme_light
        ThemeSetting.DARK -> R.string.settings_theme_dark
        ThemeSetting.SYSTEM -> R.string.settings_follow_phone
    },
)

/** Languages are named in their own language, so the name is readable whatever the app language is. */
@Composable
private fun LanguageSetting.label(): String {
    val tag = languageTag ?: return stringResource(R.string.settings_follow_phone)
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

@Composable
private fun SettingsGroup(
    title: String,
    selectable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LifeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = LifeType.sectionTitle,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .then(if (selectable) Modifier.selectableGroup() else Modifier),
            content = content,
        )
    }
}

@Composable
private fun GroupDivider(startIndent: Dp = 52.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .heightIn(min = LifeDimens.hairline)
            .background(LifeTheme.colors.divider),
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(1.5.dp, if (selected) colors.primary else colors.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(colors.primary, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = if (selected) LifeType.rowTitle else LifeType.body,
            color = colors.text,
        )
    }
}
