package dev.lifeos.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L
    private val sent = mutableListOf<String>()
    private val failures = mutableMapOf<String, ArrayDeque<ApiException>>()
    private val file get() = File(folder.root, "outbox/outbox.json")

    private fun outbox(sendDelay: Long = 0) = Outbox(OutboxStore(file), { now }) { item ->
        delay(sendDelay)
        sent += item.id
        failures[item.id]?.removeFirstOrNull()?.let { throw it }
    }

    private fun note(id: String) = OutboxItem(id, OutboxItem.Kind.NOTE, now, text = "Sample $id")
    private fun tick(id: String) = OutboxItem(id, OutboxItem.Kind.COMPLETE, now, taskId = "t-$id")
    private fun fail(id: String, vararg errors: ApiException) {
        failures[id] = ArrayDeque(errors.toList())
    }

    private val notSent = ApiException(ApiException.Kind.NOT_SENT)
    private val uncertain = ApiException(ApiException.Kind.UNCERTAIN)
    private fun http(status: Int, code: String? = null) = ApiException(ApiException.Kind.HTTP, status, code)

    @Test
    fun `items are sent in the order they were made and leave the outbox`() = runTest {
        val outbox = outbox()
        listOf("a", "b", "c").forEach { assertTrue(outbox.add(note(it))) }
        assertEquals(3, outbox.drain())
        assertEquals(listOf("a", "b", "c"), sent)
        assertTrue(outbox.items.value.isEmpty())
        assertEquals(0, outbox.drain())
        assertEquals(3, sent.size)
    }

    @Test
    fun `items survive a restart`() = runTest {
        outbox().add(note("a"))
        val restarted = outbox()
        assertEquals(listOf("a"), restarted.items.value.map { it.id })
        assertEquals(1, restarted.drain())
    }

    @Test
    fun `when nothing leaves the phone everything keeps waiting, in order`() = runTest {
        val outbox = outbox()
        listOf("a", "b").forEach { outbox.add(note(it)) }
        fail("a", notSent)
        assertEquals(0, outbox.drain())
        assertEquals(listOf("a"), sent) // b was not tried before a
        assertEquals(listOf(OutboxItem.State.WAITING, OutboxItem.State.WAITING), outbox.items.value.map { it.state })
        assertEquals(0, outbox.items.value[0].attempts)
        assertEquals(2, outbox.drain())
        assertEquals(listOf("a", "a", "b"), sent)
    }

    @Test
    fun `a new note with an unknown outcome is never sent again by itself`() = runTest {
        val outbox = outbox()
        outbox.add(note("a"))
        outbox.add(note("b"))
        fail("a", uncertain)
        assertEquals(0, outbox.drain())
        assertEquals(OutboxItem.State.CHECK, outbox.items.value[0].state)
        // Later drains pass it by and go on with what waits behind it.
        assertEquals(1, outbox.drain())
        assertEquals(1, outbox.drain().let { sent.count { it == "a" } })
        assertEquals(listOf("a"), outbox.items.value.map { it.id })
    }

    @Test
    fun `send again is possible only after the earlier attempt can no longer arrive`() = runTest {
        val outbox = outbox()
        outbox.add(note("a"))
        fail("a", uncertain)
        outbox.drain()
        now += 5 * 60_000
        assertFalse(outbox.sendAgain("a"))
        assertEquals(OutboxItem.State.CHECK, outbox.items.value[0].state)
        now += 61_001
        assertTrue(outbox.sendAgain("a"))
        assertEquals(1, outbox.drain())
        assertEquals(listOf("a", "a"), sent)
    }

    @Test
    fun `a tick with an unknown outcome is repeated, and task-not-found then counts as done`() = runTest {
        val outbox = outbox()
        outbox.add(tick("a"))
        fail("a", uncertain, http(404, "task_not_found"))
        assertEquals(0, outbox.drain())
        assertEquals(OutboxItem.State.WAITING, outbox.items.value[0].state)
        assertEquals(1, outbox.drain())
        assertTrue(outbox.items.value.isEmpty())
        assertEquals(listOf("a", "a"), sent)
    }

    @Test
    fun `a refusal makes the item failed and the rest goes on`() = runTest {
        val outbox = outbox()
        outbox.add(note("a"))
        outbox.add(note("b"))
        fail("a", http(400, "bad_request"))
        assertEquals(1, outbox.drain())
        val left = outbox.items.value.single()
        assertEquals(OutboxItem.State.FAILED, left.state)
        assertEquals("bad_request", left.errorCode)
        outbox.discard("a")
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun `a busy or unavailable computer is tried again later`() = runTest {
        val outbox = outbox()
        outbox.add(note("a"))
        fail("a", http(503, "memory_unavailable"), http(429))
        assertEquals(0, outbox.drain())
        assertEquals(0, outbox.drain())
        assertEquals(OutboxItem.State.WAITING, outbox.items.value[0].state)
        assertEquals(1, outbox.drain())
    }

    @Test
    fun `two drains at once send every item once`() = runTest {
        val outbox = outbox(sendDelay = 50)
        listOf("a", "b", "c").forEach { outbox.add(note(it)) }
        listOf(async { outbox.drain() }, async { outbox.drain() }, async { outbox.drain() }).awaitAll()
        assertEquals(listOf("a", "b", "c"), sent)
    }

    @Test
    fun `the attempt is on storage before the item is sent, and a crash in between is noticed`() = runTest {
        var seenWhileSending: List<OutboxItem> = emptyList()
        val outbox = Outbox(OutboxStore(file), { now }) { seenWhileSending = OutboxStore(file).load() }
        outbox.add(note("a"))
        outbox.drain()
        assertTrue(seenWhileSending.single().sending)
        assertEquals(1, seenWhileSending.single().attempts)

        // The app dies while a note and a tick are being sent.
        OutboxStore(file).save(listOf(note("n").copy(sending = true, attemptAtMillis = now), tick("t").copy(sending = true)))
        val restarted = outbox()
        assertEquals(listOf(OutboxItem.State.CHECK, OutboxItem.State.WAITING), restarted.items.value.map { it.state })
        assertEquals(1, restarted.drain())
        assertEquals(listOf("t"), sent)
    }

    @Test
    fun `a send that is cut off leaves a note for checking and is not sent again`() = runTest {
        val outbox = outbox(sendDelay = 10_000)
        outbox.add(note("a"))
        outbox.add(tick("b"))
        val draining = launch { outbox.drain() }
        advanceTimeBy(1_000)
        draining.cancel()
        draining.join()
        // The note may have arrived: it waits for the owner. The tick may simply be repeated.
        assertEquals(listOf(OutboxItem.State.CHECK, OutboxItem.State.WAITING), outbox.items.value.map { it.state })
        assertTrue(outbox.items.value.none { it.sending })
        val later = outbox(sendDelay = 0)
        assertEquals(1, later.drain())
        assertEquals(listOf("b"), sent)
    }

    @Test
    fun `wiping removes everything from storage`() = runTest {
        val outbox = outbox()
        outbox.add(note("a"))
        outbox.wipe()
        assertTrue(outbox.items.value.isEmpty())
        assertFalse(file.exists())
        assertTrue(outbox().items.value.isEmpty())
    }
}
