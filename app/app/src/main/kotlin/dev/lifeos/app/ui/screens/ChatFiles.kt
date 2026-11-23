package dev.lifeos.app.ui.screens

import android.os.SystemClock
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.OfferedFile
import dev.lifeos.app.data.DownloadFailure
import dev.lifeos.app.data.DownloadRules
import dev.lifeos.app.data.DownloadState
import dev.lifeos.app.data.OpenResult
import dev.lifeos.app.ui.components.LocalSnackbar
import dev.lifeos.app.ui.components.PillButton
import dev.lifeos.app.ui.components.TopBarAction
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.delay

/**
 * A file the assistant offered: its name, kind and size, and "Download". Nothing is
 * transferred before the tap; the file then goes straight into the phone's Downloads folder
 * (size and hash checked, an incomplete file removed) and the app keeps no copy. "Open"
 * hands the saved file to the phone's app for its kind, as for course files.
 */
@Composable
internal fun FileCard(core: LifeOsCore, file: OfferedFile) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val offer = file.offer
    val key = offer?.let(core.downloads::offerKey)
    val states by core.downloads.states.collectAsStateWithLifecycle()
    val state = key?.let { states[it] }
    // An offer runs out on the computer; the card says so instead of offering a download that fails.
    val now by produceState(SystemClock.elapsedRealtime(), key) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(30_000)
        }
    }
    val usable = offer != null && (file.open(now) || state is DownloadState.Running || state is DownloadState.Saved)
    // Before Android 10 the owner chooses where the file goes.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(offer?.type ?: "application/octet-stream")) { uri ->
        if (uri != null && offer != null) core.downloads.startOffer(offer, uri)
    }
    val snackbar = LocalSnackbar.current
    val noApp = stringResource(R.string.download_open_no_app)
    val gone = stringResource(R.string.download_open_gone)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(LifeDimens.hairline, colors.divider, shape)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(colors.surface2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(LifeIcons.File, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(file.name, style = LifeType.rowTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(
                    file.name.substringAfterLast('.', "").takeIf { it.length in 1..5 }?.uppercase(),
                    offer?.size?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                ).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = LifeType.small, color = colors.muted, maxLines = 1)
            }
            when {
                offer == null || key == null || !usable -> Box(Modifier.size(LifeDimens.minTouch))
                state is DownloadState.Running -> TopBarAction(LifeIcons.Close, stringResource(R.string.download_cancel, file.name)) { core.downloads.cancel(key) }
                state is DownloadState.Saved -> PillButton(
                    text = stringResource(R.string.download_open),
                    onClick = {
                        when (core.downloads.open(context, key, file.name)) {
                            OpenResult.NO_APP -> snackbar(noApp)
                            OpenResult.GONE -> snackbar(gone)
                            OpenResult.OPENED -> Unit
                        }
                    },
                    onClickLabel = stringResource(R.string.download_open_label, file.name),
                    modifier = Modifier.padding(end = 8.dp),
                )
                else -> TopBarAction(LifeIcons.Download, stringResource(R.string.download_start, file.name)) {
                    if (core.downloads.needsPicker) { core.openingSystemScreen(); picker.launch(DownloadRules.safeName(file.name)) } else core.downloads.startOffer(offer)
                }
            }
        }
        val line = when {
            file.refused != null -> stringResource(
                when (file.refused) {
                    OfferedFile.Refusal.TOO_LARGE -> R.string.chat_file_refused_large
                    OfferedFile.Refusal.OUTSIDE -> R.string.chat_file_refused_outside
                    OfferedFile.Refusal.HIDDEN -> R.string.chat_file_refused_hidden
                    OfferedFile.Refusal.TOO_MANY -> R.string.chat_file_refused_many
                    OfferedFile.Refusal.OTHER -> R.string.chat_file_refused_other
                },
            )
            offer == null -> stringResource(R.string.chat_file_past)
            state is DownloadState.Running ->
                if (state.total > 0) {
                    stringResource(R.string.download_progress, (state.bytes * 100 / state.total).toInt().coerceIn(0, 100))
                } else {
                    stringResource(R.string.download_running)
                }
            state is DownloadState.Saved -> stringResource(R.string.download_saved)
            state is DownloadState.Failed -> stringResource(
                when (state.reason) {
                    // For an offer there is only the computer's copy: "no copy" means the offer is over.
                    DownloadFailure.Reason.NO_COPY, DownloadFailure.Reason.DIRECT, DownloadFailure.Reason.SIGN_IN -> R.string.chat_file_failed_gone
                    DownloadFailure.Reason.DAMAGED -> R.string.download_failed_damaged
                    DownloadFailure.Reason.UNREACHABLE -> R.string.download_failed_unreachable
                    DownloadFailure.Reason.STORAGE -> R.string.download_failed_storage
                },
            )
            !usable -> stringResource(R.string.chat_file_expired)
            else -> null
        }
        if (line != null) {
            Text(
                text = line,
                style = LifeType.small,
                color = if (state is DownloadState.Failed || file.refused != null) colors.text else colors.muted,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
