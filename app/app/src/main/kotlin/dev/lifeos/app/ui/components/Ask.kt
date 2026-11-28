package dev.lifeos.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.CommandAnswer
import dev.lifeos.app.data.CommandBar
import dev.lifeos.app.data.CommandOption
import dev.lifeos.app.data.CommandOutcome
import dev.lifeos.app.data.CommandPrevious
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import java.time.ZoneId

// ---------------------------------------------------------------- speech (#185, #204)

/** The recogniser's request: Turkish first with English as an additional language, offline when it can. */
object Speech {
    // Read by the phone's recogniser where it knows them (the names of the platform's extras;
    // the newer ones as text, so older Android versions simply ignore them).
    private const val ADDITIONAL_LANGUAGES = "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES"
    private const val LANGUAGE_SWITCH = "android.speech.extra.ENABLE_LANGUAGE_SWITCH"
    private const val LANGUAGE_SWITCH_ALLOWED = "android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES"

    fun intent(prompt: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, CommandBar.SPEECH_FIRST)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, CommandBar.SPEECH_FIRST)
        putExtra(ADDITIONAL_LANGUAGES, CommandBar.SPEECH_ALSO)
        putExtra(LANGUAGE_SWITCH, "balanced")
        putStringArrayListExtra(LANGUAGE_SWITCH_ALLOWED, arrayListOf(CommandBar.SPEECH_FIRST, *CommandBar.SPEECH_ALSO))
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    }
}

/**
 * The microphone of a message field: starts the phone's own speech recognition (no microphone
 * permission, no cost; LifeOS only gets the text) and hands the recognised text to [onHeard].
 * [systemScreen]: the recogniser is about to cover the app, which may come back without a lock.
 */
@Composable
fun rememberSpeech(systemScreen: () -> Unit, onHeard: (String) -> Unit): () -> Unit {
    val heard by rememberUpdatedState(onHeard)
    val covering by rememberUpdatedState(systemScreen)
    val showMessage = LocalSnackbar.current
    val noSpeech = stringResource(R.string.command_no_speech)
    val prompt = stringResource(R.string.command_speak_prompt)
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) heard(text)
    }
    return remember(listen, prompt, noSpeech) {
        {
            try {
                covering()
                listen.launch(Speech.intent(prompt))
            } catch (_: ActivityNotFoundException) {
                showMessage(noSpeech)
            }
        }
    }
}

// ---------------------------------------------------------------- one request and its answer (#204, #205)

sealed interface AskPhase {
    data object Idle : AskPhase
    data object Working : AskPhase
    data class Answered(val answer: CommandAnswer, val undone: Set<String> = emptySet()) : AskPhase
    data class Failed(val failure: CommandBar.Failure) : AskPhase
}

/**
 * One request to the command interpreter and what came of it, for the places that ask outside
 * the "+" sheet: the home's bar and the coach conversation. The request runs in [launch] (the
 * app's own scope: leaving the screen does not cut it off). What the answer hands the app is
 * done through [perform]; the part that is conversation goes to [onTalk]; a request that
 * could not be answered goes to [onFailed], which says whether it dealt with it.
 */
