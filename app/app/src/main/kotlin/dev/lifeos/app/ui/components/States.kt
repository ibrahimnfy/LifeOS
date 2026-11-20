package dev.lifeos.app.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lifeos.app.LifeOsCore
import dev.lifeos.app.R
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.Endpoint
import dev.lifeos.app.data.Outcome
import dev.lifeos.app.data.OutboxItem
import dev.lifeos.app.data.Problem
import dev.lifeos.app.data.UiState
import dev.lifeos.app.data.UiStates
import dev.lifeos.app.ui.theme.LifeDimens
import dev.lifeos.app.ui.theme.LifeTheme
import dev.lifeos.app.ui.theme.LifeType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One endpoint for one screen: shows the saved copy at once, asks the computer, and asks
 * again when the app is unlocked, the connection returns or a queued write was sent.
 */
class LoadModel<T>(
    private val core: LifeOsCore,
    private val endpoint: Endpoint<T>,
    private val isEmpty: (T) -> Boolean,
) : ViewModel() {
    private val mutable = MutableStateFlow<UiState<T>>(UiState.Loading)
    private var job: Job? = null

    val state: StateFlow<UiState<T>> = mutable

    init {
        viewModelScope.launch {
            combine(core.pairing, core.locked, core.reload) { pairing, locked, _ -> (pairing != null) to locked }
                .collect { (paired, locked) -> load(paired, locked) }
        }
    }

    private var loadedAt = 0L

    fun refresh() = load(core.pairing.value != null, core.locked.value)

    /** The screen came back into view: ask again unless the answer is fresh. */
    fun refreshIfOld() {
        if (job?.isActive != true && System.currentTimeMillis() - loadedAt > FRESH_MILLIS) refresh()
    }

    /** Show data that arrived another way (for example a plan that was just prepared). */
    fun show(data: T) {
        job?.cancel()
        mutable.value = UiStates.of(true, null, Outcome.Success(data, System.currentTimeMillis()), isEmpty)
    }

    private fun load(paired: Boolean, locked: Boolean) {
        job?.cancel()
        if (!paired) {
            mutable.value = UiState.NotPaired
            return
        }
        if (locked) {
            // Nothing of the content is read or shown while the app is locked.
            mutable.value = UiState.Loading
            return
        }
        val saved = core.saved(endpoint)
        val current = mutable.value
        mutable.value = when {
            current is UiState.Content && !current.fromCache -> current.copy(refreshing = true)
            current is UiState.Empty && !current.fromCache -> current.copy(refreshing = true)
            else -> UiStates.of(true, saved, null, isEmpty)
        }
        job = viewModelScope.launch {
            val outcome = try {
                Outcome.Success(core.fetch(endpoint), System.currentTimeMillis())
            } catch (error: ApiException) {
                Outcome.Failure(error)
            }
            loadedAt = System.currentTimeMillis()
            mutable.value = UiStates.of(true, core.saved(endpoint) ?: saved, outcome, isEmpty)
        }
    }

    private companion object {
        const val FRESH_MILLIS = 30_000L
    }
}

@Composable
fun <T> rememberLoad(core: LifeOsCore, endpoint: Endpoint<T>, isEmpty: (T) -> Boolean = { false }): LoadModel<T> {
    @Suppress("UNCHECKED_CAST")
    val model = viewModel<ViewModel>(key = endpoint.key) { LoadModel(core, endpoint, isEmpty) } as LoadModel<T>
    LaunchedEffect(model) { model.refreshIfOld() }
    return model
}

val UiState<*>.isRefreshing: Boolean
    get() = (this is UiState.Content<*> && refreshing) || (this is UiState.Empty && refreshing)

/** A scrolling screen body with pull to refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    // The indicator shows only for a pull, not for the quiet reload behind a saved copy.
    var pulled by remember { mutableStateOf(false) }
    if (!refreshing) pulled = false
    PullToRefreshBox(
        isRefreshing = pulled && refreshing,
        onRefresh = {
            pulled = true
            onRefresh()
        },
        modifier = modifier,
        content = content,
    )
}

/**
 * The shared states of a screen part (docs/app-design.md, section 4): skeleton while loading,
 * the notice with the pair button, the error with "Retry", one sentence when empty, and the
 * content with a line saying that it is a saved copy and how old it is.
 */
@Composable
fun <T> StateHost(
    state: UiState<T>,
    emptyText: String,
    onRetry: () -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
    skeletonRows: Int = 3,
    savedLine: Boolean = true,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (state) {
            UiState.Loading -> SkeletonCard(skeletonRows)
            UiState.NotPaired -> NotPairedNotice(onPair)
            is UiState.Error -> ErrorNotice(state.problem, state.status, onRetry)
            is UiState.Empty -> {
                if (savedLine && state.fromCache && !state.refreshing) SavedCopyLine(state.savedAtMillis)
                EmptyLine(emptyText)
            }
            is UiState.Content -> {
                if (savedLine && state.fromCache && !state.refreshing) SavedCopyLine(state.savedAtMillis)
                content(state.data)
            }
        }
    }
}

@Composable
fun EmptyLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = LifeType.body,
        color = LifeTheme.colors.muted,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
    )
}

/** "Saved copy · updated 5 minutes ago": the computer did not answer, this is what was shown last. */
@Composable
fun SavedCopyLine(savedAtMillis: Long, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(state = dev.lifeos.app.ConnectionState.UNREACHABLE)
        Text(
            text = stringResource(R.string.state_saved_copy, relativeTime(savedAtMillis)),
            style = LifeType.small,
            color = colors.text,
        )
    }
}

