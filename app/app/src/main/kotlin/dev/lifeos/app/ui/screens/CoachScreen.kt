package dev.lifeos.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.CoachChatView
import dev.lifeos.app.data.JobUsage
import dev.lifeos.app.data.UsageNumbers
import dev.lifeos.app.ui.components.MarkdownText
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.components.TopBarAction
import dev.lifeos.app.ui.components.appLocale
import dev.lifeos.app.ui.components.dayLabel
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import java.time.OffsetDateTime

/**
 * The one conversation with the coach (#114): opened from the coach card on Today and from the
 * pinned entry of the Chat tab. It is read-only and always on the coach's model, so it has
 * neither the model control nor "Changes allowed"; its plan usage stands under the top bar,
 * apart from the morning run's.
 */
@Composable
fun CoachScreen(core: LifeOsCore, onBack: () -> Unit, onConnectionClick: () -> Unit, onPair: () -> Unit, onSessions: () -> Unit) {
    val chat = core.coach
    val state by chat.state.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val view = state.coach
    var about by rememberSaveable { mutableStateOf(false) }
    var confirmTidy by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        TopBar(
            title = stringResource(R.string.coach_conversation),
            subtitle = listOfNotNull(stringResource(R.string.chat_mode_read_only), view?.model, view?.effort).joinToString(" · "),
            onBack = onBack,
            onConnectionClick = onConnectionClick,
            actions = { TopBarAction(LifeIcons.Activity, stringResource(R.string.coach_about), enabled = view != null, onClick = { about = true }) },
        )
        if (view != null) UsageStrip(view, onClick = { about = true })
        ConversationPane(core, chat, onPair = onPair, onSessions = onSessions, modes = false, empty = { CoachEmpty(view, it) })
    }
    if (about && view != null && !locked) {
        CoachSheet(
            view = view,
            canTidy = view.started && state.canSend,
            onTidy = {
                about = false
                confirmTidy = true
            },
            onDismiss = { about = false },
        )
    }
    if (confirmTidy && !locked) {
        ConfirmDialog(
            title = stringResource(R.string.coach_tidy_title),
            text = stringResource(R.string.coach_tidy_text),
            confirm = stringResource(R.string.coach_tidy),
            onDismiss = { confirmTidy = false },
            onConfirm = {
                confirmTidy = false
                chat.tidyCoach()
            },
        )
    }
}

/** Runs, tokens and Claude Code's cost estimate of one period, or "No runs". */
@Composable
private fun usageText(usage: JobUsage): String =
    if (usage.runs == 0) {
        stringResource(R.string.models_no_runs)
    } else {
        listOf(
            pluralStringResource(R.plurals.models_runs, usage.runs, usage.runs),
            stringResource(R.string.models_tokens, UsageNumbers.compact(usage.tokens)),
            UsageNumbers.dollars(usage.costUsd),
        ).joinToString(" · ")
    }

@Composable
private fun UsageLine(label: String, usage: JobUsage) {
    val colors = LifeTheme.colors
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Text(label, style = LifeType.small, color = colors.muted, modifier = Modifier.width(132.dp))
        Text(usageText(usage), style = LifeType.small, color = colors.text, modifier = Modifier.weight(1f))
    }
}

/** Today's plan usage in plain numbers: this conversation, and apart from it the morning run. */
@Composable
private fun UsageStrip(view: CoachChatView, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LifeDimens.pad)
            .heightIn(min = LifeDimens.minTouch)
            .clip(shape)
            .border(LifeDimens.hairline, colors.divider, shape)
            .clickable(onClickLabel = stringResource(R.string.coach_about), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.coach_usage_today), style = LifeType.label, color = colors.muted)
        UsageLine(stringResource(R.string.coach_usage_chat), view.usage.today)
        UsageLine(stringResource(R.string.coach_usage_morning), view.morningUsage.today)
    }
}

/** Before the first message, and right after the conversation was tidied up. */
@Composable
private fun ColumnScope.CoachEmpty(view: CoachChatView?, onPrompt: (String) -> Unit) {
    val colors = LifeTheme.colors
    Text(text = stringResource(R.string.coach_empty_title), style = LifeType.featured, color = colors.text)
    Text(
        text = stringResource(if ((view?.compactions ?: 0) > 0) R.string.coach_empty_tidied else R.string.coach_empty_text),
        style = LifeType.small,
        color = colors.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    listOf(R.string.coach_prompt_1, R.string.coach_prompt_2, R.string.coach_prompt_3).forEach { prompt ->
        val text = stringResource(prompt)
        val shape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .heightIn(min = LifeDimens.minTouch)
                .clip(shape)
                .border(1.dp, colors.divider, shape)
                .clickable(role = Role.Button) { onPrompt(text) }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, style = LifeType.body, color = colors.text, textAlign = TextAlign.Center)
        }
    }
}

/** Usage today and this week, how full the conversation is, what tidying up does, and the summary so far. */
@Composable
private fun CoachSheet(view: CoachChatView, canTidy: Boolean, onTidy: () -> Unit, onDismiss: () -> Unit) {
    val colors = LifeTheme.colors
    val locale = appLocale()
    SheetFrame(stringResource(R.string.coach_about), onDismiss) {
        SheetHeading(stringResource(R.string.coach_usage_chat))
        UsageLine(stringResource(R.string.models_today), view.usage.today)
        UsageLine(stringResource(R.string.models_week), view.usage.week)
        SheetHeading(stringResource(R.string.coach_usage_morning))
        UsageLine(stringResource(R.string.models_today), view.morningUsage.today)
        UsageLine(stringResource(R.string.models_week), view.morningUsage.week)
        Text(stringResource(R.string.coach_usage_note), style = LifeType.small, color = colors.muted, modifier = Modifier.padding(top = 4.dp))

        SheetHeading(stringResource(R.string.coach_size))
        Text(stringResource(R.string.coach_size_percent, view.fullPercent), style = LifeType.body, color = colors.text)
        Text(stringResource(R.string.coach_size_note), style = LifeType.small, color = colors.muted)
        if (view.compactions > 0) {
            val day = try {
                view.lastCompaction?.at?.let { dayLabel(OffsetDateTime.parse(it).toLocalDate(), locale) }
            } catch (_: Exception) {
                null
            }
            val times = pluralStringResource(R.plurals.coach_tidied_times, view.compactions, view.compactions)
            val facts = view.lastCompaction?.facts ?: 0
            Text(
                text = if (day == null) times else times + " " + pluralStringResource(R.plurals.coach_tidied_last, facts, day, facts),
                style = LifeType.small,
                color = colors.text,
            )
        }
        SecondaryButton(text = stringResource(R.string.coach_tidy), enabled = canTidy, onClick = onTidy, modifier = Modifier.padding(top = 8.dp))

        view.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            SheetHeading(stringResource(R.string.coach_summary))
            MarkdownText(summary)
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text = text,
        style = LifeType.label,
        color = LifeTheme.colors.text,
        modifier = Modifier
            .padding(top = 12.dp, bottom = 2.dp)
            .semantics { heading() },
    )
}
