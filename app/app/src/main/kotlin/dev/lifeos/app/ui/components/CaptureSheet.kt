package dev.lifeos.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.CommandAnswer
import dev.lifeos.app.data.CommandBar
import dev.lifeos.app.data.CommandOutcome
import dev.lifeos.app.data.CommandPrevious
import dev.lifeos.app.data.CommandQuestion
import dev.lifeos.app.data.Dates
import dev.lifeos.app.data.Reminders
import dev.lifeos.app.data.TaskNote
import dev.lifeos.app.ui.icons.LifeIcons
import dev.lifeos.app.ui.screens.ClockPicker
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What "+" opens (#185): a request to the command bar, a note, or a task. */
enum class CaptureKind { COMMAND, NOTE, TASK }

/** How the command bar reaches the computer and the rest of the app (given by the app). */
class CommandHooks(
    val run: (JsonObject) -> Deferred<CommandAnswer>,
    val newRequestId: () -> String,
    val undo: suspend (String) -> Boolean,
    /** Do what an answer hands over that stays on this screen (a note saved or deleted: lists read again). */
    val perform: (CommandOutcome) -> Unit,
    /** The answer moves the owner elsewhere: the sheet closes, the app opens it and confirms. */
    val leave: (CommandAnswer) -> Unit,
    /** Speech recognition is about to cover the app: it may come back without a lock. */
    val systemScreen: () -> Unit = {},
)

/** The bar's state while a request is on its way and after. */
private sealed interface BarState {
    data object Idle : BarState
    data object Working : BarState
    data class Answered(val answer: CommandAnswer, val undone: Set<String> = emptySet()) : BarState
    data class Failed(val failure: CommandBar.Failure) : BarState
}

/** A request as it was sent: asked again with the same id after a lost answer. */
private data class Sent(val requestId: String, val text: String?, val choice: String?, val previous: CommandPrevious?)

/**
 * "+" (#166, #185): one text field with the keyboard open and a microphone, then what "+"
 * offers: **Ask** (the command bar: the computer turns the request into actions, shows what it
 * did with Undo, or asks one short question), **Note** (with an optional reminder), **Task**
 * (with a due date and a note, #170) and **New chat**, which opens a blank chat at once and
 * hands it what was typed. Notes and tasks are saved through the outbox, also offline.
 * [prefill] is text shared from another app (it starts as a note).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(
    prefill: String,
    onDismiss: () -> Unit,
    onSaved: (CaptureKind, String, String?, String?, ZonedDateTime?) -> Unit,
    onNewChat: (String) -> Unit,
    hooks: CommandHooks,
) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val locale = appLocale()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(prefill) }
    var kind by rememberSaveable { mutableStateOf(if (prefill.isBlank()) CaptureKind.COMMAND else CaptureKind.NOTE) }
    var due by rememberSaveable { mutableStateOf<String?>(null) }
    var taskNote by rememberSaveable { mutableStateOf("") }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var remindDay by rememberSaveable { mutableStateOf<String?>(null) }
    var remindTime by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingRemindDay by rememberSaveable { mutableStateOf(false) }
    var pickingRemindTime by rememberSaveable { mutableStateOf(false) }
    var bar by remember { mutableStateOf<BarState>(BarState.Idle) }
    var sent by remember { mutableStateOf<Sent?>(null) }
    var answerText by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val zone = remember { ZoneId.systemDefault() }
    val remindAt = remindDay?.let { day -> remindTime?.let { time -> Reminders.moment(day, time, zone) } }
    val noSpeech = stringResource(R.string.command_no_speech)
    val undoFailed = stringResource(R.string.command_undo_failed)
    val showMessage = LocalSnackbar.current

    // Speech (#185): the phone's own recogniser, in the app's language, offline when it can.
    // LifeOS gets only the text; the audio goes to the recognition service of the phone.
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!heard.isNullOrEmpty()) text = listOf(text.trim(), heard).filter { it.isNotEmpty() }.joinToString(" ")
    }
    val speechPrompt = stringResource(R.string.command_speak_prompt)
    val startListening: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val language = CommandBar.speechLanguage(locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, speechPrompt)
        }
        try {
            hooks.systemScreen()
            listen.launch(intent)
        } catch (_: ActivityNotFoundException) {
            showMessage(noSpeech)
        }
    }

    fun send(request: Sent) {
        sent = request
        bar = BarState.Working
        scope.launch {
            val body = CommandBar.body(request.requestId, request.text, request.choice, locale.toLanguageTag(), CommandBar.now(zone), request.previous)
            bar = try {
                val answer = hooks.run(body).await()
                answer.results.filter { it.ok && it.performs != null && it.performs !in CommandBar.NAVIGATING }.forEach(hooks.perform)
                if (answer.question == null && CommandBar.navigation(answer) != null) {
                    sheetState.hide()
                    hooks.leave(answer)
                    return@launch
                }
                answerText = ""
                BarState.Answered(answer)
            } catch (error: ApiException) {
                BarState.Failed(CommandBar.failureOf(error))
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bg,
        contentColor = colors.text,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The task's date and note, and the bar's answer, make it taller: it scrolls above the keyboard.
                .verticalScroll(rememberScrollState())
                .padding(start = LifeDimens.pad, end = LifeDimens.pad, bottom = LifeDimens.pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.capture_title),
                style = LifeType.sectionTitle,
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(if (kind == CaptureKind.COMMAND) R.string.command_hint else R.string.capture_hint), style = LifeType.body) },
                textStyle = LifeType.body,
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                trailingIcon = { TopBarAction(LifeIcons.Mic, stringResource(R.string.command_speak), onClick = startListening) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.divider,
                    cursorColor = colors.primary,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedPlaceholderColor = colors.muted,
                    unfocusedPlaceholderColor = colors.muted,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .padding(4.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KindOption(stringResource(R.string.command_kind), kind == CaptureKind.COMMAND) { kind = CaptureKind.COMMAND }
                KindOption(stringResource(R.string.capture_kind_note), kind == CaptureKind.NOTE) { kind = CaptureKind.NOTE }
                KindOption(stringResource(R.string.capture_kind_task), kind == CaptureKind.TASK) { kind = CaptureKind.TASK }
                // Not a kind to save but a way out: a blank chat opens at once.
                KindOption(stringResource(R.string.chat_new), selected = false, role = Role.Button) {
                    val draft = text.trim()
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onNewChat(draft) }
                }
            }
            when (kind) {
                CaptureKind.COMMAND -> CommandPart(
                    bar = bar,
                    enabled = text.isNotBlank(),
                    answerText = answerText,
                    onAnswerText = { answerText = it },
                    context = context,
                    locale = locale,
                    onSend = { send(Sent(hooks.newRequestId(), text.trim(), null, null)) },
                    onPick = { option, answer ->
                        when {
                            option.choice != null -> send(Sent(hooks.newRequestId(), null, option.choice, null))
                            else -> send(Sent(hooks.newRequestId(), option.answer ?: option.label, null, answer.previous))
                        }
                    },
                    onAnswer = { answer -> send(Sent(hooks.newRequestId(), answerText.trim(), null, answer.previous)) },
                    onAgain = { sent?.let(::send) },
                    onSaveAs = { target -> kind = target; bar = BarState.Idle },
                    onUndo = { state, token ->
                        scope.launch {
                            val undone = hooks.undo(token)
                            if (undone) {
                                val current = bar
                                if (current is BarState.Answered && current.answer == state.answer) bar = current.copy(undone = current.undone + token)
                            } else {
                                showMessage(undoFailed)
                            }
                        }
                    },
                    onClose = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } },
                )
                CaptureKind.NOTE -> {
                    // A note may carry a reminder (#185): the computer notifies at that time; the
                    // notification opens the note.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = remindAt?.let { stringResource(R.string.capture_remind_at, reminderMoment(it, locale)) } ?: stringResource(R.string.capture_remind),
                            onClick = { pickingRemindDay = true },
                            modifier = Modifier.weight(1f),
                        )
                        if (remindAt != null) {
                            SecondaryButton(
                                text = stringResource(R.string.capture_remind_clear),
                                onClick = { remindDay = null; remindTime = null },
                                modifier = Modifier.weight(0.7f),
                            )
                        }
                    }
                }
                CaptureKind.TASK -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = due?.let { stringResource(R.string.capture_due, dueLabel(it) ?: it) } ?: stringResource(R.string.capture_due_none),
                            onClick = { pickingDate = true },
                            modifier = Modifier.weight(1f),
                        )
                        if (due != null) {
                            SecondaryButton(
                                text = stringResource(R.string.capture_due_clear),
                                onClick = { due = null },
                                modifier = Modifier.weight(0.6f),
                            )
                        }
                    }
                    TaskNoteField(taskNote) { taskNote = it }
                }
            }
            if (kind != CaptureKind.COMMAND) {
                PrimaryButton(
                    text = stringResource(R.string.capture_save),
                    enabled = text.isNotBlank(),
                    onClick = {
                        val saved = text.trim()
                        val day = due.takeIf { kind == CaptureKind.TASK }
                        val note = TaskNote.of(taskNote).takeIf { kind == CaptureKind.TASK }
                        val reminder = remindAt.takeIf { kind == CaptureKind.NOTE }
                        val savedKind = kind
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onSaved(savedKind, saved, day, note, reminder) }
                    },
                )
            }
        }
    }

    if (pickingDate) {
        DayPicker(initial = due, onDismiss = { pickingDate = false }) {
            due = it
            pickingDate = false
        }
    }
    if (pickingRemindDay) {
        DayPicker(initial = remindDay, onDismiss = { pickingRemindDay = false }) {
            remindDay = it
            pickingRemindDay = false
            pickingRemindTime = true // then the time
        }
    }
    if (pickingRemindTime) {
        val start = remindTime ?: ZonedDateTime.now(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).let { "%02d:%02d".format(it.hour, it.minute) }
        ClockPicker(initial = start, onDismiss = { pickingRemindTime = false }, dismissLabel = R.string.action_cancel) {
            remindTime = it
            pickingRemindTime = false
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** "Tue 24 Nov, 10:00" for a reminder on the phone's clock. */
private fun reminderMoment(at: ZonedDateTime, locale: Locale): String = "${dayLabel(at.toLocalDate(), locale)}, %02d:%02d".format(at.hour, at.minute)

/** The command bar's part of the sheet: send, the answer with Undo, one question, or what went wrong. */
@Composable
private fun CommandPart(
    bar: BarState,
    enabled: Boolean,
    answerText: String,
    onAnswerText: (String) -> Unit,
    context: Context,
    locale: Locale,
    onSend: () -> Unit,
    onPick: (dev.lifeos.app.data.CommandOption, CommandAnswer) -> Unit,
    onAnswer: (CommandAnswer) -> Unit,
    onAgain: () -> Unit,
    onSaveAs: (CaptureKind) -> Unit,
    onUndo: (BarState.Answered, String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LifeTheme.colors
    when (bar) {
        BarState.Idle -> PrimaryButton(text = stringResource(R.string.command_send), enabled = enabled, onClick = onSend)
        BarState.Working -> {
            PrimaryButton(text = stringResource(R.string.command_working), enabled = false, onClick = {})
            Text(
                stringResource(R.string.command_working),
                style = LifeType.small,
                color = colors.muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        is BarState.Failed -> {
            val message = when (bar.failure) {
                CommandBar.Failure.OFFLINE -> R.string.command_offline
                CommandBar.Failure.LOST -> R.string.command_lost
                CommandBar.Failure.NOT_UNDERSTOOD -> R.string.command_not_understood
                CommandBar.Failure.REFUSED -> R.string.command_failed
            }
            Text(stringResource(message), style = LifeType.body, color = colors.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            when (bar.failure) {
                CommandBar.Failure.OFFLINE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.command_save_note), onClick = { onSaveAs(CaptureKind.NOTE) }, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.command_save_task), onClick = { onSaveAs(CaptureKind.TASK) }, modifier = Modifier.weight(1f))
                }
                CommandBar.Failure.NOT_UNDERSTOOD -> SecondaryButton(stringResource(R.string.command_save_note), onClick = { onSaveAs(CaptureKind.NOTE) })
                // The same request again: a request that was done is answered as it was, never done twice.
                CommandBar.Failure.LOST -> PrimaryButton(stringResource(R.string.command_ask_again), onClick = onAgain)
                CommandBar.Failure.REFUSED -> PrimaryButton(stringResource(R.string.command_send), enabled = enabled, onClick = onSend)
            }
        }
        is BarState.Answered -> {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bar.answer.results.forEach { outcome ->
                    val line = outcomeLine(context, outcome, locale)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line, style = LifeType.body, color = if (outcome.ok) colors.text else colors.badge, modifier = Modifier.weight(1f))
                        val token = outcome.undo
                        if (token != null && outcome.ok) {
                            if (token in bar.undone) {
                                Text(stringResource(R.string.command_undone), style = LifeType.small, color = colors.muted)
                            } else {
                                UndoButton(stringResource(R.string.command_undo_label, line)) { onUndo(bar, token) }
                            }
                        }
                    }
                }
            }
            val question = bar.answer.question
            if (question != null) {
                QuestionPart(question, answerText, onAnswerText, onPick = { onPick(it, bar.answer) }, onAnswer = { onAnswer(bar.answer) })
            } else {
                PrimaryButton(text = stringResource(R.string.command_done), onClick = onClose)
            }
        }
    }
}

