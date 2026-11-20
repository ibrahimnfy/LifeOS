package dev.lifeos.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * Lies over everything while a paired app is locked: opaque, and no touch or back press
 * reaches what is underneath.
 */
@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit, onBack: () -> Unit) {
    val colors = LifeTheme.colors
    // Ask at once; the button is for a second try.
    LaunchedEffect(Unit) { onUnlock() }
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.lock_title),
            style = LifeType.screenTitle,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = message ?: stringResource(R.string.lock_text),
            style = LifeType.body,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        PrimaryButton(
            text = stringResource(R.string.lock_unlock),
            onClick = onUnlock,
            modifier = Modifier
                .padding(top = 12.dp)
                .widthIn(max = 320.dp),
        )
    }
}

/** First run: one short step that leads to pairing; it can be skipped. */
@Composable
fun WelcomeScreen(onPair: () -> Unit, onLater: () -> Unit) {
    val colors = LifeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.welcome_title),
            style = LifeType.screenTitle,
            color = colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.welcome_text),
            style = LifeType.body,
            color = colors.muted,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(
            text = stringResource(R.string.settings_pair),
            onClick = onPair,
            modifier = Modifier
                .padding(top = 12.dp)
                .widthIn(max = 320.dp),
        )
        SecondaryButton(
            text = stringResource(R.string.welcome_later),
            onClick = onLater,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}
