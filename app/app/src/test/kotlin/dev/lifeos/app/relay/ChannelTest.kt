package dev.lifeos.app.relay

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.Header
import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Vectors
import dev.lifeos.app.crypto.testSodium
import dev.lifeos.app.pairing.FileCounterStore
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The receiver checks of docs/security.md, section 3.3, on the phone's side. */
class ChannelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val e2e = E2e(testSodium)
    private val payload = JsonPrimitive("x")

    private fun channel(counters: CounterStore = MemoryCounters(), id: ByteArray = Vectors.deviceId) =
        Channel(e2e, id, Vectors.secret, counters) { 1_800_000_000 }

    private fun fromBridge(counter: Long, kind: Int = Kind.END, secret: ByteArray = Vectors.secret, direction: Int = Direction.BRIDGE_TO_PHONE, id: ByteArray = Vectors.deviceId) =
        e2e.seal(secret, Header(id, direction, counter, kind), 1_800_000_000, "r", payload)

    @Test
    fun `genuine messages are accepted in rising counter order`() {
        val channel = channel()
        assertEquals(1L, channel.accept(fromBridge(1))?.header?.counter)
        assertEquals(5L, channel.accept(fromBridge(5))?.header?.counter)
        assertEquals(emptyMap<String, Int>(), channel.dropCounts())
    }

    @Test
    fun `a repeated or older message is dropped`() {
        val channel = channel()
        val blob = fromBridge(3)
        assertNotNull(channel.accept(blob))
        assertNull(channel.accept(blob))
        assertNull(channel.accept(fromBridge(2)))
        assertNull(channel.accept(fromBridge(3)))
        assertNotNull(channel.accept(fromBridge(4)))
        assertEquals(mapOf("replay" to 3), channel.dropCounts())
    }

    @Test
    fun `a repeat is dropped after a restart too`() {
        val file = File(folder.root, "counters.json")
        val blob = fromBridge(7)
        assertNotNull(channel(FileCounterStore.create(file, sent = 1, received = 0)).accept(blob))
        val restarted = channel(FileCounterStore.open(file))
        assertNull(restarted.accept(blob))
        assertNotNull(restarted.accept(fromBridge(8)))
    }

    @Test
    fun `a reflected message, one for another device, and a forged one are dropped`() {
        val channel = channel()
        // The phone's own message sent back to it: right secret, wrong direction.
        assertNull(channel.accept(fromBridge(1, direction = Direction.PHONE_TO_BRIDGE)))
        assertNull(channel.accept(fromBridge(1, direction = Direction.PUSH, kind = Kind.PUSH_HINT)))
        assertNull(channel.accept(fromBridge(1, id = ByteArray(16) { 9 })))
        assertNull(channel.accept(fromBridge(1, secret = ByteArray(32) { 9 })))
        assertNull(channel.accept(ByteArray(100)))
        assertNull(channel.accept(ByteArray(0)))
        assertEquals(
            mapOf("direction" to 2, "unknown_device" to 1, "authentication" to 1, "version" to 1, "malformed" to 1),
            channel.dropCounts(),
        )
        // Nothing of that moved the counter.
        assertNotNull(channel.accept(fromBridge(1)))
    }

    @Test
    fun `a tampered message does not advance the counter`() {
        val counters = MemoryCounters()
        val channel = channel(counters)
        val blob = fromBridge(9)
        for (index in blob.indices) {
            val changed = blob.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull("byte $index", channel.accept(changed))
        }
        assertEquals(0, counters.received)
        assertNotNull(channel.accept(blob))
    }

    @Test
    fun `every sealed message uses a new, stored counter`() {
        val file = File(folder.root, "counters.json")
        val channel = channel(FileCounterStore.create(file, sent = 1, received = 0))
        val first = e2e.parseHeader(channel.seal(Kind.REQUEST, "a", payload))
        val second = e2e.parseHeader(channel.seal(Kind.REQUEST, "a", payload))
        assertEquals(listOf(2L, 3L), listOf(first.counter, second.counter))
        assertEquals(Direction.PHONE_TO_BRIDGE, first.direction)
        // After a restart the numbering continues; nothing is reused.
        val restarted = channel(FileCounterStore.open(file))
        assertEquals(4, e2e.parseHeader(restarted.seal(Kind.REQUEST, "a", payload)).counter)
    }

    @Test
    fun `the counter is on storage before a number is handed out`() {
        val file = File(folder.root, "counters.json")
        val store = FileCounterStore.create(file, sent = 1, received = 0)
        assertEquals(2, store.nextSend())
        assertTrue(""""sent":2""" in file.readText())
        assertTrue(store.acceptReceived(4))
        assertTrue(""""received":4""" in file.readText())
        // When storage fails, no number is handed out and nothing is accepted.
        assertTrue(file.delete() && folder.root.deleteRecursively())
        assertThrows(IOException::class.java) { store.nextSend() }
        assertThrows(IOException::class.java) { store.acceptReceived(5) }
    }

    @Test
    fun `missing or damaged counters are an error, never a fresh start`() {
        val file = File(folder.root, "counters.json")
        assertThrows(IOException::class.java) { FileCounterStore.open(file) }
        file.writeText("{")
        assertThrows(IOException::class.java) { FileCounterStore.open(file) }
        file.writeText("""{"sent":3}""")
        assertThrows(IOException::class.java) { FileCounterStore.open(file) }
    }

    @Test
    fun `a closed channel neither seals nor accepts`() {
        val channel = channel()
        channel.close()
        assertNull(channel.accept(fromBridge(1)))
        assertThrows(IllegalStateException::class.java) { channel.seal(Kind.REQUEST, "a", payload) }
    }
}
