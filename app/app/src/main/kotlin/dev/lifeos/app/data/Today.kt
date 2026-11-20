package dev.lifeos.app.data

import kotlinx.serialization.json.JsonElement

/**
 * What pulling down on Today asks again: the coach's last plan and the agenda (which carries
 * today's and the overdue tasks). Reading only: a pull never starts a model run.
 */
object TodayReload {
    val endpoints: List<Endpoint<*>> = listOf(Endpoints.plan, Endpoints.agenda)

    /** Ask every endpoint again; [load] is how the screen asks (its loaders). */
    fun reload(load: (Endpoint<*>) -> Unit) = endpoints.forEach(load)
}

/** The explicit "Rerun" of the coach card: one model run on the computer. */
object CoachRerun {
    sealed interface State {
        data object Idle : State
        data object Running : State
        data object Done : State

        /** The computer refused: a plan was prepared less than ten minutes ago. */
        data object TooSoon : State

        /** Nothing left the phone: the computer is not reachable. */
        data object Unreachable : State

        /** The run was handed over but no answer came in time; it may still finish. */
        data object NoAnswer : State

        /** The computer could not prepare a plan; the previous one stays. */
        data object Failed : State
    }

    /** Run it; a new plan is handed to [save] before [State.Done] is returned. */
    suspend fun run(api: Api, save: (JsonElement) -> Unit): State = try {
        val (_, raw) = api.refreshPlan()
        save(raw)
        State.Done
    } catch (error: ApiException) {
        when {
            error.kind == ApiException.Kind.HTTP && error.status == 429 -> State.TooSoon
            error.kind == ApiException.Kind.UNCERTAIN -> State.NoAnswer
            error.kind == ApiException.Kind.HTTP -> State.Failed
            else -> State.Unreachable
        }
    }
}
