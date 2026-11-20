package dev.lifeos.app.ui.screens

import android.content.ActivityNotFoundException
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.ConnectionState
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.activity.DayUsage
import dev.lifeos.app.activity.UsageReader
import dev.lifeos.app.activity.UsageView
import dev.lifeos.app.ui.components.*
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.launch
import java.time.LocalDate

/** "2 h 05 min" or "45 min". */
@Composable
fun durationLabel(minutes: Int): String =
    if (minutes >= 60) stringResource(R.string.usage_hm, minutes / 60, minutes % 60) else stringResource(R.string.usage_m, minutes)

/**
 * Activity: screen time from the system's usage records (usage access), drawn from what is
 * stored on the phone, so it works offline. Sleep comes with its own issue.
 */
@Composable
fun ActivityScreen(core: LifeOsCore, onBack: () -> Unit, onConnectionClick: () -> Unit) {
    val colors = LifeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val noSettings = stringResource(R.string.activity_access_missing)
    val locked by core.locked.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(core.usageAccess()) }
    val version by core.activityChanged.collectAsStateWithLifecycle()
    val syncOn by core.settings.usageSync.collectAsStateWithLifecycle(initialValue = true)
    val connection by core.connection.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableIntStateOf(7) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }

    // Coming back from the system settings: look again whether access was granted, and measure.
    LifecycleResumeEffect(Unit) {
        access = core.usageAccess()
        if (access && !core.locked.value) scope.launch { core.measureUsage() }
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = stringResource(R.string.more_activity), onBack = onBack, onConnectionClick = onConnectionClick)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LifeDimens.pad)
                .padding(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Like every other screen: nothing of the content is read or drawn while the app is locked.
            if (locked) return@Column
            if (!access) {
                LifeCard {
                    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.activity_access_title), style = LifeType.sectionTitle, color = colors.text)
                        Text(stringResource(R.string.activity_access_text), style = LifeType.body, color = colors.text)
                        Text(stringResource(R.string.activity_access_hint), style = LifeType.small, color = colors.muted)
                        PrimaryButton(
                            text = stringResource(R.string.activity_access_button),
                            onClick = {
                                val opened = UsageReader.settingsIntents(context).any { intent ->
                                    try {
                                        context.startActivity(intent)
                                        true
                                    } catch (_: ActivityNotFoundException) {
                                        false
                                    }
                                }
                                if (!opened) snackbar(noSettings)
                            },
                        )
                    }
                }
                return@Column
            }

            val days = remember(version) { core.activity.days() }
            val today = LocalDate.now()
            val window = UsageView.window(days, today, range)
            val todayUsage = window.last().second

            LifeCard {
                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.activity_today), style = LifeType.label, color = colors.muted)
                    Text(durationLabel(todayUsage?.screenMinutes ?: 0), style = LifeType.screenTitle, color = colors.text)
                    Text(
                        pluralStringResource(R.plurals.activity_unlocks, todayUsage?.unlocks ?: 0, todayUsage?.unlocks ?: 0),
                        style = LifeType.small,
                        color = colors.muted,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(label = stringResource(R.string.activity_range_week), selected = range == 7) { range = 7 }
                Chip(label = stringResource(R.string.activity_range_month), selected = range == 30) { range = 30 }
            }

            val average = UsageView.average(window)
            val selected = UsageView.selected(window, chosen?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
            LifeCard {
                SectionHeader(
                    title = stringResource(R.string.activity_per_day),
                    note = average?.let { stringResource(R.string.activity_average, durationLabel(it)) },
                )
                if (window.all { it.second == null }) {
                    EmptyLine(stringResource(R.string.activity_no_data))
                } else {
                    UsageBars(
                        window = window,
                        selected = selected?.date,
                        average = average,
                        description = pluralStringResource(R.plurals.activity_chart_description, range, range),
                        onSelect = { chosen = it.toString() },
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                        Text(dayLabel(window.first().first, appLocale()), style = LifeType.small, color = colors.muted, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.activity_today), style = LifeType.small, color = colors.muted)
                    }
                }
            }

            if (selected != null) DayCard(selected)

            SyncCard(
                core = core,
                syncOn = syncOn,
                connected = connection == ConnectionState.CONNECTED,
                version = version,
                onToggle = { on -> scope.launch { core.settings.setUsageSync(on) } },
            )
        }
    }
}

