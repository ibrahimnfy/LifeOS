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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.Project
import dev.lifeos.app.data.ProjectEndpoints
import dev.lifeos.app.data.ProjectList
import dev.lifeos.app.data.ProjectSession
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.CheckMark
import dev.lifeos.app.ui.components.CourseMark
import dev.lifeos.app.ui.components.IconMark
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.LocalLooks
import dev.lifeos.app.ui.components.MarkRow
import dev.lifeos.app.ui.components.Ornament
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.RefreshBox
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.SavedCopyLine
import dev.lifeos.app.ui.components.SectionHeader
import dev.lifeos.app.ui.components.StateHost
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.components.dueLabel
import dev.lifeos.app.ui.components.isRefreshing
import dev.lifeos.app.ui.components.relativeIso
import dev.lifeos.app.ui.components.rememberLoad
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/** Projects under More (#152): newest activity first, each in its own colour and with its ornament. */
@Composable
fun ProjectsScreen(core: LifeOsCore, onBack: () -> Unit, onConnectionClick: () -> Unit, onPair: () -> Unit, onOpenProject: (String) -> Unit) {
    val model = rememberLoad(core, ProjectEndpoints.list) { list: ProjectList -> list.projects.isEmpty() }
    val state by model.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.more_projects), onBack = onBack, onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                (state as? UiState.Content<ProjectList>)?.takeIf { it.fromCache && !it.refreshing }?.let { SavedCopyLine(it.savedAtMillis) }
                StateHost(state, stringResource(R.string.projects_empty), onRetry = model::refresh, onPair = onPair, skeletonRows = 4) { list: ProjectList ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        list.projects.forEach { project -> ProjectCell(project) { onOpenProject(project.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCell(project: Project, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val look = LocalLooks.current.project(project.name.ifBlank { project.label })
    val toned = colors.tone(look.tone)
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(toned.card)
            .border(LifeDimens.hairline, toned.second, shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            Ornament(look.pattern, look.tone, Modifier.matchParentSize())
            CourseMark(look, Modifier.padding(start = 12.dp, top = 8.dp), size = 32.dp, onTint = true)
        }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(project.label, style = LifeType.rowTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            statusText(project.status)?.let { StatusPill(it) }
        }
        (project.summary ?: project.state)?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = LifeType.small, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }
        Text(
            activityLine(project),
            style = LifeType.small,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun activityLine(project: Project): String {
    val tasks = pluralStringResource(R.plurals.projects_open_tasks, project.openTaskCount, project.openTaskCount)
    val last = relativeIso(project.lastActivity)
    return if (last != null) stringResource(R.string.projects_activity_line, tasks, last) else tasks
}

@Composable
private fun statusText(status: String?): String? = when (status) {
    "active" -> stringResource(R.string.project_status_active)
    "paused" -> stringResource(R.string.project_status_paused)
    "done" -> stringResource(R.string.project_status_done)
    else -> null
}

@Composable
private fun StatusPill(text: String) {
    val colors = LifeTheme.colors
    Text(
        text,
        style = LifeType.label,
        color = colors.text,
        modifier = Modifier
            .background(colors.bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/**
 * One project: status, state, open tasks, recent sessions and "Ask about this project", which
 * starts a new chat in the project's folder ([onAsk]); a session opens where it left off.
 */
@Composable
fun ProjectScreen(
    core: LifeOsCore,
    projectId: String,
    onBack: () -> Unit,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onAsk: (Project) -> Unit,
    onOpenSession: (Project, ProjectSession) -> Unit,
) {
    val colors = LifeTheme.colors
    val model = rememberLoad(core, ProjectEndpoints.one(projectId))
    val state by model.state.collectAsStateWithLifecycle()
    val title = (state as? UiState.Content<Project>)?.data?.label ?: stringResource(R.string.more_projects)
    Column(Modifier.fillMaxSize()) {
        TopBar(title = title, onBack = onBack, onConnectionClick = onConnectionClick)
        RefreshBox(refreshing = state.isRefreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LifeDimens.pad)
                    .padding(top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                (state as? UiState.Content<Project>)?.takeIf { it.fromCache && !it.refreshing }?.let { SavedCopyLine(it.savedAtMillis) }
                StateHost(state, stringResource(R.string.projects_empty), onRetry = model::refresh, onPair = onPair, skeletonRows = 4) { project: Project ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProjectHero(project)
                        if (project.canAsk) {
                            PrimaryButton(text = stringResource(R.string.project_ask), onClick = { onAsk(project) })
                        } else {
                            Text(
                                stringResource(if (project.folder.isNullOrBlank()) R.string.project_no_folder else R.string.project_folder_missing),
                                style = LifeType.small,
                                color = colors.muted,
                            )
                        }
                        project.state?.takeIf { it.isNotBlank() && it != project.summary }?.let { text ->
                            LifeCard {
                                SectionHeader(title = stringResource(R.string.project_state))
                                Text(text, style = LifeType.body, color = colors.text, modifier = Modifier.padding(bottom = 12.dp))
                            }
                        }
                        val looks = LocalLooks.current
                        LifeCard {
                            SectionHeader(
                                title = stringResource(R.string.project_tasks),
                                icon = LifeIcons.Tasks,
                                note = project.openTaskCount.takeIf { it > 0 }?.toString(),
                            )
                            if (project.openTasks.isEmpty()) {
                                Text(stringResource(R.string.project_no_tasks), style = LifeType.body, color = colors.muted, modifier = Modifier.padding(bottom = 12.dp))
                            }
                            project.openTasks.forEachIndexed { index, task ->
                                if (index > 0) RowDivider()
                                MarkRow(title = task.title, subtitle = dueLabel(task.due)) { CheckMark(done = false, tone = looks.forTask(task)?.tone) }
                            }
                        }
                        if (project.sessions.isNotEmpty()) {
                            LifeCard {
                                SectionHeader(title = stringResource(R.string.project_sessions), icon = LifeIcons.Sessions)
                                project.sessions.forEachIndexed { index, session ->
                                    if (index > 0) RowDivider()
                                    MarkRow(
                                        title = session.title.ifBlank { stringResource(R.string.project_session_untitled) },
                                        subtitle = relativeIso(session.lastActivity),
                                        modifier = Modifier.clickable(enabled = project.canAsk, role = Role.Button) { onOpenSession(project, session) },
                                    ) { IconMark(LifeIcons.Chat) }
                                }
                            }
                        }
                        project.folder?.let { Text(it, style = LifeType.small, color = colors.muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectHero(project: Project) {
    val colors = LifeTheme.colors
    val look = LocalLooks.current.project(project.name.ifBlank { project.label })
    val toned = colors.tone(look.tone)
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(toned.card)
            .border(LifeDimens.hairline, toned.second, shape),
    ) {
        Ornament(look.pattern, look.tone, Modifier.fillMaxWidth().height(40.dp))
        Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CourseMark(look, size = 44.dp, onTint = true)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    project.summary?.takeIf { it.isNotBlank() } ?: project.label,
                    style = LifeType.rowTitle,
                    color = colors.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(activityLine(project), style = LifeType.small, color = colors.muted)
            }
            statusText(project.status)?.let { StatusPill(it) }
        }
    }
}
