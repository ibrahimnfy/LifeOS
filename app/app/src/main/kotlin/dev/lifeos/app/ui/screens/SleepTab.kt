package dev.lifeos.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.activity.Night
import dev.lifeos.app.activity.SleepPrompt
import dev.lifeos.app.activity.SleepView
import dev.lifeos.app.data.Tone
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import dev.lifeos.app.ui.theme.ToneAlpha
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The colour of sleep: violet, the night-time tone. */
private val SLEEP_TONE = Tone.VIOLET

/** A night that the editor is open for: its morning, and the times to start from. */
private data class Editing(val date: String, val bed: String, val wake: String)

/**
 * Sleep: last night, a 7 or 30 night chart (entered nights solid, estimated ones hatched,
 * the average dashed), the chosen night with "Enter" or "Correct", and the morning question.
 */
@Composable
fun SleepTab(core: LifeOsCore, version: Int, access: Boolean) {
    val colors = LifeTheme.colors
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableIntStateOf(7) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    val nights = remember(version) { core.activity.nights() }
    val today = LocalDate.now()
    val window = SleepView.window(nights, today, range)
    val average = SleepView.average(window)
    val chosenDay = chosen?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
    val shown = window.firstOrNull { it.first == chosenDay } ?: (today to SleepView.lastNight(nights, today))

    NightCard(
        date = shown.first,
        night = shown.second,
        isLastNight = shown.first == today,
        onEdit = {
            val night = shown.second
            editing = Editing(shown.first.toString(), night?.bed ?: "23:30", night?.wake ?: "07:00")
        },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(label = stringResource(R.string.activity_range_week), selected = range == 7) { range = 7 }
        Chip(label = stringResource(R.string.activity_range_month), selected = range == 30) { range = 30 }
    }

    LifeCard {
        SectionHeader(
            title = stringResource(R.string.sleep_per_night),
            note = average?.let { stringResource(R.string.activity_average, durationLabel(it)) },
        )
        if (window.all { it.second == null }) {
            EmptyLine(stringResource(R.string.sleep_no_data))
        } else {
            SleepBars(
                window = window,
                selected = shown.first,
                average = average,
                description = pluralStringResource(R.plurals.sleep_chart_description, range, range),
                onSelect = { chosen = it.toString() },
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(dayLabel(window.first().first, appLocale()), style = LifeType.small, color = colors.muted, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.activity_today), style = LifeType.small, color = colors.muted)
            }
            Legend(Modifier.padding(top = 8.dp, bottom = 6.dp))
        }
    }

    if (!access) UsageAccessCard(short = true)

    MorningQuestionCard(core)

    editing?.let { night ->
        NightEditor(
            night = night,
            onDismiss = { editing = null },
            onSave = { bed, wake ->
                editing = null
                chosen = night.date
                scope.launch { core.enterNight(night.date, bed, wake) }
            },
        )
    }
}

/** One night in words: bed time, wake time, length, whether it is estimated, and the way to enter or correct it. */
@Composable
private fun NightCard(date: LocalDate, night: Night?, isLastNight: Boolean, onEdit: () -> Unit) {
    val colors = LifeTheme.colors
    val ink = colors.tone(SLEEP_TONE).ink
    LifeCard {
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (isLastNight) stringResource(R.string.sleep_last_night) else stringResource(R.string.sleep_night_of, dayLabel(date, appLocale())),
                style = LifeType.label,
                color = colors.muted,
                modifier = Modifier.semantics { heading() },
            )
            if (night == null) {
                Text(stringResource(R.string.sleep_not_entered), style = LifeType.body, color = colors.text)
            } else {
                Text(durationLabel(night.minutes), style = LifeType.screenTitle, color = colors.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sleep_times, night.bed, night.wake), style = LifeType.small, color = colors.muted)
                    if (night.estimated) {
                        Text(
                            stringResource(R.string.sleep_estimated),
                            style = LifeType.label,
                            color = ink,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(colors.tone(SLEEP_TONE).second, RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (night == null || night.estimated) {
                PrimaryButton(text = stringResource(if (isLastNight) R.string.sleep_enter_last_night else R.string.sleep_enter), onClick = onEdit)
            } else {
                SecondaryButton(text = stringResource(R.string.sleep_correct), onClick = onEdit)
            }
        }
    }
}

/**
 * Hours slept per night on a shared baseline: entered nights solid, estimated ones hatched
 * (so the difference is not told by colour alone), the chosen night stronger, the average as a
 * dashed line. Tapping a night chooses it; the card above is its readable form.
 */
@Composable
private fun SleepBars(
    window: List<Pair<LocalDate, Night?>>,
    selected: LocalDate,
    average: Int?,
    description: String,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = LifeTheme.colors
    val tone = colors.tone(SLEEP_TONE)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .pointerInput(window) {
                detectTapGestures { offset ->
                    val index = (offset.x / (size.width.toFloat() / window.size)).toInt().coerceIn(0, window.size - 1)
                    onSelect(window[index].first)
                }
            }
            .semantics { contentDescription = description },
    ) {
        val top = 6.dp.toPx()
        val chartHeight = size.height - top
        val most = maxOf(window.maxOf { it.second?.minutes ?: 0 }, 8 * 60)
        val slot = size.width / window.size
        val barWidth = (slot - 2.dp.toPx()).coerceIn(2.dp.toPx(), 22.dp.toPx())
        val radius = minOf(4.dp.toPx(), barWidth / 2)
        window.forEachIndexed { index, (day, night) ->
            val minutes = night?.minutes ?: return@forEachIndexed
            val height = (minutes.toFloat() / most * chartHeight).coerceAtLeast(2.dp.toPx())
            val left = index * slot + (slot - barWidth) / 2
            val bar = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left,
                        top = size.height - height,
                        right = left + barWidth,
                        bottom = size.height,
                        topLeftCornerRadius = CornerRadius(radius),
                        topRightCornerRadius = CornerRadius(radius),
                    ),
                )
            }
            val strong = day == selected
            if (night.estimated) {
                drawPath(bar, tone.second)
                hatch(bar, tone.ink.copy(alpha = if (strong) 1f else ToneAlpha.LINE))
            } else {
                drawPath(bar, if (strong) tone.ink else tone.ink.copy(alpha = 0.45f))
            }
        }
        drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        if (average != null && average > 0) {
            val y = size.height - average.toFloat() / most * chartHeight
            drawLine(
                colors.muted,
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
    }
}

