package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.Chat
import dev.lifeos.app.data.CommandList
import dev.lifeos.app.data.Commands
import dev.lifeos.app.data.Endpoints
import dev.lifeos.app.data.UiState
import dev.lifeos.app.ui.components.rememberLoad
import dev.lifeos.app.data.ChatFailure
import dev.lifeos.app.data.ChatFolder
import dev.lifeos.app.data.ChatMessage
import dev.lifeos.app.data.ChatPhase
import dev.lifeos.app.data.ChatProblem
import dev.lifeos.app.data.ChatState
import dev.lifeos.app.data.Interaction
import dev.lifeos.app.data.SkillInstall
import dev.lifeos.app.ui.components.ErrorNotice
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.MarkdownText
import dev.lifeos.app.ui.components.NotPairedNotice
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.components.SecondaryButton
import dev.lifeos.app.ui.components.SkeletonCard
import dev.lifeos.app.ui.components.TopBar
import dev.lifeos.app.ui.components.TopBarAction
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How often the app asks about a run it is not receiving, while the conversation is on screen. */
private const val CHECK_MILLIS = 5_000L

/** How often a card asks whether the skill it shows has been downloaded. */
private const val CARD_MILLIS = 3_000L

/**
 * One conversation: the messages, the answer as it is written, and the composer. It is the
 * conversation [LifeOsCore.chat] holds, so it is the same one until the owner starts a new
 * chat or opens another session.
 */
@Composable
fun ChatScreen(
    core: LifeOsCore,
    onBack: () -> Unit,
    onConnectionClick: () -> Unit,
    onPair: () -> Unit,
    onSessions: () -> Unit,
    onNewChat: () -> Unit,
) {
    val chat = core.chat
    val state by chat.state.collectAsStateWithLifecycle()
    val models = (rememberLoad(core, Endpoints.modelChoices) { false }.state.collectAsStateWithLifecycle().value as? UiState.Content)?.data
    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        TopBar(
            title = stringResource(R.string.nav_chat),
            // Where it runs and with what: the folder, the model and the effort.
            subtitle = listOfNotNull(state.folder?.let { folderName(it) }, state.choice.label(models).takeIf { it.isNotEmpty() }).joinToString(" · ").takeIf { it.isNotEmpty() },
            onBack = onBack,
            onConnectionClick = onConnectionClick,
            actions = {
                TopBarAction(LifeIcons.Sessions, stringResource(R.string.more_sessions), onClick = onSessions)
                TopBarAction(LifeIcons.Compose, stringResource(R.string.chat_new), onClick = onNewChat)
            },
        )
        ConversationPane(core, chat, onPair = onPair, onSessions = onSessions)
    }
}

/**
 * Everything of a conversation below the top bar: the thread, the waiting messages, the mode
 * pill ([modes]) and the composer. [chat] is the conversation it shows.
 */
