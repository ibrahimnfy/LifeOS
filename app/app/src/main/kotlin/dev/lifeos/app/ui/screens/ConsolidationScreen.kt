package dev.lifeos.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.ConsolidationEndpoints
import dev.lifeos.app.data.ConsolidationView
import dev.lifeos.app.data.MemoryOperation
import dev.lifeos.app.data.MemoryOperations
import dev.lifeos.app.data.Tone
import dev.lifeos.app.data.UiState
import dev.lifeos.app.data.approveMemory
import dev.lifeos.app.data.rejectMemory
import dev.lifeos.app.data.runConsolidation
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.PillButton
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.RefreshBox
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.components.SectionHeader
import dev.lifeos.app.ui.components.StateHost
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.components.Watermark
import dev.lifeos.app.ui.components.isRefreshing
import dev.lifeos.app.ui.components.relativeIso
import dev.lifeos.app.ui.components.rememberLoad
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch

/** The memory's tone on Today and on its screen. */
private val MEMORY_TONE = Tone.SKY

/**
 * Today's card while a consolidation proposal waits (#151): how many changes, and the way to
 * review them. Nothing is written to the memory until the owner approves.
 */
@Composable
fun ConsolidationCard(core: LifeOsCore, onOpen: () -> Unit) {
    val model = rememberLoad(core, ConsolidationEndpoints.view)
    val state by model.state.collectAsStateWithLifecycle()
    val view = (state as? UiState.Content<ConsolidationView>)?.data
    val pending = MemoryOperations.pending(view).size
    if (pending == 0) return
    val colors = LifeTheme.colors
    LifeCard(watermark = Watermark(LifeIcons.Notes, MEMORY_TONE)) {
        SectionHeader(title = stringResource(R.string.memory_review_title), icon = LifeIcons.Notes, tone = MEMORY_TONE)
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(pluralStringResource(R.plurals.memory_review_waiting, pending, pending), style = LifeType.body, color = colors.text)
            PrimaryButton(text = stringResource(R.string.memory_review_open), onClick = onOpen)
        }
    }
}

/** A request on the screen, and what came of it. */
private enum class Outcome { NONE, BUSY, PARTLY_FAILED, CHANGED, GONE, FAILED, RUN_STARTED, RUN_WAITING, RUN_TOO_SOON }

