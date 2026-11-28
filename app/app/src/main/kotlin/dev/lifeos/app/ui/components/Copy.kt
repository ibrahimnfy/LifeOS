package dev.lifeos.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.delay

/** How long a copy control shows its check mark after a copy. */
private const val COPIED_MILLIS = 2000L

/**
 * The height a copy control takes in the layout. It is 48 dp wide; in height the free space
 * around it (which Compose adds to a small target by itself) completes the 48 dp to touch.
 */
internal val CopyHeight = 40.dp

/**
 * Puts [text] on the phone's clipboard, as plain text. False when the system refused (a text
 * too large to hand over, for example): then nothing is shown as copied.
 */
private fun copyToClipboard(context: Context, text: String): Boolean = try {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    clipboard != null
} catch (_: RuntimeException) {
    false
}

/**
 * The state of one copy control: whether it has just copied, and the action that copies. For
 * two seconds after a copy the control shows a check mark. The app shows no message of its
 * own, because Android 13 and later show the system's confirmation: never two messages.
 */
@Composable
private fun rememberCopy(): Pair<Boolean, (String) -> Unit> {
    val context = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(taps) {
        if (taps > 0) {
            delay(COPIED_MILLIS)
            copied = false
        }
    }
    return copied to { text ->
        if (copyToClipboard(context, text)) {
            copied = true
            taps++
        }
    }
}

/** What a screen reader hears: the action's name, and "Copied" right after a copy. */
private fun Modifier.copySemantics(label: String, copied: Boolean, done: String): Modifier = semantics {
    contentDescription = label
    if (copied) {
        stateDescription = done
        liveRegion = LiveRegionMode.Polite
    }
}

/**
 * A quiet icon that copies [text]: under a message of a conversation (#208). [label] names it
 * for screen readers ("Copy message").
 */
@Composable
fun CopyIcon(text: String, label: String, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    val (copied, copy) = rememberCopy()
    Box(
        modifier = modifier
            .size(width = LifeDimens.minTouch, height = CopyHeight)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = label, role = Role.Button) { copy(text) }
            .copySemantics(label, copied, stringResource(R.string.chat_copied)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (copied) LifeIcons.Check else LifeIcons.Copy,
            contentDescription = null,
            tint = if (copied) colors.ok else colors.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The "Copy" in the header of a code block: the icon with the word; "Copied" right after a copy. */
@Composable
fun CopyWord(text: String, label: String, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    val (copied, copy) = rememberCopy()
    val done = stringResource(R.string.chat_copied)
    Row(
        modifier = modifier
            .heightIn(min = CopyHeight)
            .widthIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = label, role = Role.Button) { copy(text) }
            .copySemantics(label, copied, done)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (copied) LifeIcons.Check else LifeIcons.Copy,
            contentDescription = null,
            tint = if (copied) colors.ok else colors.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(text = if (copied) done else stringResource(R.string.chat_copy), style = LifeType.small, color = colors.muted)
    }
}
