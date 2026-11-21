package dev.lifeos.app.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When to send what waits as soon as the phone is online again (#165), without the Android
 * framework (so it can be tested on its own).
 *
 * - [networkChanged] reports whether a validated network (one the system checked can reach
 *   the internet) is in use. Only the step from "none" to "validated" starts a run; repeated
 *   reports of the same state (the system sends many) change nothing.
 * - [request] asks for a run directly (the background job does, when the system started it
 *   because the network came back).
 * - Runs never overlap: what is asked for while one runs becomes a single follow-up run, which
 *   starts [minGapMillis] after the previous one ended. A flapping connection therefore costs
 *   at most one run per gap. A run is never cancelled half-way: the outbox would have to treat
 *   an item on its way as "maybe arrived" (see `Outbox.drain`).
 * - A run that fails does not stop later ones.
 */
class SyncTrigger(
    scope: CoroutineScope,
    online: Boolean,
    private val minGapMillis: Long = MIN_GAP_MILLIS,
    private val run: suspend () -> Unit,
) {
    private var online: Boolean = online
    private val asked = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in asked) {
                try {
                    run()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The next network change or request tries again.
                }
                delay(minGapMillis)
            }
        }
    }

    /** The default network is now validated ([validated] true) or there is none usable (false). */
    fun networkChanged(validated: Boolean) {
        val cameBack = synchronized(this) {
            val before = online
            online = validated
            validated && !before
        }
        if (cameBack) request()
    }

    /** Ask for a run now (or, while one runs, right after it). */
    fun request() {
        asked.trySend(Unit)
    }

    companion object {
        /** The least time between the end of one run and the start of the next. */
        const val MIN_GAP_MILLIS = 3_000L
    }
}