@Composable
fun NotPairedNotice(onPair: () -> Unit, modifier: Modifier = Modifier) {
    LifeCard(modifier) {
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.state_not_paired_title), style = LifeType.sectionTitle, color = LifeTheme.colors.text)
            Text(stringResource(R.string.state_not_paired_text), style = LifeType.body, color = LifeTheme.colors.muted)
            PrimaryButton(text = stringResource(R.string.settings_pair), onClick = onPair)
        }
    }
}

@Composable
fun ErrorNotice(problem: Problem, status: Int, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    var details by remember { mutableStateOf(false) }
    LifeCard(modifier) {
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(
                    when (problem) {
                        Problem.UNREACHABLE -> R.string.error_unreachable
                        Problem.NO_ANSWER -> R.string.error_no_answer
                        Problem.SERVER -> R.string.error_server
                        Problem.NOT_SET_UP -> R.string.error_not_set_up
                        Problem.FOLDER_IN_REPOSITORY -> R.string.error_folder_in_repository
                    },
                ),
                style = LifeType.body,
                color = colors.text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            PrimaryButton(text = stringResource(R.string.action_retry), onClick = onRetry)
            if (status != 0) {
                if (details) {
                    Text(stringResource(R.string.error_details_status, status), style = LifeType.small, color = colors.muted)
                } else {
                    SecondaryButton(text = stringResource(R.string.action_details), onClick = { details = true })
                }
            }
        }
    }
}

/** Grey placeholders in the shape of a card with rows; silent for screen readers except "Loading". */
@Composable
fun SkeletonCard(rows: Int, modifier: Modifier = Modifier) {
    val colors = LifeTheme.colors
    val loading = stringResource(R.string.state_loading)
    LifeCard(modifier.clearAndSetSemantics { contentDescription = loading }) {
        Box(
            Modifier
                .padding(vertical = 8.dp)
                .size(width = 120.dp, height = 14.dp)
                .background(colors.surface2, RoundedCornerShape(7.dp)),
        )
        repeat(rows) {
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(colors.surface2, RoundedCornerShape(20.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.8f)
                            .height(14.dp)
                            .background(colors.surface2, RoundedCornerShape(7.dp)),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.45f)
                            .height(12.dp)
                            .background(colors.surface2, RoundedCornerShape(6.dp)),
                    )
                }
            }
        }
    }
}

/**
 * Queued writes of the given kinds that need the owner's eye: "check" (sent, no answer:
 * send again or discard) and "failed" (the computer refused it).
 */
@Composable
fun OutboxNotices(core: LifeOsCore, kinds: Set<OutboxItem.Kind>, modifier: Modifier = Modifier) {
    val items by core.outbox.items.collectAsStateWithLifecycle()
    val locked by core.locked.collectAsStateWithLifecycle()
    val colors = LifeTheme.colors
    val problems = if (locked) emptyList() else items.filter { it.kind in kinds && it.state != OutboxItem.State.WAITING }
    if (problems.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        problems.forEach { item ->
            LifeCard {
                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(if (item.state == OutboxItem.State.CHECK) R.string.outbox_check_title else R.string.outbox_failed_title),
                        style = LifeType.label,
                        color = colors.text,
                    )
                    Text(item.event?.title ?: item.text, style = LifeType.rowTitle, color = colors.text)
                    Text(
                        stringResource(if (item.state == OutboxItem.State.CHECK) R.string.outbox_check_text else R.string.outbox_failed_text),
                        style = LifeType.small,
                        color = colors.muted,
                    )
                    val canResend = item.state == OutboxItem.State.FAILED || core.outbox.settled(item)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = stringResource(R.string.outbox_send_again),
                            enabled = canResend,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (core.outbox.sendAgain(item.id)) core.drainOutbox()
                            },
                        )
                        SecondaryButton(
                            text = stringResource(R.string.outbox_discard),
                            danger = true,
                            modifier = Modifier.weight(1f),
                            onClick = { core.outbox.discard(item.id) },
                        )
                    }
                    if (!canResend) {
                        Text(stringResource(R.string.outbox_wait_hint), style = LifeType.small, color = colors.muted)
                    }
                }
            }
        }
    }
}

/** The small "waiting to sync" label of a row that has not reached the computer yet. */
@Composable
fun WaitingLabel(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.outbox_waiting), style = LifeType.small, color = LifeTheme.colors.muted, modifier = modifier)
}

// ---------------------------------------------------------------- formatting

@Composable
fun appLocale(): Locale = LocalConfiguration.current.locales[0]

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

/** "Fri 15 Jan", in the app's language. */
fun dayLabel(day: LocalDate, locale: Locale): String = day.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

/** A moment the site gave as seconds, in the phone's time zone: "15 Jan, 13:00". */
fun momentLabel(epochSeconds: Long, locale: Locale): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", locale))

/** When an ISO time such as "2027-01-15T17:00:00Z" was, as "5 minutes ago"; null if unreadable. */
fun relativeIso(iso: String?): String? = try {
    iso?.let { relativeTime(Instant.parse(it).toEpochMilli()) }
} catch (_: Exception) {
    null
}

@Composable
fun dueLabel(due: String?, locale: Locale = appLocale()): String? {
    val day = dev.lifeos.app.data.Dates.parse(due) ?: return null
    val today = today()
    return when (day) {
        today -> stringResource(R.string.day_today)
        today.plusDays(1) -> stringResource(R.string.day_tomorrow)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> dayLabel(day, locale)
    }
}

/** Shows a short message at the bottom of the screen; provided by the app's root. */
val LocalSnackbar = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Shows a short message with "Undo" for as long as undoing is possible: (message, onUndo). */
val LocalUndo = staticCompositionLocalOf<(String, () -> Unit) -> Unit> { { _, _ -> } }