@Composable
internal fun ColumnScope.ConversationPane(
    core: LifeOsCore,
    chat: Chat,
    onPair: () -> Unit,
    onSessions: () -> Unit,
    modes: Boolean = true,
    empty: @Composable ColumnScope.(onPrompt: (String) -> Unit) -> Unit = { EmptyChat(it) },
) {
    val state by chat.state.collectAsStateWithLifecycle()
    val paired = core.pairing.collectAsStateWithLifecycle().value != null
    val locked by core.locked.collectAsStateWithLifecycle()
    val reload by core.reload.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }

    // Read the conversation when it is shown, after an unlock and when the connection returns.
    LaunchedEffect(state.generation, paired, locked, reload) {
        if (paired && !locked && !state.loaded && !state.loading) chat.load()
    }
    // A run the app is not receiving: ask now and then, only while this screen is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.generation, state.phase == ChatPhase.ELSEWHERE, locked) {
        if (state.phase != ChatPhase.ELSEWHERE || locked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(CHECK_MILLIS)
                chat.check()
            }
        }
    }
    // A skill that is still being downloaded becomes ready (or fails) without any event: its
    // card asks while it is on screen (#97).
    val fetching = state.interactions.any { it.pending && it.install?.state == SkillInstall.State.DOWNLOADING }
    LaunchedEffect(state.generation, fetching, locked) {
        if (!fetching || locked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(CARD_MILLIS)
                chat.refreshCards()
            }
        }
    }
    // A message that was not sent comes back into the composer.
    LaunchedEffect(state.draft) {
        val back = state.draft ?: return@LaunchedEffect
        draft = if (draft.isBlank()) back else back + "\n" + draft
        chat.takeDraft()
    }

    val scroll = rememberScrollState()
    // Follow the answer while it grows, unless the owner scrolled up to read.
    var follow by remember { mutableStateOf(true) }
    // Earlier messages are put on top: the screen keeps its place by the height that was added.
    var heightBefore by remember { mutableStateOf<Int?>(null) }
    val current by rememberUpdatedState(state)
    val older = {
        if (current.olderExist && !current.loadingOlder) {
            follow = false
            heightBefore = scroll.maxValue
            chat.loadOlder()
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { Triple(scroll.value, scroll.maxValue, scroll.isScrollInProgress) }.collect { (value, max, moving) ->
            when {
                // Scrolled up to the very top: the page before is asked for.
                moving && value == 0 && max > 0 -> older()
                moving -> follow = max - value < 120
                follow && value < max -> scroll.scrollTo(max)
            }
        }
    }
    LaunchedEffect(state.olderLoads) {
        val before = heightBefore ?: return@LaunchedEffect
        heightBefore = null
        val after = withTimeoutOrNull(1_000) { snapshotFlow { scroll.maxValue }.first { it != before } } ?: return@LaunchedEffect
        // A drag or fling that is still going on would cancel the correction: it comes right after.
        snapshotFlow { scroll.isScrollInProgress }.first { !it }
        scroll.scrollTo(scroll.value + (after - before))
    }
    LaunchedEffect(state.generation, state.messages.lastOrNull()?.key, state.interactions.size) { follow = true }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = LifeDimens.pad, vertical = 12.dp),
        verticalArrangement = if (paired && state.isEmpty && state.loaded) Arrangement.spacedBy(10.dp, Alignment.CenterVertically) else Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            !paired -> NotPairedNotice(onPair)
            state.loading && state.messages.isEmpty() -> SkeletonCard(rows = 2)
            state.loadProblem != null -> ErrorNotice(checkNotNull(state.loadProblem), 0, onRetry = chat::load)
            state.isEmpty && state.loaded -> empty { draft = it }
            else -> Thread(core, state, chat, onSessions = onSessions, onOlder = older, onStop = { confirmStop = true })
        }
    }
    if (paired && state.queue.isNotEmpty()) QueueList(state, chat, locked)
    // Coach (#114): the owner asked to tidy the conversation up; that is one run on the computer.
    if (paired && state.compacting) SystemLine(stringResource(R.string.coach_tidying_now))
    // "/" commands of the session's folder: asked for when a message starts with "/".
    var commands by remember(state.sessionId) { mutableStateOf<CommandList?>(null) }
    val slash = modes && draft.startsWith("/")
    LaunchedEffect(state.sessionId, slash, reload) {
        val id = state.sessionId
        if (slash && id != null && paired && !locked) {
            try {
                commands = core.api.commands(id)
            } catch (_: ApiException) {
                // Without the list the message is sent as typed; the computer checks it.
            }
        }
    }
    if (paired && slash) CommandHelp(commands, draft, state, onPick = { draft = Commands.complete(it) })
    if (paired && modes) {
        val modelChoices = (rememberLoad(core, Endpoints.modelChoices) { false }.state.collectAsStateWithLifecycle().value as? UiState.Content)?.data
        var pickingModel by rememberSaveable { mutableStateOf(false) }
        // What belongs to this session (#75): its title, group, reminders and reply notifications.
        val change = rememberSessionChange(core)
        var sessionSheet by rememberSaveable(state.sessionId) { mutableStateOf(false) }
        ModeRow(
            capable = state.capable,
            enabled = state.phase == ChatPhase.IDLE,
            onChange = chat::setCapable,
            model = state.choice.label(modelChoices).ifEmpty { stringResource(R.string.chat_model_default) },
            onModel = { pickingModel = true },
            // A conversation without a message yet is no session on the computer.
            onSession = if (state.sessionId != null) ({ sessionSheet = true }) else null,
        )
        SessionSheetHost(core, state.sessionId.takeIf { sessionSheet }, change, onDismiss = { sessionSheet = false })
        if (pickingModel && !locked) {
            ModelSheet(
                choice = state.choice,
                choices = modelChoices,
                onDismiss = { pickingModel = false },
                onSave = {
                    pickingModel = false
                    chat.setModel(it)
                },
            )
        }
    }
    Composer(
        draft = draft,
        onDraftChange = { draft = it },
        commandLength = if (modes) Commands.recognisedLength(commands, draft) else 0,
        canSend = paired && (state.canSend || state.canQueue) && draft.isNotBlank(),
        // While an answer is awaited the message waits in line on the computer.
        sendLabel = stringResource(if (state.canQueue) R.string.chat_queue_add else R.string.chat_send),
        onSend = { if (chat.send(draft)) draft = "" },
    )
    if (confirmStop && state.canStop && !locked) {
        ConfirmDialog(
            title = stringResource(R.string.chat_stop_title),
            text = stringResource(R.string.chat_stop_text),
            confirm = stringResource(R.string.chat_stop),
            onDismiss = { confirmStop = false },
            onConfirm = {
                confirmStop = false
                chat.cancel()
            },
        )
    }
}

