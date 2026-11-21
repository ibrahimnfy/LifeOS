package dev.lifeos.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.lifeos.app.ConnectionState
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.ModelChoices
import dev.lifeos.app.data.RoutingChoice
import dev.lifeos.app.data.RoutingEdit
import dev.lifeos.app.data.UiState
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.JobUsage
import dev.lifeos.app.data.ModelJob
import dev.lifeos.app.data.ModelRouting
import dev.lifeos.app.data.UsageNumbers
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/** The app's name of a job; a job the app does not know keeps the bridge's (English) label. */
@Composable
private fun ModelJob.title(): String = when (job) {
    "chat" -> stringResource(R.string.models_job_chat)
    "coach_plan" -> stringResource(R.string.models_job_coach_plan)
    "coach_chat" -> stringResource(R.string.models_job_coach_chat)
    "consolidation" -> stringResource(R.string.models_job_consolidation)
    "course_resources" -> stringResource(R.string.models_job_course_resources)
    "summaries" -> stringResource(R.string.models_job_summaries)
    "context_map" -> stringResource(R.string.models_job_context_map)
    else -> label.ifBlank { job }
}

@Composable
private fun noModelName(text: String): String = when (text) {
    "saving notes and tasks" -> stringResource(R.string.models_none_saving)
    "course sync" -> stringResource(R.string.models_none_course_sync)
    "notifications" -> stringResource(R.string.models_none_notifications)
    else -> text
}

/**
 * Settings → Models: which model each of the computer's jobs uses (with effort and advisor),
 * where that comes from, and the plan usage of its runs today and this week. Read-only for
 * now: the routing is changed on the computer (the bridge has no endpoint to change it yet).
 */
@Composable
fun ModelsScreen(core: LifeOsCore, onBack: () -> Unit, onConnectionClick: () -> Unit, onPair: () -> Unit) {
    val colors = LifeTheme.colors
    val model = rememberLoad(core, Endpoints.modelRouting) { routing: ModelRouting -> routing.jobs.isEmpty() }
    val state by model.state.collectAsStateWithLifecycle()
    val choicesModel = rememberLoad(core, Endpoints.modelChoices) { false }
    val choices by choicesModel.state.collectAsStateWithLifecycle()
    val connection by core.connection.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ModelJob?>(null) }
    var saving by remember { mutableStateOf(false) }
    val texts = ChangeTexts(
        saved = stringResource(R.string.models_saved),
        offline = stringResource(R.string.models_offline),
        refused = stringResource(R.string.models_refused),
        audit = stringResource(R.string.models_audit_unavailable),
    )
    // A setting, not a capture: sent directly; when the computer cannot be reached nothing changes.
    val send: (kotlinx.serialization.json.JsonObject) -> Unit = { body ->
        if (connection != ConnectionState.CONNECTED) {
            snackbar(texts.offline)
        } else if (!saving) {
            saving = true
            scope.launch {
                try {
                    model.show(core.api.changeRouting(body))
                    snackbar(texts.saved)
                } catch (error: ApiException) {
                    snackbar(
                        when {
                            error.kind != ApiException.Kind.HTTP -> texts.offline
                            error.status == 503 -> texts.audit
                            else -> texts.refused
                        },
                    )
                } finally {
                    saving = false
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.models_title), onBack = onBack, onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.models_read_only), style = LifeType.small, color = colors.muted)
                StateHost(state, stringResource(R.string.models_empty), onRetry = model::refresh, onPair = onPair, skeletonRows = 5) { routing: ModelRouting ->
                    routing.jobs.forEach { job ->
                        JobCard(job, enabled = !saving, onChange = { editing = job }, onReset = { send(RoutingEdit.reset(job.job)) })
                    }
                    if (routing.noModel.isNotEmpty()) {
                        LifeCard {
                            SectionHeader(title = stringResource(R.string.models_no_model))
                            Text(
                                routing.noModel.map { noModelName(it) }.joinToString(" · "),
                                style = LifeType.body,
                                color = colors.text,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                    Text(stringResource(R.string.models_cost_note), style = LifeType.small, color = colors.muted)
                }
            }
        }
    }

    editing?.let { job ->
        val available = (choices as? UiState.Content<ModelChoices>)?.data
        RoutingSheet(
            job = job,
            models = available?.models.orEmpty().ifEmpty { listOfNotNull(job.model, job.defaults?.model).distinct() },
            efforts = available?.efforts.orEmpty().ifEmpty { listOfNotNull(job.effort, job.defaults?.effort).distinct() },
            onDismiss = { editing = null },
            onSave = { body ->
                editing = null
                send(body)
            },
        )
    }
}

