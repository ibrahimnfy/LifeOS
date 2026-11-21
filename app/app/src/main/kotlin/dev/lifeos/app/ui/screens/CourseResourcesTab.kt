package dev.lifeos.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.CourseResource
import dev.lifeos.app.data.CourseResourceView
import dev.lifeos.app.data.CourseResources
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.RowDivider
import dev.lifeos.app.ui.components.SectionHeader
import dev.lifeos.app.ui.components.StateHost
import dev.lifeos.app.ui.components.isRefreshing
import dev.lifeos.app.ui.components.rememberLoad
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType

/**
 * The resources the computer found from the course's syllabus (#128, #160): read only. Each
 * says whether it was downloaded into the course's folder on the computer or why it is only
 * listed. Titles and addresses are outside text, shown as plain text; an HTTPS address opens
 * in the browser.
 */
@Composable
internal fun ResourcesTab(core: LifeOsCore, courseId: Long, onPair: () -> Unit) {
    val colors = LifeTheme.colors
    val model = rememberLoad(core, CourseResources.endpoint(courseId)) { view: CourseResourceView -> view.items.isEmpty() && view.foldersNamed }
    val state by model.state.collectAsStateWithLifecycle()
    TabBody(state.isRefreshing, model::refresh) {
        StateHost(state, stringResource(R.string.course_resources_empty), onRetry = model::refresh, onPair = onPair) { view: CourseResourceView ->
            Text(stringResource(R.string.course_resources_note), style = LifeType.small, color = colors.muted)
            // Until the owner has named their resource folders on the computer, nothing is downloaded.
            if (!view.foldersNamed) Text(stringResource(R.string.course_resources_folders_not_named), style = LifeType.label, color = colors.warn)
            if (view.items.isNotEmpty()) {
                LifeCard {
                    SectionHeader(title = stringResource(R.string.course_resources))
                    view.items.forEachIndexed { index, item ->
                        if (index > 0) RowDivider(startIndent = 0.dp)
                        ResourceRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceRow(item: CourseResource) {
    val colors = LifeTheme.colors
    val uriHandler = LocalUriHandler.current
    val opens = item.url.startsWith("https://")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = opens, onClickLabel = stringResource(R.string.course_open_link), role = Role.Button) {
                try {
                    uriHandler.openUri(item.url)
                } catch (_: Exception) {
                }
            }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(item.title.ifBlank { item.url }, style = LifeType.rowTitle, color = if (opens) colors.link else colors.text)
        Text(stringResource(resourceLineRes(item)), style = LifeType.small, color = colors.muted)
    }
}

/** What the row says about [item]: downloaded, or why it is only listed. */
private fun resourceLineRes(item: CourseResource): Int = when (CourseResources.reasonOf(item)) {
    null -> R.string.course_resource_downloaded
    CourseResources.Reason.PAID -> R.string.course_resource_paid
    CourseResources.Reason.OTHER -> R.string.course_resource_other
    CourseResources.Reason.FOLDERS_NOT_NAMED -> R.string.course_resource_folders_not_named
    CourseResources.Reason.ALREADY_PRESENT -> R.string.course_resource_already_present
    CourseResources.Reason.NOT_HTTPS -> R.string.course_resource_not_https
    CourseResources.Reason.NOT_PUBLIC -> R.string.course_resource_not_public
    CourseResources.Reason.NOT_A_DOCUMENT -> R.string.course_resource_not_a_document
    CourseResources.Reason.TOO_LARGE -> R.string.course_resource_too_large
    CourseResources.Reason.FAILED -> R.string.course_resource_failed
    CourseResources.Reason.LATER -> R.string.course_resource_later
    CourseResources.Reason.UNRECORDED -> R.string.course_resource_unrecorded
    CourseResources.Reason.UNKNOWN -> R.string.course_resource_listed
}