@Composable
internal fun folderName(folder: ChatFolder): String = if (folder.memory) stringResource(R.string.chat_memory_label) else folder.label

/** The empty state: what this is, and three example prompts that fill the composer. */
@Composable
internal fun EmptyChat(onPrompt: (String) -> Unit) {
    val colors = LifeTheme.colors
    Text(text = stringResource(R.string.chat_empty_title), style = LifeType.featured, color = colors.text)
    Text(
        text = stringResource(R.string.chat_empty_text),
        style = LifeType.small,
        color = colors.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    listOf(R.string.chat_prompt_1, R.string.chat_prompt_2, R.string.chat_prompt_3).forEach { prompt ->
        val text = stringResource(prompt)
        val shape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .heightIn(min = LifeDimens.minTouch)
                .clip(shape)
                .border(1.dp, colors.divider, shape)
                .clickable(role = Role.Button) { onPrompt(text) }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, style = LifeType.body, color = colors.text, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ColumnScope.Thread(core: LifeOsCore, state: ChatState, chat: Chat, onSessions: () -> Unit, onOlder: () -> Unit, onStop: () -> Unit) {
    if (state.loadingOlder) {
        SystemLine(stringResource(R.string.chat_older_loading))
    } else if (state.olderExist) {
        LinkAction(stringResource(R.string.chat_older_load), onClick = onOlder)
    }
    // Questions and approvals of the last run. Those that wait for the owner come last, where
    // the eye is; those that are settled stay as a record above the answer that followed them.
    val (waiting, settled) = state.interactions.partition { it.pending }
    val cards: @Composable (List<Interaction>) -> Unit = { list ->
        list.forEach { interaction ->
            key(interaction.id) {
                InteractionCard(
                    interaction = interaction,
                    onApprove = { chat.answerApproval(interaction.id, it) },
                    onAnswer = { chat.answerQuestion(interaction.id, it) },
                    onSkip = { chat.skipQuestion(interaction.id) },
                    onExpire = { chat.expire(interaction.id) },
                )
            }
        }
    }
    val answer = state.messages.lastOrNull()?.takeIf { !it.fromOwner }
    state.messages.forEach { message ->
        // Coach (#114): from this message on it is a renewed session; what stands above was tidied up.
        if (message.key == state.renewedAt) SystemLine(stringResource(R.string.coach_renewed))
        if (message === answer) cards(settled)
        if (message.fromOwner) OwnerBubble(message) else AnswerWithFiles(core, message, cutOff = message.incomplete && state.phase == ChatPhase.IDLE)
    }
    if (answer == null) cards(settled)
    cards(waiting)
    // "Waiting for you" is about the run: a card that waits across runs does not hold it up.
    val asked = state.interactions.any { it.status == Interaction.Status.WAITING && !it.crossRun }
    when (state.phase) {
        ChatPhase.SENDING, ChatPhase.ANSWERING -> Working(state.sinceMillis, onStop.takeIf { state.canStop }) {
            when {
                state.stopping -> stringResource(R.string.chat_stopping)
                asked -> stringResource(R.string.chat_waiting_for_you)
                state.tidying && state.phase == ChatPhase.SENDING -> stringResource(R.string.coach_tidying, it)
                else -> stringResource(R.string.chat_working, it)
            }
        }
        ChatPhase.ELSEWHERE -> {
            Working(state.sinceMillis, onStop.takeIf { state.canStop }) {
                when {
                    state.stopping -> stringResource(R.string.chat_stopping)
                    asked -> stringResource(R.string.chat_waiting_for_you)
                    else -> stringResource(R.string.chat_elsewhere, it)
                }
            }
            if (state.unreachable) SystemLine(stringResource(R.string.chat_elsewhere_unreachable))
        }
        ChatPhase.IDLE -> Unit
    }
    state.failure?.let { FailureNotice(it, chat::retry, chat::dismissFailure, onSessions) }
}

/**
 * Read-only (the default for every conversation) or "changes allowed": the next messages then
 * ask the computer for its capable mode, in which the assistant may change files and run
 * commands inside the guardrails, and asks here before a deletion and the like.
 */
@Composable
private fun ModeRow(capable: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, model: String, onModel: () -> Unit, onSession: (() -> Unit)? = null) {
    val colors = LifeTheme.colors
    val label = stringResource(R.string.chat_mode_toggle)
    val modelLabel = stringResource(R.string.chat_model_label, model)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LifeDimens.pad),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The conversation's model and effort; a tap changes them.
        Box(
            modifier = Modifier
                .heightIn(min = LifeDimens.minTouch)
                .clip(RoundedCornerShape(50))
                .clickable(onClickLabel = modelLabel, role = Role.Button, onClick = onModel)
                .semantics { contentDescription = modelLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = model,
                style = LifeType.label,
                color = colors.text,
                maxLines = 1,
                modifier = Modifier
                    .border(1.5.dp, colors.divider, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Box(
            modifier = Modifier
                .heightIn(min = LifeDimens.minTouch)
                .clip(RoundedCornerShape(50))
                .toggleable(value = capable, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .border(1.5.dp, if (capable) colors.warn else colors.divider, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).background(if (capable) colors.warn else colors.muted, CircleShape))
                Text(
                    text = stringResource(if (capable) R.string.chat_mode_capable else R.string.chat_mode_read_only),
                    style = LifeType.label,
                    color = if (enabled) colors.text else colors.muted,
                )
            }
        }
        if (onSession != null) {
            Spacer(Modifier.weight(1f))
            val sessionLabel = stringResource(R.string.session_menu)
            Box(
                modifier = Modifier
                    .size(LifeDimens.minTouch)
                    .clip(CircleShape)
                    .clickable(onClickLabel = sessionLabel, role = Role.Button, onClick = onSession)
                    .semantics { contentDescription = sessionLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(LifeIcons.More, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
            }
        }
      }
        if (capable) Text(text = stringResource(R.string.chat_mode_capable_note), style = LifeType.small, color = colors.muted)
    }
}

/** The owner's message: to the right, on the surface colour, with one tight corner. */
@Composable
private fun OwnerBubble(message: ChatMessage) {
    val colors = LifeTheme.colors
    val you = stringResource(R.string.chat_you)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Spacer(Modifier.weight(1f))
        SelectionContainer(Modifier.weight(4f, fill = false)) {
            Text(
                text = message.text,
                style = LifeType.body,
                color = colors.text,
                modifier = Modifier
                    .background(colors.surface, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { contentDescription = "$you: ${message.text}" },
            )
        }
    }
}

/** The assistant's answer: to the left, outlined, rendered as Markdown. */
@Composable
private fun AssistantBubble(message: ChatMessage, cutOff: Boolean) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Row(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(9f, fill = false)
                .border(1.dp, colors.divider, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer { MarkdownText(message.text) }
            if (cutOff) Text(stringResource(R.string.chat_incomplete), style = LifeType.small, color = colors.muted)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** The files offered with an answer, one card each, under the answer. */
@Composable
private fun AnswerWithFiles(core: LifeOsCore, message: ChatMessage, cutOff: Boolean) {
    if (message.text.isNotBlank() || cutOff) AssistantBubble(message, cutOff)
    // MCP servers this answer used (#153): they ran outside the sandbox, and the answer says so.
    if (message.servers.isNotEmpty()) {
        Text(
            text = pluralStringResource(R.plurals.mcp_used, message.servers.size, message.servers.size, message.servers.joinToString(", ")),
            style = LifeType.small,
            color = LifeTheme.colors.warn,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    message.files.forEach { FileCard(core, it) }
}

/** "Working… 0:42": a small spinner and the time since the message was sent. */
@Composable
private fun Working(sinceMillis: Long, onStop: (() -> Unit)?, text: @Composable (String) -> String) {
    val colors = LifeTheme.colors
    val elapsed by produceState(0L, sinceMillis) {
        while (true) {
            value = ((System.currentTimeMillis() - sinceMillis) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    val label = text("%d:%02d".format(elapsed / 60, elapsed % 60))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // The time is not read out every second: only the state itself.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = colors.primary, trackColor = colors.surface2, strokeWidth = 2.dp)
        Text(text = label, style = LifeType.small, color = colors.muted, modifier = Modifier.weight(1f))
        if (onStop != null) LinkAction(stringResource(R.string.chat_stop), onClick = onStop)
    }
}

/** A quiet line between rules, for what is not a message. */
@Composable
internal fun SystemLine(text: String) {
    Text(
        text = text,
        style = LifeType.small,
        color = LifeTheme.colors.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun FailureNotice(failure: ChatFailure, onRetry: () -> Unit, onDismiss: () -> Unit, onSessions: () -> Unit) {
    val colors = LifeTheme.colors
    LifeCard {
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(failure.problem.textRes()),
                style = LifeType.body,
                color = colors.text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failure.prompt != null) {
                    PrimaryButton(text = stringResource(R.string.action_retry), onClick = onRetry, modifier = Modifier.weight(1f))
                }
                if (failure.problem == ChatProblem.NO_ANSWER) {
                    PrimaryButton(text = stringResource(R.string.chat_open_sessions), onClick = onSessions, modifier = Modifier.weight(1f))
                }
                SecondaryButton(text = stringResource(R.string.chat_dismiss), onClick = onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun ChatProblem.textRes(): Int = when (this) {
    ChatProblem.NOT_REACHED -> R.string.chat_fail_not_reached
    ChatProblem.NO_ANSWER -> R.string.chat_fail_no_answer
    ChatProblem.NOT_DELIVERED -> R.string.chat_fail_not_delivered
    ChatProblem.IN_USE -> R.string.chat_fail_in_use
    ChatProblem.BUSY -> R.string.chat_fail_busy
    ChatProblem.FOLDER_GONE -> R.string.chat_fail_folder_gone
    ChatProblem.SESSION_GONE -> R.string.chat_fail_session_gone
    ChatProblem.FOLDER_IN_REPOSITORY -> R.string.error_folder_in_repository
    ChatProblem.CAPABLE_OFF -> R.string.chat_fail_capable_off
    ChatProblem.NOT_SET_UP -> R.string.chat_fail_not_set_up
    ChatProblem.TOO_LONG -> R.string.chat_fail_too_long
    ChatProblem.STOPPED -> R.string.chat_fail_stopped
    ChatProblem.STOPPED_HERE -> R.string.chat_fail_stopped_here
    ChatProblem.STOP_FAILED -> R.string.chat_fail_stop_failed
    ChatProblem.QUEUE_FAILED -> R.string.chat_fail_queue_failed
    ChatProblem.COMMAND_UNKNOWN -> R.string.chat_fail_command_unknown
    ChatProblem.SKILLS_OFF -> R.string.chat_fail_skills_off
    ChatProblem.MCP_OFF -> R.string.chat_fail_mcp_off
    ChatProblem.UNRECORDED -> R.string.chat_fail_unrecorded
    ChatProblem.SETTING_FAILED -> R.string.chat_fail_setting_failed
    ChatProblem.TOO_LARGE -> R.string.chat_fail_too_large
    ChatProblem.TIDY_FAILED -> R.string.coach_tidy_failed
    ChatProblem.FIRST_LOST -> R.string.chat_fail_first_lost
    ChatProblem.NOT_PAIRED -> R.string.chat_fail_not_paired
    ChatProblem.FAILED -> R.string.chat_fail_failed
}

@Composable
private fun Composer(draft: String, onDraftChange: (String) -> Unit, canSend: Boolean, sendLabel: String, onSend: () -> Unit, commandLength: Int = 0) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LifeDimens.pad, vertical = 8.dp)
            .heightIn(min = LifeDimens.minTouch)
            .border(1.dp, colors.divider, shape)
            .padding(start = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        ) {
            if (draft.isEmpty()) {
                Text(text = stringResource(R.string.chat_composer_hint), style = LifeType.body, color = colors.muted)
            }
            // Usable at any time: a message can be written while an answer is still arriving.
            // The field keeps its own cursor; text put in from outside (an example prompt, a completed
            // command, a message handed back) places the cursor at its end.
            var field by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
            if (field.text != draft) field = TextFieldValue(draft, TextRange(draft.length))
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != draft) onDraftChange(it.text)
                },
                textStyle = LifeType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.primary),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                // A recognised "/" command stands out in the message.
                visualTransformation = remember(commandLength, colors.link) { CommandHighlight(commandLength, colors.link) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            modifier = Modifier
                .size(LifeDimens.minTouch)
                .clip(CircleShape)
                .clickable(enabled = canSend, onClickLabel = sendLabel, role = Role.Button, onClick = onSend)
                .semantics {
                    role = Role.Button
                    contentDescription = sendLabel
                    if (!canSend) disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .alpha(if (canSend) 1f else 0.45f)
                    .background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LifeIcons.Send, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
