package dev.lifeos.app.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Interoperability with the bridge: the same keys, the same bytes, the same code. */
class E2eVectorsTest {
    private val e2e = E2e(testSodium)

    @Test
    fun `sub-keys equal the bridge's for every direction and the code`() {
        assertEquals(setOf(1, 2, 3, 4), Vectors.subkeys.keys)
        Vectors.subkeys.forEach { (number, hex) -> assertEquals(hex, e2e.deriveKey(Vectors.secret, number).toHex()) }
    }

    @Test
    fun `headers pack to the bridge's bytes and parse back`() {
        for (vector in Vectors.sealed) {
            val header = Header(Vectors.deviceId, vector.direction, vector.counter, vector.kind)
            assertArrayEquals(vector.name, vector.header, header.pack())
            assertEquals(30, vector.header.size)
            assertEquals(vector.name, header, e2e.parseHeader(vector.blob))
        }
    }

    @Test
    fun `every message sealed by the bridge is opened`() {
        assertTrue(Vectors.sealed.size >= 8)
        for (vector in Vectors.sealed) {
            val message = e2e.open(Vectors.secret, vector.blob)
            assertEquals(vector.name, vector.timestamp, message.timestamp)
            assertEquals(vector.name, vector.requestId, message.requestId)
            assertEquals(vector.name, vector.payload, message.payload)
            assertEquals(vector.name, vector.counter, message.header.counter)
            assertEquals(vector.name, vector.kind, message.header.kind)
            assertEquals(vector.name, vector.direction, message.header.direction)
            assertTrue(message.header.isFor(Vectors.deviceId))
        }
    }

    @Test
    fun `sealing here with the bridge's nonce gives the bridge's exact bytes`() {
        // Same key, same nonce, same header and the same plaintext bytes: so whatever this
        // app seals, the bridge opens. Both directions and non-ASCII text are covered.
        for (vector in Vectors.sealed) {
            val header = Header(Vectors.deviceId, vector.direction, vector.counter, vector.kind)
            val blob = e2e.seal(Vectors.secret, header, vector.timestamp, vector.requestId, vector.payload, vector.nonce)
            assertArrayEquals(vector.name, vector.blob, blob)
        }
    }

    @Test
    fun `comparison code equals the bridge's`() {
        val code = e2e.comparisonCode(Vectors.secret, Vectors.named("hello").blob)
        assertEquals(Vectors.comparisonCode, code)
        assertTrue(Regex("[0-9]{6}").matches(code))
        // It depends on the hello message and on the secret.
        assertFalse(code == e2e.comparisonCode(Vectors.secret, Vectors.named("request").blob))
        assertFalse(code == e2e.comparisonCode(ByteArray(32) { 7 }, Vectors.named("hello").blob))
    }

    @Test
    fun `comparison code is shown in two groups`() {
        assertEquals("012 345", formatComparisonCode("012345"))
    }

    @Test
    fun `random nonces differ and the result opens again`() {
        val header = Header(Vectors.deviceId, Direction.PHONE_TO_BRIDGE, 9, Kind.REQUEST)
        val payload = buildJsonObject { put("text", JsonPrimitive("Çağrı – 東京")) }
        val first = e2e.seal(Vectors.secret, header, 1_800_000_100, "r-9", payload)
        val second = e2e.seal(Vectors.secret, header, 1_800_000_100, "r-9", payload)
        assertFalse(first.contentEquals(second))
        assertEquals(payload, e2e.open(Vectors.secret, first).payload)
    }

    /**
     * Writes messages sealed here (random nonces) for the bridge to open:
     * `bridge/.venv/bin/python <script that calls e2e.open_sealed>` during development.
     */
    @Test
    fun `messages sealed here are written out for the bridge to check`() {
        val cases = buildJsonArray {
            for (vector in Vectors.sealed) {
                val header = Header(Vectors.deviceId, vector.direction, vector.counter + 100, vector.kind)
                val blob = e2e.seal(Vectors.secret, header, vector.timestamp, vector.requestId, vector.payload)
                add(
                    buildJsonObject {
                        put("name", JsonPrimitive(vector.name))
                        put("counter", JsonPrimitive(vector.counter + 100))
                        put("ts", JsonPrimitive(vector.timestamp))
                        put("rid", JsonPrimitive(vector.requestId))
                        put("payload", vector.payload)
                        put("blob", JsonPrimitive(Base64.getEncoder().encodeToString(blob)))
                        put("code", JsonPrimitive(e2e.comparisonCode(Vectors.secret, blob)))
                    },
                )
            }
        }
        val out = File("build/e2e-from-kotlin.json")
        out.parentFile?.mkdirs()
        out.writeText(Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), cases))
        assertTrue(out.length() > 0)
    }
}
