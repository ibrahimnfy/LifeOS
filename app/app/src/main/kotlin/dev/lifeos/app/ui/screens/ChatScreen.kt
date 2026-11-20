package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/** Chat in its empty state: example prompts and the composer. Nothing is sent yet. */
@Composable
fun ChatScreen(onConnectionClick: () -> Unit) {
    val colors = LifeTheme.colors
    var draft by rememberSaveable { mutableStateOf("") }
    val prompts = listOf(R.string.chat_prompt_1, R.string.chat_prompt_2, R.string.chat_prompt_3)

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        TopBar(title = stringResource(R.string.nav_chat), onConnectionClick = onConnectionClick)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = stringResource(R.string.chat_empty_title), style = LifeType.featured, color = colors.text)
            Text(
                text = stringResource(R.string.chat_empty_text),
                style = LifeType.small,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            prompts.forEach { prompt ->
                val text = stringResource(prompt)
                val shape = RoundedCornerShape(24.dp)
                Box(
                    modifier = Modifier
                        .heightIn(min = LifeDimens.minTouch)
                        .clip(shape)
                        .border(1.dp, colors.divider, shape)
                        .clickable(role = Role.Button) { draft = text }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = text, style = LifeType.body, color = colors.text, textAlign = TextAlign.Center)
                }
            }
        }
        Composer(draft = draft, onDraftChange = { draft = it })
    }
}

@Composable
private fun Composer(draft: String, onDraftChange: (String) -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(24.dp)
    val sendLabel = stringResource(R.string.chat_send)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LifeDimens.pad, vertical = 8.dp)
            .heightIn(min = LifeDimens.minTouch)
            .border(1.dp, colors.divider, shape)
            .padding(start = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        ) {
            if (draft.isEmpty()) {
                Text(text = stringResource(R.string.chat_composer_hint), style = LifeType.body, color = colors.muted)
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = LifeType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.primary),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Sending arrives with the chat issue: the button is shown, but disabled.
        Box(
            modifier = Modifier
                .size(LifeDimens.minTouch)
                .semantics {
                    role = Role.Button
                    contentDescription = sendLabel
                    disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .alpha(0.45f)
                    .background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LifeIcons.Send, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
