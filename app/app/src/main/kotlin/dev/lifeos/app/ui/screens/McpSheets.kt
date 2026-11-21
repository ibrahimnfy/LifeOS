package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.Interaction
import dev.lifeos.app.data.InteractionData
import dev.lifeos.app.data.McpApprovals
import dev.lifeos.app.data.McpServer
import dev.lifeos.app.data.SessionSummary
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.components.SkeletonCard
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch

// A session folder's MCP servers (#153): what the folder declares, what is approved, and the
// approval of one server through its card. An approved server runs outside the sandbox, and
// every place that offers or shows an approval says so first.

/** The warning that goes before everything else about MCP servers: amber frame, the app's own words. */
@Composable
internal fun McpWarning() {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = stringResource(R.string.mcp_warning),
        style = LifeType.label,
        color = colors.text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(2.dp, colors.warn, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Exactly what would start: the program with its arguments, or the address, and the names
 * (never the values) of its environment variables and headers. Shown whole, as plain text:
 * the folder's file is someone's text, not the app's.
 */
@Composable
internal fun McpDefinition(server: McpServer) {
    if (server.commandLine.isNotBlank()) Exact(stringResource(R.string.mcp_starts), server.commandLine)
    server.url?.takeIf { it.isNotBlank() }?.let { Exact(stringResource(R.string.mcp_connects), it) }
    if (server.envNames.isNotEmpty()) Exact(stringResource(R.string.mcp_env), server.envNames.joinToString(", "))
    if (server.headerNames.isNotEmpty()) Exact(stringResource(R.string.mcp_headers), server.headerNames.joinToString(", "))
}

/**
 * What an approval card asks: the server by name, the warning, the definition, and where the
 * approval holds: the folder for a folder's server, every folder for a personal one.
 */
@Composable
private fun McpQuestion(server: McpServer, folder: String) {
    val colors = LifeTheme.colors
    CardLabel(stringResource(R.string.mcp_card_label))
    Text(
        text = stringResource(if (server.personal) R.string.mcp_card_title_personal else R.string.mcp_card_title, server.name),
        style = LifeType.rowTitle,
        color = colors.text,
    )
    McpWarning()
    McpDefinition(server)
    if (server.personal) {
        Text(text = stringResource(R.string.mcp_card_every_folder), style = LifeType.label, color = colors.warn)
    } else if (folder.isNotBlank()) {
        Exact(stringResource(R.string.mcp_card_folder), folder)
    }
}

@Composable
private fun McpButtons(onApprove: (Boolean) -> Unit) {
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(text = stringResource(R.string.mcp_approve), onClick = { onApprove(true) }, modifier = Modifier.weight(1f))
        SecondaryButton(text = stringResource(R.string.mcp_deny), onClick = { onApprove(false) }, modifier = Modifier.weight(1f))
    }
    Text(text = stringResource(R.string.mcp_card_note), style = LifeType.small, color = LifeTheme.colors.muted)
}

/** The approval of one MCP server as a card in the conversation (it waits there for up to a day). */
@Composable
internal fun ColumnScope.McpApprovalCard(interaction: Interaction, open: Boolean, onApprove: (Boolean) -> Unit) {
    val server = interaction.server ?: return
    McpQuestion(server, interaction.folder)
    if (open) McpButtons(onApprove)
}

/** What a settled MCP approval says; a [personal] server's approval covers every folder. */
internal fun Interaction.Status.mcpResultRes(personal: Boolean): Int? = when (this) {
    Interaction.Status.ALLOWED -> if (personal) R.string.mcp_approved_personal else R.string.mcp_approved
    Interaction.Status.REFUSED -> R.string.mcp_refused
    Interaction.Status.EXPIRED -> R.string.mcp_expired
    Interaction.Status.NOT_DONE -> R.string.mcp_not_done
    Interaction.Status.GONE -> R.string.interaction_gone
    Interaction.Status.SENDING -> R.string.interaction_sending
    else -> null
}

/**
 * The MCP servers [session] can be given, from its sheet: its folder's, then the owner's
 * personal ones, each with its scope, what would start and its state, "Review and approve"
 * (the computer makes the approval card for exactly that definition, shown here at once and
 * answered like any approval) and "Revoke approval". Every request names the scope.
 */
@Composable
internal fun McpSheet(core: LifeOsCore, session: SessionSummary, onDismiss: () -> Unit) {
    val colors = LifeTheme.colors
    val scope = rememberCoroutineScope()
    val api = core.api
    // What the last request came to, said inside the sheet (a line below would be hidden by it).
    var message by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf<List<McpServer>?>(null) }
    var failed by remember { mutableStateOf(false) }
    // Counts what was changed from here: the list is read again.
    var version by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // The approval card that is being looked at, and what became of it.
    var card by remember { mutableStateOf<InteractionData?>(null) }
    var outcome by remember { mutableStateOf<Interaction.Status?>(null) }
    val notSent = stringResource(R.string.mcp_request_failed)
    val notThere = stringResource(R.string.mcp_request_gone)
    val unsupported = stringResource(R.string.mcp_request_unsupported)
    val revoked = stringResource(R.string.mcp_revoked)
    val nothingRevoked = stringResource(R.string.mcp_revoke_none)
    val unrecorded = stringResource(R.string.mcp_refusal_unrecorded)

    /** The owner's answer to the card that is shown; a yes the computer refused leaves it there to answer again. */
    suspend fun answer(cardId: String, approve: Boolean) {
        val answered = McpApprovals.answer(api, session.id, cardId, approve)
        outcome = answered.status
        if (answered.refusal != null) message = unrecorded
    }
    LaunchedEffect(session.id, version) {
        try {
            servers = api.mcpServers(session.id).filter { it.usable }
            failed = false
        } catch (_: ApiException) {
            failed = servers == null
        }
    }

    /** One request to the computer; the conversation, if it shows this session, looks at its cards again. */
    fun request(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } catch (error: ApiException) {
                message = when (error.code) {
                    "mcp_server_not_found" -> notThere
                    "mcp_server_unsupported" -> unsupported
                    else -> notSent
                }
            } finally {
                busy = false
                version++
            }
            if (core.chat.state.value.sessionId == session.id) core.chat.refreshCards()
        }
    }

    val reviewed = card
    val server = reviewed?.scopedServer
    if (reviewed != null && server != null) {
        SheetFrame(stringResource(R.string.mcp_title), onDismiss) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                McpQuestion(server, reviewed.folder)
                val result = outcome
                when {
                    busy -> Notice(stringResource(R.string.interaction_sending), alert = false)
                    result == null && message != null -> {
                        Notice(checkNotNull(message), alert = true)
                        McpButtons { approve -> request { answer(reviewed.id, approve) } }
                    }
                    result == null -> McpButtons { approve -> request { answer(reviewed.id, approve) } }
                    else -> {
                        result.mcpResultRes(server.personal)?.let { Notice(stringResource(it), alert = false) }
                        SecondaryButton(
                            text = stringResource(R.string.mcp_back),
                            onClick = {
                                card = null
                                outcome = null
                            },
                        )
                    }
                }
            }
        }
        return
    }
    SheetFrame(stringResource(R.string.mcp_title), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            McpWarning()
            Text(text = stringResource(R.string.mcp_note), style = LifeType.small, color = colors.muted)
            message?.let { Notice(it, alert = false) }
            val found = servers
            when {
                found == null && failed -> Text(stringResource(R.string.mcp_load_failed), style = LifeType.body, color = colors.text)
                found == null -> SkeletonCard(rows = 2)
                found.isEmpty() -> Text(stringResource(R.string.mcp_none), style = LifeType.body, color = colors.text)
                else -> found.forEach { item ->
                    McpServerRow(
                        server = item,
                        enabled = !busy,
                        onReview = {
                            request {
                                outcome = null
                                card = McpApprovals.card(api, session.id, item)
                            }
                        },
                        onRevoke = { request { message = if (McpApprovals.revoke(api, session.id, item)) revoked else nothingRevoked } },
                    )
                }
            }
        }
    }
}

