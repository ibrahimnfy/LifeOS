package dev.lifeos.app.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncTriggerTest {
    private class Runs {
        var started = 0
        var running = 0
        var mostAtOnce = 0
        var startedAt = mutableListOf<Long>()
    }

    /** A trigger whose run takes [runMillis] of virtual time and records what happened. */
    private fun TestScope.trigger(online: Boolean, runMillis: Long = 0, fail: Boolean = false): Pair<SyncTrigger, Runs> {
        val runs = Runs()
        val trigger = SyncTrigger(backgroundScope, online, minGapMillis = 3_000) {
            runs.started++
            runs.startedAt += testScheduler.currentTime
            runs.running++
            runs.mostAtOnce = maxOf(runs.mostAtOnce, runs.running)
            try {
                delay(runMillis)
                if (fail) throw IllegalStateException("the computer did not answer")
            } finally {
                runs.running--
            }
        }
        runCurrent()
        return trigger to runs
    }

    @Test
    fun `the network coming back starts a run at once`() = runTest {
        val (trigger, runs) = trigger(online = false)
        trigger.networkChanged(true)
        runCurrent()
        assertEquals(1, runs.started)
        assertEquals(listOf(0L), runs.startedAt)
    }

    @Test
    fun `nothing starts while the phone stays online or offline`() = runTest {
        val (trigger, runs) = trigger(online = true)
        // The system reports the same network many times (signal strength, bandwidth).
        repeat(5) { trigger.networkChanged(true) }
        advanceTimeBy(60_000)
        assertEquals(0, runs.started)

        val (offline, offlineRuns) = trigger(online = false)
        repeat(5) { offline.networkChanged(false) }
        advanceTimeBy(60_000)
        assertEquals(0, offlineRuns.started)
    }

    @Test
    fun `losing the network starts nothing, getting it back does`() = runTest {
        val (trigger, runs) = trigger(online = true)
        trigger.networkChanged(false)
        advanceTimeBy(10_000)
        assertEquals(0, runs.started)
        trigger.networkChanged(true)
        runCurrent()
        assertEquals(1, runs.started)
    }

    @Test
    fun `a flapping connection never starts parallel runs and adds at most one follow-up`() = runTest {
        val (trigger, runs) = trigger(online = false, runMillis = 5_000)
        trigger.networkChanged(true)
        runCurrent()
        // Ten drops and returns while the first run is still sending.
        repeat(10) {
            advanceTimeBy(200)
            trigger.networkChanged(false)
            trigger.networkChanged(true)
        }
        advanceTimeBy(60_000)
        assertEquals(1, runs.mostAtOnce)
        assertEquals(2, runs.started)
        // The follow-up waits for the end of the first run (5 s) plus the gap (3 s).
        assertEquals(listOf(0L, 8_000L), runs.startedAt)
    }

    @Test
    fun `a return right after a finished run waits for the gap`() = runTest {
        val (trigger, runs) = trigger(online = false)
        trigger.networkChanged(true)
        runCurrent()
        advanceTimeBy(1_000)
        trigger.networkChanged(false)
        trigger.networkChanged(true)
        advanceTimeBy(1_000)
        assertEquals(1, runs.started)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(0L, 3_000L), runs.startedAt)
    }

    @Test
    fun `the background job's request joins a run already going on`() = runTest {
        val (trigger, runs) = trigger(online = false, runMillis = 5_000)
        trigger.networkChanged(true)
        runCurrent()
        trigger.request()
        trigger.request()
        advanceTimeBy(60_000)
        assertEquals(1, runs.mostAtOnce)
        assertEquals(2, runs.started)
    }

    @Test
    fun `a request while online starts a run`() = runTest {
        val (trigger, runs) = trigger(online = true)
        trigger.request()
        runCurrent()
        assertEquals(1, runs.started)
    }

    @Test
    fun `a failing run does not stop later ones`() = runTest {
        val (trigger, runs) = trigger(online = false, fail = true)
        trigger.networkChanged(true)
        advanceTimeBy(5_000)
        trigger.networkChanged(false)
        trigger.networkChanged(true)
        runCurrent()
        assertEquals(2, runs.started)
    }

    @Test
    fun `a run is not cut off by the network dropping again`() = runTest {
        val finished = CompletableDeferred<Unit>()
        val trigger = SyncTrigger(backgroundScope, online = false, minGapMillis = 3_000) {
            delay(5_000)
            finished.complete(Unit)
        }
        runCurrent()
        trigger.networkChanged(true)
        advanceTimeBy(1_000)
        trigger.networkChanged(false)
        advanceTimeBy(5_000)
        assertEquals(true, finished.isCompleted)
    }
}
