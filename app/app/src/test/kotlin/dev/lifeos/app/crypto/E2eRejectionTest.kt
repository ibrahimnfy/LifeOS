package dev.lifeos.app.crypto

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/security.md, section 10: any changed byte is rejected. */
class E2eRejectionTest {
    private val e2e = E2e(testSodium)
    private val blob = Vectors.named("pair_end").blob

    private fun reasonFor(changed: ByteArray): String =
        assertThrows(Rejected::class.java) { e2e.open(Vectors.secret, changed) }.reason

    private fun flipped(index: Int, mask: Int = 0x01): ByteArray =
        blob.copyOf().also { it[index] = (it[index].toInt() xor mask).toByte() }

    @Test
    fun `flipping any single bit position of any byte is rejected`() {
        // Header (magic, version, device id, direction, counter, kind), nonce, ciphertext, tag.
        for (index in blob.indices) {
            for (mask in listOf(0x01, 0x80)) {
                val reason = reasonFor(flipped(index, mask))
                assertTrue("byte $index: $reason", reason in setOf("version", "kind", "authentication", "malformed"))
            }
        }
    }

    @Test
    fun `each part fails for the expected reason`() {
        assertEquals("version", reasonFor(flipped(0))) // magic
        assertEquals("version", reasonFor(flipped(3))) // version
        assertEquals("authentication", reasonFor(flipped(4))) // device id
        assertEquals("authentication", reasonFor(flipped(19))) // device id, last byte
        assertEquals("authentication", reasonFor(flipped(21, 0x40))) // counter
        assertEquals("authentication", reasonFor(flipped(28))) // counter, last byte
        assertEquals("authentication", reasonFor(flipped(29, 0x03))) // kind END -> ERROR
        assertEquals("kind", reasonFor(flipped(29, 0x40))) // unknown kind
        assertEquals("authentication", reasonFor(flipped(30))) // nonce
        assertEquals("authentication", reasonFor(flipped(54))) // ciphertext
        assertEquals("authentication", reasonFor(flipped(blob.size - 1))) // tag
        // Direction 2 -> 1: another key, and the header no longer matches.
        assertEquals("authentication", reasonFor(flipped(20, 0x03)))
    }

    @Test
    fun `truncated, extended, empty and oversized messages are rejected`() {
        assertEquals("malformed", reasonFor(ByteArray(0)))
        assertEquals("malformed", reasonFor(blob.copyOf(69)))
        assertEquals("authentication", reasonFor(blob.copyOf(blob.size - 1)))
        assertEquals("authentication", reasonFor(blob + byteArrayOf(0)))
        assertEquals("too_large", reasonFor(blob + ByteArray(Wire.MAX_MESSAGE_BYTES)))
    }

    @Test
    fun `another secret cannot open a message`() {
        assertThrows(Rejected::class.java) { e2e.open(ByteArray(32) { 1 }, blob) }
    }

    @Test
    fun `a counter beyond the signed range is rejected, not wrapped`() {
        val changed = blob.copyOf().also { it[21] = 0x80.toByte() }
        assertEquals("malformed", reasonFor(changed))
    }

    @Test
    fun `authentic messages with a wrong inner shape are rejected`() {
        val header = Header(Vectors.deviceId, Direction.BRIDGE_TO_PHONE, 5, Kind.END)
        val key = e2e.deriveKey(Vectors.secret, Direction.BRIDGE_TO_PHONE)
        for (plaintext in listOf("not json", "[1,2]", """{"ts":1,"p":{}}""", """{"ts":"x","rid":"r","p":{}}""", """{"ts":1,"rid":"r"}""")) {
            val nonce = testSodium.randomBytes(24)
            val sealed = header.pack() + nonce + testSodium.aeadEncrypt(plaintext.toByteArray(), header.pack(), nonce, key)
            assertEquals(plaintext, "malformed", reasonFor(sealed))
        }
    }

    @Test
    fun `headers refuse impossible values`() {
        assertThrows(IllegalArgumentException::class.java) { Header(ByteArray(15), 1, 1, Kind.HELLO) }
        assertThrows(IllegalArgumentException::class.java) { Header(ByteArray(16), 1, -1, Kind.HELLO) }
        assertThrows(IllegalArgumentException::class.java) {
            e2e.seal(ByteArray(31), Header(ByteArray(16), 1, 1, Kind.HELLO), 0, "r", JsonPrimitive(1))
        }
    }
}