@Stable
class Ask(
    private val launch: (suspend () -> Unit) -> Unit,
    private val hooks: CommandHooks,
    private val languageTag: () -> String,
    private val perform: (CommandOutcome) -> Unit,
    private val onTalk: (String) -> Unit,
    private val coachSaid: () -> String? = { null },
    private val onFailed: (String, CommandBar.Failure) -> Boolean = { _, _ -> false },
) {
    private data class Sent(val requestId: String, val text: String?, val choice: String?, val previous: CommandPrevious?)

    var phase by mutableStateOf<AskPhase>(AskPhase.Idle)
        private set

    /** The owner's words of the request on show. */
    var asked by mutableStateOf<String?>(null)
        private set

    /** The part of the request that went on to the coach, if any. */
    var talked by mutableStateOf<String?>(null)
        private set

    private var sent: Sent? = null

    val working: Boolean get() = phase == AskPhase.Working

    fun send(text: String) {
        val request = text.trim()
        if (request.isEmpty() || working) return
        asked = request
        talked = null
        run(Sent(hooks.newRequestId(), request, null, null))
    }

    fun pick(option: CommandOption, answer: CommandAnswer) {
        if (working) return
        when {
            option.choice != null -> run(Sent(hooks.newRequestId(), null, option.choice, null))
            else -> run(Sent(hooks.newRequestId(), option.answer ?: option.label, null, answer.previous))
        }
    }

    fun answer(text: String, answer: CommandAnswer) {
        if (text.isBlank() || working) return
        run(Sent(hooks.newRequestId(), text.trim(), null, answer.previous))
    }

    /** The same request again, with the same id: one that was done is answered as it was. */
    fun again() {
        if (!working) sent?.let(::run)
    }

    fun undo(token: String, failed: () -> Unit) {
        launch {
            if (hooks.undo(token)) {
                val current = phase
                if (current is AskPhase.Answered) phase = current.copy(undone = current.undone + token)
            } else {
                failed()
            }
        }
    }

    fun clear() {
        if (working) return
        phase = AskPhase.Idle
        asked = null
        talked = null
        sent = null
    }

    private fun run(request: Sent) {
        sent = request
        phase = AskPhase.Working
        launch {
            val body = CommandBar.body(
                request.requestId, request.text, request.choice, languageTag(), CommandBar.now(ZoneId.systemDefault()), request.previous,
                coachSaid = coachSaid(),
            )
            phase = try {
                val answer = hooks.run(body).await()
                answer.results.filter { it.ok && it.performs != null }.forEach(perform)
                answer.talk?.takeIf { it.isNotBlank() }?.let {
                    talked = it
                    onTalk(it)
                }
                AskPhase.Answered(answer)
            } catch (error: ApiException) {
                val failure = CommandBar.failureOf(error)
                val text = request.text
                if (text != null && request.previous == null && onFailed(text, failure)) AskPhase.Idle else AskPhase.Failed(failure)
            }
        }
    }
}

/**
 * What an [Ask] has to show: what was understood, each thing done with its Undo, what cannot
 * be done, the one question, or why no answer came. Nothing while it is idle.
 */
@Composable
fun AskResult(ask: Ask, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val locale = appLocale()
    val showMessage = LocalSnackbar.current
    val undoFailed = stringResource(R.string.command_undo_failed)
    var answerText by rememberSaveable { mutableStateOf("") }
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val phase = ask.phase) {
            AskPhase.Idle -> Unit
            AskPhase.Working -> Text(stringResource(R.string.command_understanding), style = LifeType.small, color = colors.muted)
            is AskPhase.Failed -> {
                val message = when (phase.failure) {
                    CommandBar.Failure.OFFLINE -> R.string.command_offline
                    CommandBar.Failure.LOST -> R.string.command_lost
                    CommandBar.Failure.NOT_UNDERSTOOD -> R.string.command_not_understood
                    CommandBar.Failure.REFUSED -> R.string.command_failed
                }
                Text(stringResource(message), style = LifeType.body, color = colors.text)
                // The same request again: one that was done is answered as it was, never done twice.
                if (phase.failure != CommandBar.Failure.NOT_UNDERSTOOD) SecondaryButton(stringResource(R.string.command_ask_again), onClick = ask::again)
            }
            is AskPhase.Answered -> {
                val answer = phase.answer
                answer.understood?.takeIf { it.isNotBlank() && CommandBar.shows(answer) }?.let {
                    Text(stringResource(R.string.command_understood, it), style = LifeType.small, color = colors.muted)
                }
                answer.results.forEach { outcome ->
                    val line = outcomeLine(context, outcome, locale)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line, style = LifeType.body, color = if (outcome.ok) colors.text else colors.badge, modifier = Modifier.weight(1f))
                        val token = outcome.undo
                        if (token != null && outcome.ok) {
                            if (token in phase.undone) {
                                Text(stringResource(R.string.command_undone), style = LifeType.small, color = colors.muted)
                            } else {
                                UndoButton(stringResource(R.string.command_undo_label, line)) { ask.undo(token) { showMessage(undoFailed) } }
                            }
                        }
                    }
                }
                answer.cannot?.takeIf { it.isNotBlank() }?.let { Text(it, style = LifeType.body, color = colors.badge) }
                answer.question?.let { question ->
                    QuestionPart(
                        question,
                        answerText,
                        onAnswerText = { answerText = it },
                        onPick = { ask.pick(it, answer) },
                        onAnswer = {
                            ask.answer(answerText, answer)
                            answerText = ""
                        },
                    )
                }
            }
        }
    }
}

/**
 * A message written to the coach that the interpreter turned into something to show (#204):
 * the owner's [text] and the [ask] with what was done. It stands below the message that was
 * the last one then ([afterKey]; null: the conversation was empty).
 */
@Stable
class CoachAct(val id: Long, val afterKey: Long?, val text: String, val ask: Ask)