/**
 * Screen time per day: one bar per day on a shared baseline, the chosen day in full colour,
 * the average as a dashed line. Tapping a bar chooses its day; the day card below is the
 * readable form of the same numbers.
 */
@Composable
private fun UsageBars(
    window: List<Pair<LocalDate, DayUsage?>>,
    selected: String?,
    average: Int?,
    description: String,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = LifeTheme.colors
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .pointerInput(window) {
                detectTapGestures { offset ->
                    val index = (offset.x / (size.width.toFloat() / window.size)).toInt().coerceIn(0, window.size - 1)
                    if (window[index].second != null) onSelect(window[index].first)
                }
            }
            .semantics { contentDescription = description },
    ) {
        val top = 6.dp.toPx()
        val chartHeight = size.height - top
        val most = maxOf(window.maxOf { it.second?.screenMinutes ?: 0 }, 60)
        val slot = size.width / window.size
        val barWidth = (slot - 2.dp.toPx()).coerceIn(2.dp.toPx(), 22.dp.toPx())
        val radius = minOf(4.dp.toPx(), barWidth / 2)
        window.forEachIndexed { index, (day, usage) ->
            val minutes = usage?.screenMinutes ?: return@forEachIndexed
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
            drawPath(bar, if (day.toString() == selected) colors.primary else colors.primary.copy(alpha = 0.4f))
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

/** One day in words and bars: total, unlocks, and the apps used for a minute or more. */
@Composable
private fun DayCard(day: DayUsage) {
    val colors = LifeTheme.colors
    val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
    val most = day.apps.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
    LifeCard {
        SectionHeader(
            title = date?.let { dayLabel(it, appLocale()) } ?: day.date,
            note = listOf(durationLabel(day.screenMinutes), pluralStringResource(R.plurals.activity_unlocks, day.unlocks, day.unlocks)).joinToString(" · "),
        )
        if (day.apps.isEmpty()) EmptyLine(stringResource(R.string.activity_apps_none))
        day.apps.take(TOP_APPS).forEach { app ->
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.label, style = LifeType.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(durationLabel(app.minutes), style = LifeType.small, color = colors.muted)
                }
                Box(
                    Modifier
                        .fillMaxWidth(app.minutes.toFloat() / most)
                        .height(6.dp)
                        .background(colors.primary, RoundedCornerShape(3.dp)),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private const val TOP_APPS = 10

/** Sending to the computer: the switch, what is sent, and how far it got. */
@Composable
private fun SyncCard(core: LifeOsCore, syncOn: Boolean, connected: Boolean, version: Int, onToggle: (Boolean) -> Unit) {
    val colors = LifeTheme.colors
    val waiting = remember(version) { core.activity.days().count { it.complete && !it.uploaded } }
    val last = remember(version) { core.activity.lastSyncMillis() }
    LifeCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LifeDimens.minTouch)
                .toggleable(value = syncOn, role = Role.Switch, onValueChange = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.activity_sync_switch), style = LifeType.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
            Switch(checked = syncOn, onCheckedChange = null)
        }
        Text(stringResource(R.string.activity_sync_text), style = LifeType.small, color = colors.muted)
        if (syncOn) {
            val status = listOfNotNull(
                if (last > 0) stringResource(R.string.activity_sync_last, relativeTime(last)) else stringResource(R.string.activity_sync_never),
                if (waiting > 0) pluralStringResource(R.plurals.activity_sync_waiting, waiting, waiting) else null,
            ).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                Text(
                    status,
                    style = LifeType.small,
                    color = colors.text,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                PillButton(text = stringResource(R.string.activity_sync_now), enabled = connected, onClick = { core.syncActivity(force = true) })
            }
        }
    }
}
