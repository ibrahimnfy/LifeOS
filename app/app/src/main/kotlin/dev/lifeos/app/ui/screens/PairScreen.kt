package dev.lifeos.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.PairProblem
import dev.lifeos.app.PairState
import dev.lifeos.app.R
import dev.lifeos.app.lock.Authenticator
import dev.lifeos.app.pairing.Pairing
import dev.lifeos.app.pairing.PairingText
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.QrScanner
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/** Asks the owner to confirm with fingerprint or screen lock: (title, subtitle, onSuccess, onFailure). */
typealias Authenticate = (String, String, () -> Unit, () -> Unit) -> Unit

/**
 * Pairing (docs/security.md, section 4): explanation → camera → confirmation that it is the
 * owner → the six-digit comparison code → paired. The steps after the scan live in
 * [LifeOsCore], so they continue when this screen is left for a moment.
 */
@Composable
fun PairScreen(core: LifeOsCore, authenticate: Authenticate, onBack: () -> Unit) {
    val context = LocalContext.current
    val progress by core.pairProgress.collectAsStateWithLifecycle()
    var scanning by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<Int?>(null) }
    val defaultLabel = stringResource(R.string.pair_label_default)
    val confirmTitle = stringResource(R.string.pair_confirm_title)
    val confirmSubtitle = stringResource(R.string.pair_confirm_subtitle)

    val leave = {
        core.resetPairing()
        onBack()
    }
    BackHandler(onBack = leave)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
        notice = if (granted) null else R.string.pair_camera_denied
    }

    fun startScan() {
        notice = null
        when {
            !Authenticator.available(context) -> notice = R.string.pair_failed_no_lock
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                scanning = true
            else -> permission.launch(Manifest.permission.CAMERA)
        }
    }

    fun onScanned(text: String) {
        if (!scanning) return
        val offer = PairingText.parse(text)
        if (offer == null) {
            notice = R.string.pair_not_a_code
            return
        }
        scanning = false
        notice = null
        authenticate(
            confirmTitle,
            confirmSubtitle,
            { core.startPairing(offer, label?.takeIf { it.isNotBlank() } ?: defaultLabel) },
            {
                offer.secret.fill(0)
                notice = R.string.pair_failed_confirmation
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.pair_title), onBack = leave)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = LifeDimens.pad)
                .padding(top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val state = progress) {
                PairState.Idle -> if (scanning) {
                    ScanStep(notice = notice, onText = ::onScanned, onCancel = { scanning = false })
                } else {
                    IntroStep(
                        label = label ?: defaultLabel,
                        onLabelChange = { label = it.take(Pairing.MAX_LABEL) },
                        notice = notice,
                        onScan = ::startScan,
                    )
                }
                PairState.Working -> WorkingStep(onCancel = leave)
                is PairState.Compare -> CompareStep(code = state.code, onCancel = leave)
                is PairState.Done -> DoneStep(name = state.name, onDone = leave)
                is PairState.Failed -> FailedStep(reason = state.reason, onRetry = { core.resetPairing() })
            }
        }
    }
}

@Composable
private fun IntroStep(label: String, onLabelChange: (String) -> Unit, notice: Int?, onScan: () -> Unit) {
    val colors = LifeTheme.colors
    Text(stringResource(R.string.pair_intro), style = LifeType.body, color = colors.text)
    LifeCard {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Step(1, stringResource(R.string.pair_step_1))
            Text(
                text = stringResource(R.string.pair_command),
                style = LifeType.body.copy(fontFamily = FontFamily.Monospace),
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Step(2, stringResource(R.string.pair_step_2))
            Step(3, stringResource(R.string.pair_step_3))
        }
    }
    OutlinedTextField(
        value = label,
        onValueChange = onLabelChange,
        label = { Text(stringResource(R.string.pair_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    if (notice != null) Notice(stringResource(notice))
    PrimaryButton(text = stringResource(R.string.pair_scan), onClick = onScan)
    Text(stringResource(R.string.pair_camera_why), style = LifeType.small, color = colors.muted)
}

@Composable
private fun Step(number: Int, text: String) {
    Text(
        text = "%d.  %s".format(number, text),
        style = LifeType.body,
        color = LifeTheme.colors.text,
    )
}

@Composable
private fun ScanStep(notice: Int?, onText: (String) -> Unit, onCancel: () -> Unit) {
    val colors = LifeTheme.colors
    val description = stringResource(R.string.pair_scan_region)
    Text(stringResource(R.string.pair_scanning), style = LifeType.body, color = colors.text)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .border(LifeDimens.hairline, colors.divider, RoundedCornerShape(20.dp))
            .background(colors.surface2)
            .semantics { contentDescription = description },
    ) {
        QrScanner(onText = onText, modifier = Modifier.fillMaxSize())
    }
    if (notice != null) Notice(stringResource(notice))
    SecondaryButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
}

@Composable
private fun WorkingStep(onCancel: () -> Unit) {
    val colors = LifeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(color = colors.primary)
        Text(
            stringResource(R.string.pair_working),
            style = LifeType.body,
            color = colors.text,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    SecondaryButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
}

@Composable
private fun CompareStep(code: String, onCancel: () -> Unit) {
    val colors = LifeTheme.colors
    LifeCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.pair_code_label), style = LifeType.label, color = colors.muted)
            Text(
                text = code,
                style = TextStyle(
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.04.em,
                ),
                color = colors.text,
                modifier = Modifier.semantics {
                    // Read digit by digit, not as a number.
                    contentDescription = code.toList().joinToString(" ")
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }
    }
    Text(stringResource(R.string.pair_code_text), style = LifeType.body, color = colors.text)
    Text(stringResource(R.string.pair_code_mismatch), style = LifeType.small, color = colors.muted)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(color = colors.primary)
        Text(stringResource(R.string.pair_code_waiting), style = LifeType.small, color = colors.muted)
    }
    SecondaryButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
}

@Composable
private fun DoneStep(name: String, onDone: () -> Unit) {
    val colors = LifeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.pair_done),
            style = LifeType.screenTitle,
            color = colors.text,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        Text(
            stringResource(R.string.pair_done_text, name),
            style = LifeType.body,
            color = colors.muted,
            textAlign = TextAlign.Center,
        )
    }
    PrimaryButton(text = stringResource(R.string.pair_done_button), onClick = onDone)
}

@Composable
private fun FailedStep(reason: PairProblem, onRetry: () -> Unit) {
    Notice(
        stringResource(
            when (reason) {
                PairProblem.NEEDS_CONFIRMATION -> R.string.pair_failed_confirmation
                PairProblem.NO_SCREEN_LOCK -> R.string.pair_failed_no_lock
                PairProblem.UNREACHABLE -> R.string.pair_failed_unreachable
                PairProblem.REFUSED -> R.string.pair_failed_refused
                PairProblem.TIMEOUT -> R.string.pair_failed_timeout
                PairProblem.STORAGE -> R.string.pair_failed_storage
            },
        ),
    )
    PrimaryButton(text = stringResource(R.string.pair_retry), onClick = onRetry)
}

/** A message the owner must not miss: announced by screen readers when it appears. */
@Composable
private fun Notice(text: String) {
    val colors = LifeTheme.colors
    Text(
        text = text,
        style = LifeType.body,
        color = colors.text,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
