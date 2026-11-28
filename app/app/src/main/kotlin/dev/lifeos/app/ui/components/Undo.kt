package dev.lifeos.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's one Undo bar (#207): after deleting, completing, marking, and after every action
 * the command bar (or the coach, or the home's "Ask") did on the computer, one line says what
 * happened and offers "Undo" for [visibleMillis]. Screens reach it through [LocalUndo] (their
 * own way back) and [LocalUndoCommand] (an undo token of the computer, `POST /command/undo`).
 *
 * The newest action is shown at once: a bar that is still up steps aside. Its own Undo is then
 * no longer offered, as with any bar whose time is over.
 */
class UndoBar(
    private val host: SnackbarHostState,
    private val scope: CoroutineScope,
    private val undoLabel: String,
    private val visibleMillis: Long,
) {
    /** A plain line, without Undo. */
    fun say(message: String) {
        scope.launch { host.showSnackbar(message) }
    }

    /** [message] with "Undo"; [onUndo] runs when the owner taps it in time. */
    fun offer(message: String, onUndo: () -> Unit) {
        host.currentSnackbarData?.dismiss()
        scope.launch {
            val result = withTimeoutOrNull(visibleMillis) {
                host.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    /**
     * [message] with "Undo" for something the computer did and gave an undo token for (a
     * result of the command bar): [undo] takes it back there and answers whether it did;
     * [failed] is said when it could not (too late, or the computer cannot be reached).
     */
    fun offerCommand(message: String, token: String, failed: String, undo: suspend (String) -> Boolean) {
        offer(message) {
            scope.launch { if (!undo(token)) host.showSnackbar(failed) }
        }
    }
}

/**
 * Shows a line with "Undo" for an action of the computer: (message, undo token). Provided by
 * the app's root; the command bar, the coach conversation and the home's "Ask" use it for
 * the `undo` of their results.
 */
val LocalUndoCommand = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }
