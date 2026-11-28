package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * "Continue on the computer" (#201): the line that resumes this conversation in a terminal,
 * to read, select or copy. Claude Code's own list of sessions leaves conversations started
 * from the phone out; by id they resume like any other.
 */
@Composable
internal fun ComputerResumeSheet(command: String, onDismiss: () -> Unit) {
    val colors = LifeTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    SheetFrame(stringResource(R.string.session_computer), onDismiss) {
        Text(stringResource(R.string.session_computer_note), style = LifeType.small, color = colors.muted)
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = command,
                style = LifeType.small.copy(fontFamily = FontFamily.Monospace),
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface2)
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = stringResource(if (copied) R.string.session_computer_copied else R.string.session_computer_copy),
            onClick = {
                clipboard.setText(AnnotatedString(command))
                copied = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
