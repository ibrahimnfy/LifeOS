package dev.lifeos.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
                    routing.jobs.forEach { job -> JobCard(job) }
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
}

@Composable
private fun JobCard(job: ModelJob) {
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
                        job.perSession -> R.string.models_source_session
                        job.source == "routing" -> R.string.models_source_routing
                        job.source == "background_model" -> R.string.models_source_background
                        else -> R.string.models_source_default
                    },
                ),
                style = LifeType.small,
                color = colors.muted,
            )
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
