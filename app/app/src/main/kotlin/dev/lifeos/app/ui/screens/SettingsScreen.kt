package dev.lifeos.app.ui.screens

import androidx.appcompat.app.AppCompatDelegate
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
    theme: ThemeSetting,
    onThemeChange: (ThemeSetting) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LifeTheme.colors
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LifeDimens.minTouch)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot()
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings_not_paired),
                        style = LifeType.rowTitle,
                        color = colors.text,
                    )
                }
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryButton(text = stringResource(R.string.settings_pair), onClick = {}, enabled = false)
                    Text(
                        text = stringResource(R.string.settings_pair_hint),
                        style = LifeType.small,
                        color = colors.muted,
                    )
                }
            }

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
private fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
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