/** Diagonal lines inside [shape]: the mark of an estimated night. */
private fun DrawScope.hatch(shape: Path, color: Color) {
    clipPath(shape) {
        val step = 5.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.5.dp.toPx())
            x += step
        }
    }
}

/** What solid and hatched bars mean. */
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    val tone = colors.tone(SLEEP_TONE)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(tone.ink, RoundedCornerShape(3.dp)))
            Text(stringResource(R.string.sleep_legend_entered), style = LifeType.small, color = colors.muted, modifier = Modifier.padding(start = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))) {
                drawRect(tone.second)
                val square = Path().apply { addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
                hatch(square, tone.ink)
            }
            Text(stringResource(R.string.sleep_estimated), style = LifeType.small, color = colors.muted, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** The morning question: on or off, its time, and (Android 13) the permission to show it. */
@Composable
private fun MorningQuestionCard(core: LifeOsCore) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val on by core.settings.sleepPrompt.collectAsStateWithLifecycle(initialValue = true)
    val time by core.settings.sleepPromptTime.collectAsStateWithLifecycle(initialValue = "05:00")
    var picking by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(SleepPrompt.mayNotify(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> allowed = granted }
    LifeCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LifeDimens.minTouch)
                .toggleable(value = on, role = Role.Switch) { value ->
                    scope.launch {
                        core.settings.setSleepPrompt(value)
                        core.scheduleSleepPrompt()
                    }
                    if (value && !allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.sleep_prompt_switch), style = LifeType.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = null)
        }
        Text(stringResource(R.string.sleep_prompt_explain, time), style = LifeType.small, color = colors.muted)
        if (on) {
            Row(Modifier.padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(text = stringResource(R.string.sleep_prompt_time, time), onClick = { picking = true }, modifier = Modifier.weight(1f))
                if (!allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    SecondaryButton(
                        text = stringResource(R.string.sleep_prompt_allow),
                        onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    if (picking) {
        ClockPicker(time, onDismiss = { picking = false }, dismissLabel = R.string.action_cancel) { picked ->
            picking = false
            scope.launch {
                core.settings.setSleepPromptTime(picked)
                core.scheduleSleepPrompt()
            }
        }
    }
}

/** Enter or correct one night: bed time and wake time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightEditor(night: Editing, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val colors = LifeTheme.colors
    var bed by rememberSaveable { mutableStateOf(night.bed) }
    var wake by rememberSaveable { mutableStateOf(night.wake) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val date = runCatching { LocalDate.parse(night.date) }.getOrNull()
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
                .padding(start = LifeDimens.pad, end = LifeDimens.pad, bottom = LifeDimens.pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.sleep_editor_title, date?.let { dayLabel(it, appLocale()) } ?: night.date),
                style = LifeType.sectionTitle,
                color = colors.text,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(text = stringResource(R.string.sleep_bed, bed), onClick = { picking = "bed" }, modifier = Modifier.weight(1f))
                SecondaryButton(text = stringResource(R.string.sleep_wake, wake), onClick = { picking = "wake" }, modifier = Modifier.weight(1f))
            }
            if (SleepView.validEntry(bed, wake)) {
                Text(durationLabel(Night(night.date, bed, wake, estimated = false).minutes), style = LifeType.small, color = colors.muted)
            }
            PrimaryButton(text = stringResource(R.string.capture_save), enabled = SleepView.validEntry(bed, wake), onClick = { onSave(bed, wake) })
        }
    }
    when (picking) {
        "bed" -> ClockPicker(bed, onDismiss = { picking = null }, dismissLabel = R.string.action_cancel) {
            bed = it
            picking = null
        }
        "wake" -> ClockPicker(wake, onDismiss = { picking = null }, dismissLabel = R.string.action_cancel) {
            wake = it
            picking = null
        }
    }
}
