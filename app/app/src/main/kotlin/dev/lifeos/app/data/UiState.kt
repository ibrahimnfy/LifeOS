package dev.lifeos.app.data

/** What a screen shows for one piece of data (docs/app-design.md, section 4). */
sealed interface UiState<out T> {
    /** Nothing to show yet: skeleton placeholders. */
    data object Loading : UiState<Nothing>

    /**
     * [fromCache]: this is the saved copy from [savedAtMillis] (the computer has not answered,
     * or not yet). [refreshing]: a request is on its way.
     */
    data class Content<T>(val data: T, val fromCache: Boolean, val savedAtMillis: Long, val refreshing: Boolean = false) :
        UiState<T>

    /** The answer holds nothing to list. */
    data class Empty(val fromCache: Boolean, val savedAtMillis: Long, val refreshing: Boolean = false) : UiState<Nothing>

    /** This phone is not paired (or no longer accepted): a notice with a button to settings. */
    data object NotPaired : UiState<Nothing>

    /** No answer and no saved copy: a plain message and "Retry". */
    data class Error(val problem: Problem, val status: Int = 0) : UiState<Nothing>
}

enum class Problem { UNREACHABLE, NO_ANSWER, SERVER, NOT_SET_UP, FOLDER_IN_REPOSITORY }

/** When the list on screen was received (0 when there is none): what the phone added later is put into it. */
val UiState<*>.listAtMillis: Long
    get() = when (this) {
        is UiState.Content<*> -> savedAtMillis
        is UiState.Empty -> savedAtMillis
        else -> 0L
    }

/** What the last request for an endpoint ended with; null while it is still running. */
sealed interface Outcome<out T> {
    data class Success<T>(val data: T, val atMillis: Long) : Outcome<T>
    data class Failure(val error: ApiException) : Outcome<Nothing>
}

object UiStates {
    /** Bridge error code: the folder sits inside another git repository (capable mode). */
    const val FOLDER_IN_REPOSITORY = "folder_inside_repository"

    /**
     * The one rule that turns "paired?", the saved copy and the last request into a screen state:
     * a fresh answer wins; otherwise the saved copy is shown (marked as such); only without
     * either does the screen show loading or an error.
     */
    fun <T> of(paired: Boolean, saved: Pair<T, Long>?, outcome: Outcome<T>?, isEmpty: (T) -> Boolean): UiState<T> {
        if (!paired) return UiState.NotPaired
        return when (outcome) {
            is Outcome.Success ->
                if (isEmpty(outcome.data)) UiState.Empty(false, outcome.atMillis) else UiState.Content(outcome.data, false, outcome.atMillis)
            null -> when {
                saved == null -> UiState.Loading
                isEmpty(saved.first) -> UiState.Empty(true, saved.second, refreshing = true)
                else -> UiState.Content(saved.first, true, saved.second, refreshing = true)
            }
            is Outcome.Failure -> when {
                outcome.error.kind == ApiException.Kind.NOT_PAIRED || outcome.error.kind == ApiException.Kind.REFUSED -> UiState.NotPaired
                saved == null -> UiState.Error(problemOf(outcome.error), outcome.error.status)
                isEmpty(saved.first) -> UiState.Empty(true, saved.second)
                else -> UiState.Content(saved.first, true, saved.second)
            }
        }
    }

    fun problemOf(error: ApiException): Problem = when (error.kind) {
        ApiException.Kind.NOT_SENT, ApiException.Kind.NOT_PAIRED, ApiException.Kind.REFUSED -> Problem.UNREACHABLE
        ApiException.Kind.UNCERTAIN -> Problem.NO_ANSWER
        ApiException.Kind.HTTP -> when {
            // The capable mode refuses a folder inside another git repository (bridge #139).
            error.code == FOLDER_IN_REPOSITORY -> Problem.FOLDER_IN_REPOSITORY
            error.status == 503 -> Problem.NOT_SET_UP
            else -> Problem.SERVER
        }
    }
}