@Composable
private fun RowScope.UndoButton(label: String, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = LifeDimens.minTouch)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.action_undo), style = LifeType.button, color = colors.primary)
    }
}

/** The one question: its options (each a button), and for the model's question an answer field. */
@Composable
private fun QuestionPart(question: CommandQuestion, answerText: String, onAnswerText: (String) -> Unit, onPick: (dev.lifeos.app.data.CommandOption) -> Unit, onAnswer: () -> Unit) {
    val colors = LifeTheme.colors
    val asked = question.text ?: stringResource(pickQuestion(question.kind))
    Text(asked, style = LifeType.rowTitle, color = colors.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    question.options.forEach { option ->
        val label = listOfNotNull(option.label, option.detail?.takeIf { it.isNotBlank() }).joinToString(" · ")
        SecondaryButton(text = label, onClick = { onPick(option) })
    }
    if (question.kind == "clarify") {
        OutlinedTextField(
            value = answerText,
            onValueChange = onAnswerText,
            placeholder = { Text(stringResource(R.string.command_answer_hint), style = LifeType.body) },
            textStyle = LifeType.body,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.divider,
                cursorColor = colors.primary,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(text = stringResource(R.string.command_answer_send), enabled = answerText.isNotBlank(), onClick = onAnswer)
    }
}

private fun pickQuestion(kind: String): Int = when (kind) {
    "task" -> R.string.command_pick_task
    "note" -> R.string.command_pick_note
    "event" -> R.string.command_pick_event
    "chat" -> R.string.command_pick_chat
    "folder" -> R.string.command_pick_folder
    "course" -> R.string.command_pick_course
    "assignment" -> R.string.command_pick_assignment
    "project" -> R.string.command_pick_project
    else -> R.string.command_pick_other
}

/** The actions that open or continue a chat: their line names the chat's change permission. */
private val CHAT_ACTIONS = setOf("new_chat", "continue_chat", "work_in_folder")

/**
 * One line of what an action did, in the app's language: "Task added: Buy milk",
 * "Note saved, reminder Tue 24 Nov, 10:00: Call the office".
 */
fun outcomeLine(context: Context, outcome: CommandOutcome, locale: Locale): String {
    val title = outcome.title.orEmpty()
    val moment = whenLabel(outcome.moment, locale)
    if (!outcome.ok) {
        val reason = when {
            outcome.error == "time_in_past" -> R.string.command_result_past
            outcome.error.orEmpty().endsWith("_not_found") -> R.string.command_result_not_found
            else -> R.string.command_result_failed
        }
        return context.getString(reason)
    }
    // A chat the bar opens or continues: what happened, then the change permission it got (#190).
    if (outcome.action in CHAT_ACTIONS) {
        val line = when {
            outcome.note == "chat_not_found" -> return context.getString(R.string.command_result_chat_not_found)
            outcome.note == "chat_busy" -> context.getString(R.string.command_result_chat_busy, title)
            outcome.note == "not_sent" -> context.getString(R.string.command_result_chat_not_sent, title)
            outcome.action == "new_chat" -> context.getString(R.string.command_result_new_chat)
            outcome.action == "work_in_folder" -> context.getString(R.string.command_result_work_in_folder, title)
            outcome.flag("send") -> context.getString(R.string.command_result_sent, title)
            else -> context.getString(R.string.command_result_open, title)
        }
        val setting = when (outcome.mode) {
            "capable" -> R.string.command_setting_capable
            "read_only" -> R.string.command_setting_read_only
            else -> return line
        }
        return context.getString(R.string.command_result_with_setting, line, context.getString(setting))
    }
    return when (outcome.action) {
        "create_note" -> if (moment != null) context.getString(R.string.command_result_create_note_reminder, title, moment) else context.getString(R.string.command_result_create_note, title)
        "edit_note" -> context.getString(R.string.command_result_edit_note, title)
        "delete_note" -> context.getString(R.string.command_result_delete_note, title)
        "set_reminder" -> context.getString(R.string.command_result_set_reminder, title, moment.orEmpty())
        "cancel_reminder" -> context.getString(R.string.command_result_cancel_reminder, title)
        "add_task" -> context.getString(R.string.command_result_add_task, title)
        "complete_task" -> context.getString(R.string.command_result_complete_task, title)
        "edit_task" -> context.getString(R.string.command_result_edit_task, title)
        "add_event" -> context.getString(R.string.command_result_add_event, title, moment.orEmpty())
        "change_event" -> context.getString(R.string.command_result_change_event, title, moment.orEmpty())
        "work_in_folder" -> context.getString(R.string.command_result_work_in_folder, title)
        "stop_using_folder" -> context.getString(R.string.command_result_stop_using_folder, title)
        "refresh_plan" -> context.getString(R.string.command_result_refresh_plan)
        "sync_courses" -> context.getString(R.string.command_result_sync_courses)
        "record_sleep" -> context.getString(R.string.command_result_record_sleep, title, moment ?: outcome.moment.orEmpty())
        else -> if (title.isNotBlank()) context.getString(R.string.command_result_open, title) else context.getString(R.string.command_result_opening)
    }
}

/** A time the bridge sent ("2027-01-16T07:00:00Z", "2027-01-18 14:00", "2027-01-20") on the phone's clock. */
fun whenLabel(value: String?, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (value.isNullOrBlank()) return null
    return try {
        val at = Instant.parse(value).atZone(zone)
        "${dayLabel(at.toLocalDate(), locale)}, %02d:%02d".format(at.hour, at.minute)
    } catch (_: Exception) {
        try {
            val at = LocalDateTime.parse(value.trim().replace(' ', 'T'))
            "${dayLabel(at.toLocalDate(), locale)}, %02d:%02d".format(at.hour, at.minute)
        } catch (_: Exception) {
            Dates.parse(value.take(10))?.let { dayLabel(it, locale) } ?: value.takeIf { it.length <= 11 }
        }
    }
}

@Composable
private fun RowScope.KindOption(label: String, selected: Boolean, role: Role = Role.RadioButton, onClick: () -> Unit) {
    val colors = LifeTheme.colors
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = LifeDimens.minTouch)
            .clip(shape)
            .background(if (selected) colors.text else colors.surface)
            .then(if (role == Role.RadioButton) Modifier.selectable(selected = selected, role = role, onClick = onClick) else Modifier.clickable(role = role, onClick = onClick))
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = LifeType.button, color = if (selected) colors.bg else colors.muted, textAlign = TextAlign.Center)
    }
}

/** The system-style date picker. Returns the day as `YYYY-MM-DD`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPicker(initial: String?, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val start = (Dates.parse(initial) ?: today()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = start)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                },
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}
