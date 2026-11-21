package dev.lifeos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.COACH_ANSWER_MAX
import dev.lifeos.app.data.CoachMessage
import dev.lifeos.app.data.answerCoachQuestion
import dev.lifeos.app.data.skipCoachQuestion
import dev.lifeos.app.ui.components.LifeCard
import dev.lifeos.app.ui.components.LocalSnackbar
import dev.lifeos.app.ui.components.PillButton
import dev.lifeos.app.ui.components.PrimaryButton
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch

/** Where answering or skipping the coach's question stands. */
private enum class QuestionStep { OPEN, SENDING, SKIPPING, FAILED, NO_ANSWER }

/**
 * The coach's open question (#76), marked as coming from the coach: the owner answers in their
 * own words or skips it. The answer is logged on the computer first and then filed (a model
 * run of up to a few minutes); a skipped question is not asked again for that task for a while.
 */
@Composable
fun CoachQuestionCard(core: LifeOsCore, question: CoachMessage, onDone: () -> Unit) {
    val colors = LifeTheme.colors
    val scope = rememberCoroutineScope()
    val showMessage = LocalSnackbar.current
    var text by rememberSaveable(question.id) { mutableStateOf("") }
    var step by rememberSaveable(question.id) { mutableStateOf(QuestionStep.OPEN) }
    val answered = stringResource(R.string.coach_question_answered)
    val skipped = stringResource(R.string.question_skipped)
    val paused = stringResource(R.string.coach_question_paused)
    val gone = stringResource(R.string.coach_question_gone)

    fun finish(message: String) {
        core.coachQuestionHandled(question.id)
        showMessage(message)
        onDone()
    }

    /** Send the answer or the skip; a 404 means it was answered or skipped elsewhere meanwhile. */
    fun act(busy: QuestionStep, call: suspend () -> String) {
        step = busy
        scope.launch {
            step = try {
                finish(call())
                QuestionStep.OPEN
            } catch (error: ApiException) {
                when {
                    error.kind == ApiException.Kind.HTTP && error.status == 404 -> {
                        finish(gone)
                        QuestionStep.OPEN
                    }
                    // Handed over, but the filing run outlasted the wait: the answer is logged first.
                    error.kind == ApiException.Kind.UNCERTAIN && busy == QuestionStep.SENDING -> QuestionStep.NO_ANSWER
                    else -> QuestionStep.FAILED
                }
            }
        }
    }

    LifeCard {
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoachTag()
                Text(stringResource(R.string.question_label), style = LifeType.label, color = colors.text)
            }
            Text(text = question.text, style = LifeType.featured, color = colors.text)
            question.why?.takeIf { it.isNotBlank() }?.let { Text(it, style = LifeType.small, color = colors.muted) }
            val busy = step == QuestionStep.SENDING || step == QuestionStep.SKIPPING
            AnswerField(text = text, enabled = !busy) { text = it.take(COACH_ANSWER_MAX) }
            val note = when (step) {
                QuestionStep.SENDING -> R.string.coach_question_sending
                QuestionStep.FAILED -> R.string.coach_question_failed
                QuestionStep.NO_ANSWER -> R.string.coach_question_no_answer
                else -> null
            }
            if (note != null) {
                Text(stringResource(note), style = LifeType.small, color = colors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = stringResource(R.string.question_skip),
                    enabled = !busy,
                    onClick = {
                        act(QuestionStep.SKIPPING) { if (core.api.skipCoachQuestion().pausedUntil != null) paused else skipped }
                    },
                )
                PrimaryButton(
                    text = stringResource(R.string.coach_question_send),
                    enabled = !busy && text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        act(QuestionStep.SENDING) {
                            core.api.answerCoachQuestion(text.trim())
                            answered
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AnswerField(text: String, enabled: Boolean, onChange: (String) -> Unit) {
    val colors = LifeTheme.colors
    val hint = stringResource(R.string.coach_question_hint)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeDimens.minTouch)
            .background(colors.bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) Text(text = hint, style = LifeType.body, color = colors.muted)
        BasicTextField(
            value = text,
            onValueChange = onChange,
            enabled = enabled,
            textStyle = LifeType.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.primary),
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = hint },
        )
    }
}