private class ChangeTexts(val saved: String, val offline: String, val refused: String, val audit: String)

/**
 * Change one job: its model, effort and advisor. "Default" returns a field to what the job
 * uses underneath (shown in brackets); the advisor can also be switched off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingSheet(job: ModelJob, models: List<String>, efforts: List<String>, onDismiss: () -> Unit, onSave: (kotlinx.serialization.json.JsonObject) -> Unit) {
    val colors = LifeTheme.colors
    val start = remember(job) { RoutingEdit.initial(job) }
    var model by remember(job) { mutableStateOf(start.first) }
    var effort by remember(job) { mutableStateOf(start.second) }
    var advisor by remember(job) { mutableStateOf(start.third) }
    val base = job.defaults
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bg,
        contentColor = colors.text,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = LifeDimens.pad, end = LifeDimens.pad, bottom = LifeDimens.pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(job.title(), style = LifeType.sectionTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            ChoiceRow(stringResource(R.string.models_field_model), base?.model, models, model, offerOff = false) { model = it }
            ChoiceRow(stringResource(R.string.models_field_effort), base?.effort, efforts, effort, offerOff = false) { effort = it }
            ChoiceRow(stringResource(R.string.models_field_advisor), base?.advisor, models, advisor, offerOff = true) { advisor = it }
            Text(stringResource(R.string.models_change_note), style = LifeType.small, color = colors.muted)
            PrimaryButton(text = stringResource(R.string.capture_save), onClick = { onSave(RoutingEdit.body(job.job, model, effort, advisor)) })
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    underneath: String?,
    options: List<String>,
    selected: RoutingChoice,
    offerOff: Boolean,
    onPick: (RoutingChoice) -> Unit,
) {
    val colors = LifeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LifeType.label, color = colors.muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val defaultLabel = underneath?.let { stringResource(R.string.models_default_is, it) } ?: stringResource(R.string.models_default)
            Chip(label = defaultLabel, selected = selected == RoutingChoice.Default) { onPick(RoutingChoice.Default) }
            if (offerOff) Chip(label = stringResource(R.string.models_advisor_off), selected = selected == RoutingChoice.Off) { onPick(RoutingChoice.Off) }
            options.forEach { option ->
                Chip(label = option, selected = selected == RoutingChoice.Value(option)) { onPick(RoutingChoice.Value(option)) }
            }
        }
    }
}

@Composable
private fun JobCard(job: ModelJob, enabled: Boolean, onChange: () -> Unit, onReset: () -> Unit) {
    val colors = LifeTheme.colors
    LifeCard {
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(job.title(), style = LifeType.rowTitle, color = colors.text)
            val setup = listOfNotNull(
                job.model ?: stringResource(R.string.models_default_model),
                job.effort?.let { stringResource(R.string.models_effort, it) },
                job.advisor?.let { stringResource(R.string.models_advisor, it) },
            ).joinToString(" · ")
            Text(setup, style = LifeType.body, color = colors.text)
            Text(
                stringResource(
                    when {
                        job.source == "app" -> R.string.models_source_app
                        job.perSession -> R.string.models_source_session
                        job.source == "routing" -> R.string.models_source_routing
                        job.source == "background_model" -> R.string.models_source_background
                        else -> R.string.models_source_default
                    },
                ),
                style = LifeType.small,
                color = colors.muted,
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(text = stringResource(R.string.models_change), enabled = enabled, onClick = onChange)
                if (job.source == "app") {
                    val default = job.defaults
                    PillButton(
                        text = stringResource(R.string.models_reset),
                        enabled = enabled,
                        onClick = onReset,
                        onClickLabel = default?.model?.let { stringResource(R.string.models_reset_to, it) },
                    )
                }
            }
            RowDivider(startIndent = 0.dp, modifier = Modifier.padding(vertical = 6.dp))
            UsageRow(stringResource(R.string.models_today), job.usage.today)
            UsageRow(stringResource(R.string.models_week), job.usage.week)
        }
    }
}

@Composable
private fun UsageRow(period: String, usage: JobUsage) {
    val colors = LifeTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(period, style = LifeType.small, color = colors.muted, modifier = Modifier.width(96.dp))
        Text(
            if (usage.runs == 0) {
                stringResource(R.string.models_no_runs)
            } else {
                listOf(
                    pluralStringResource(R.plurals.models_runs, usage.runs, usage.runs),
                    stringResource(R.string.models_tokens, UsageNumbers.compact(usage.tokens)),
                    UsageNumbers.dollars(usage.costUsd),
                ).joinToString(" · ")
            },
            style = LifeType.small,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
    }
}