/** The proposal's operations, approved or rejected one by one or all at once (#151). */
@Composable
fun ConsolidationScreen(core: LifeOsCore, onBack: () -> Unit, onConnectionClick: () -> Unit, onPair: () -> Unit) {
    val colors = LifeTheme.colors
    val scope = rememberCoroutineScope()
    val model = rememberLoad(core, ConsolidationEndpoints.view)
    val state by model.state.collectAsStateWithLifecycle()
    var outcome by remember { mutableStateOf(Outcome.NONE) }
    val busy = outcome == Outcome.BUSY

    fun decide(approve: Boolean, ids: List<String>?) {
        outcome = Outcome.BUSY
        scope.launch {
            outcome = try {
                val view = if (approve) core.api.approveMemory(ids) else core.api.rejectMemory(ids)
                model.show(view)
                // Today's card and the lists follow (an approval rewrote the memory).
                core.memoryChanged()
                if (view.failed.isNotEmpty()) Outcome.PARTLY_FAILED else Outcome.NONE
            } catch (error: ApiException) {
                model.refresh()
                when {
                    error.kind == ApiException.Kind.HTTP && error.status == 409 -> Outcome.CHANGED
                    error.kind == ApiException.Kind.HTTP && error.status == 404 -> Outcome.GONE
                    else -> Outcome.FAILED
                }
            }
        }
    }

    fun runNow() {
        outcome = Outcome.BUSY
        scope.launch {
            outcome = try {
                core.api.runConsolidation()
                Outcome.RUN_STARTED
            } catch (error: ApiException) {
                when {
                    error.kind == ApiException.Kind.HTTP && error.status == 409 -> Outcome.RUN_WAITING
                    error.kind == ApiException.Kind.HTTP && error.status == 429 -> Outcome.RUN_TOO_SOON
                    else -> Outcome.FAILED
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.memory_review_screen), onBack = onBack, onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.memory_review_explain), style = LifeType.small, color = colors.muted)
                val note = when (outcome) {
                    Outcome.PARTLY_FAILED -> R.string.memory_review_partly_failed
                    Outcome.CHANGED -> R.string.memory_review_changed
                    Outcome.GONE -> R.string.memory_review_gone
                    Outcome.FAILED -> R.string.memory_review_failed
                    Outcome.RUN_STARTED -> R.string.memory_run_started
                    Outcome.RUN_WAITING -> R.string.memory_run_waiting
                    Outcome.RUN_TOO_SOON -> R.string.memory_run_too_soon
                    Outcome.NONE, Outcome.BUSY -> null
                }
                if (note != null) {
                    Text(stringResource(note), style = LifeType.body, color = colors.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                StateHost(state, stringResource(R.string.memory_review_none), onRetry = model::refresh, onPair = onPair, skeletonRows = 3) { view: ConsolidationView ->
                    val operations = view.proposal?.operations.orEmpty()
                    val pending = operations.count { it.pending }
                    if (pending > 0) {
                        view.proposal?.createdAt?.let { relativeIso(it) }?.let { at ->
                            Text(stringResource(R.string.memory_review_prepared, at), style = LifeType.small, color = colors.muted)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton(text = stringResource(R.string.memory_reject_all), enabled = !busy, onClick = { decide(false, null) }, modifier = Modifier.weight(1f))
                            PrimaryButton(text = stringResource(R.string.memory_approve_all, pending), enabled = !busy, onClick = { decide(true, null) }, modifier = Modifier.weight(1f))
                        }
                    }
                    if (operations.isNotEmpty()) {
                        LifeCard {
                            operations.forEachIndexed { index, operation ->
                                if (index > 0) RowDivider()
                                OperationRow(
                                    operation,
                                    enabled = !busy,
                                    onApprove = { decide(true, listOf(operation.id)) },
                                    onReject = { decide(false, listOf(operation.id)) },
                                )
                            }
                        }
                    }
                    if (pending == 0) {
                        Text(stringResource(R.string.memory_review_none), style = LifeType.body, color = colors.text)
                        if (view.running) {
                            Text(stringResource(R.string.memory_run_running), style = LifeType.small, color = colors.muted)
                        } else {
                            view.lastRun?.let { relativeIso(it) }?.let { at ->
                                Text(stringResource(R.string.memory_last_run, at), style = LifeType.small, color = colors.muted)
                            }
                            SecondaryButton(text = stringResource(R.string.memory_run_now), enabled = !busy, onClick = ::runNow)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationRow(operation: MemoryOperation, enabled: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
    val colors = LifeTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(
                when (operation.op) {
                    "file_capture" -> R.string.memory_op_file_capture
                    "merge_facts" -> R.string.memory_op_merge_facts
                    "add_fact" -> R.string.memory_op_add_fact
                    else -> R.string.memory_op_other
                },
            ),
            style = LifeType.rowTitle,
            color = colors.text,
        )
        if (operation.file.isNotBlank()) Text(operation.file, style = LifeType.small, color = colors.muted)
        when (operation.op) {
            "file_capture" -> {
                operation.captureText?.let { Text("“$it”", style = LifeType.body, color = colors.text) }
                operation.captureAt?.let { Text(stringResource(R.string.memory_captured_at, it), style = LifeType.small, color = colors.muted) }
            }
            "merge_facts" -> {
                operation.keep?.let { Labelled(stringResource(R.string.memory_keep), it) }
                operation.drop?.let { Labelled(stringResource(R.string.memory_drop), it) }
            }
            else -> operation.text?.let { Text(it, style = LifeType.body, color = colors.text) }
        }
        operation.why?.takeIf { it.isNotBlank() }?.let { Labelled(stringResource(R.string.coach_why), it, muted = true) }
        if (operation.pending) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                PillButton(text = stringResource(R.string.memory_reject), enabled = enabled, onClick = onReject)
                PillButton(text = stringResource(R.string.memory_approve), icon = LifeIcons.Check, enabled = enabled, onClick = onApprove)
            }
        } else {
            val status = when (operation.status) {
                MemoryOperations.APPLIED -> stringResource(R.string.memory_status_applied)
                MemoryOperations.REJECTED -> stringResource(R.string.memory_status_rejected)
                else -> operation.reason?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.memory_status_failed_because, it) }
                    ?: stringResource(R.string.memory_status_failed)
            }
            Text(status, style = LifeType.label, color = if (operation.status == MemoryOperations.FAILED) colors.badge else colors.muted)
        }
    }
}

@Composable
private fun Labelled(label: String, text: String, muted: Boolean = false) {
    val colors = LifeTheme.colors
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.text)) { append(label) }
            append("  ")
            append(text)
        },
        style = if (muted) LifeType.small else LifeType.body,
        color = if (muted) colors.muted else colors.text,
    )
}