@Composable
private fun McpServerRow(server: McpServer, enabled: Boolean, onReview: () -> Unit, onRevoke: () -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val approved = server.approval == McpServer.State.APPROVED
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            // An approved server is the one that runs outside the sandbox: it stands out.
            .border(if (approved) 2.dp else LifeDimens.hairline, if (approved) colors.warn else colors.divider, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = server.name, style = LifeType.rowTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        // Whose server it is, and so where an approval holds.
        Text(
            text = stringResource(if (server.personal) R.string.mcp_scope_personal else R.string.mcp_scope_folder),
            style = LifeType.small,
            color = colors.muted,
        )
        Text(
            text = stringResource(
                when (server.approval) {
                    McpServer.State.NOT_APPROVED -> R.string.mcp_state_not_approved
                    McpServer.State.APPROVED -> if (server.personal) R.string.mcp_state_approved_personal else R.string.mcp_state_approved
                    McpServer.State.CHANGED -> R.string.mcp_state_changed
                    McpServer.State.UNSUPPORTED -> R.string.mcp_state_unsupported
                },
            ),
            style = LifeType.label,
            color = when (server.approval) {
                McpServer.State.NOT_APPROVED, McpServer.State.UNSUPPORTED -> colors.muted
                else -> colors.warn
            },
        )
        McpDefinition(server)
        // An unsupported server can never be approved: no action at all.
        if (server.approvable || server.revocable) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (server.approvable) LinkAction(stringResource(R.string.mcp_review), onClick = onReview, enabled = enabled)
                if (server.revocable) LinkAction(stringResource(R.string.mcp_revoke), onClick = onRevoke, enabled = enabled)
            }
        }
    }
}
