package dev.lifeos.app.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.ByteBuffer

/**
 * The end-to-end layer between this phone and the bridge (docs/security.md, section 3).
 * It mirrors the bridge's `e2e.py` exactly; the unit tests check both against shared vectors.
 *
 * Wire format of one message:
 *
 *     header (30 bytes, authenticated, readable by the relay)
 *         "LOS" | version (1) | device id (16) | direction (1) | counter (8, big endian) | kind (1)
 *     nonce (24 random bytes)
 *     ciphertext and tag
 *
 * The plaintext is JSON: `{"ts": <unix seconds>, "rid": <request id>, "p": <payload>}`.
 * There is no own cipher, mode or handshake here: only the calls of [Sodium].
 */
object Wire {
    val MAGIC = byteArrayOf('L'.code.toByte(), 'O'.code.toByte(), 'S'.code.toByte())
    const val VERSION = 1
    const val HEADER_BYTES = 30
    const val DEVICE_ID_BYTES = 16
    const val SECRET_BYTES = 32
    const val MAX_MESSAGE_BYTES = 1_400_000
    val KDF_CONTEXT = "lifeosv1".toByteArray(Charsets.US_ASCII)
}

/** Directions. The number is also the sub-key number, so each direction has its own key. */
object Direction {
    const val PHONE_TO_BRIDGE = 1
    const val BRIDGE_TO_PHONE = 2
    const val PUSH = 3
}

/** Sub-key number of the pairing comparison code. */
const val SUBKEY_CODE = 4

/** Message kinds. */
object Kind {
    const val HELLO = 1
    const val REQUEST = 2
    const val CANCEL = 3
    const val ACK = 4
    const val CHUNK = 16
    const val END = 17
    const val ERROR = 18
    const val PUSH_HINT = 32
    val ALL = setOf(HELLO, REQUEST, CANCEL, ACK, CHUNK, END, ERROR, PUSH_HINT)
}

/** A message failed a check. [reason] is a short fixed word, never content. */
class Rejected(val reason: String) : Exception(reason)

class Header(deviceId: ByteArray, val direction: Int, val counter: Long, val kind: Int) {
    private val id = deviceId.copyOf()
    val deviceId: ByteArray get() = id.copyOf()

    init {
        require(id.size == Wire.DEVICE_ID_BYTES) { "device id has the wrong length" }
        require(direction in 0..255 && kind in 0..255 && counter >= 0)
    }

    fun pack(): ByteArray = ByteBuffer.allocate(Wire.HEADER_BYTES)
        .put(Wire.MAGIC)
        .put(Wire.VERSION.toByte())
        .put(id)
        .put(direction.toByte())
        .putLong(counter) // big endian is ByteBuffer's default
        .put(kind.toByte())
        .array()

    fun isFor(device: ByteArray): Boolean = id.contentEquals(device)

    override fun equals(other: Any?): Boolean =
        other is Header && id.contentEquals(other.id) && direction == other.direction &&
            counter == other.counter && kind == other.kind

    override fun hashCode(): Int = id.contentHashCode() * 31 + counter.hashCode()

    override fun toString(): String = "Header(direction=$direction, counter=$counter, kind=$kind)"
}

class Message(val header: Header, val timestamp: Long, val requestId: String, val payload: JsonElement)

class E2e(private val sodium: Sodium) {

    fun randomBytes(count: Int): ByteArray = sodium.randomBytes(count)

    /** Sub-key [subkey] of a device secret (`crypto_kdf_derive_from_key`, context "lifeosv1"). */
    fun deriveKey(secret: ByteArray, subkey: Int): ByteArray {
        require(secret.size == Wire.SECRET_BYTES) { "device secret has the wrong length" }
        return sodium.kdfDeriveFromKey(subkey.toLong(), Wire.KDF_CONTEXT, secret)
    }

    /** The routing header of a message. Throws [Rejected] for anything malformed. */
    fun parseHeader(blob: ByteArray): Header {
        if (blob.size > Wire.MAX_MESSAGE_BYTES) throw Rejected("too_large")
        if (blob.size < Wire.HEADER_BYTES + Sodium.NONCE_BYTES + Sodium.TAG_BYTES) throw Rejected("malformed")
        val buffer = ByteBuffer.wrap(blob, 0, Wire.HEADER_BYTES)
        val magic = ByteArray(Wire.MAGIC.size).also { buffer.get(it) }
        val version = buffer.get().toInt() and 0xFF
        if (!magic.contentEquals(Wire.MAGIC) || version != Wire.VERSION) throw Rejected("version")
        val deviceId = ByteArray(Wire.DEVICE_ID_BYTES).also { buffer.get(it) }
        val direction = buffer.get().toInt() and 0xFF
        val counter = buffer.getLong()
        val kind = buffer.get().toInt() and 0xFF
        if (kind !in Kind.ALL) throw Rejected("kind")
        // Counters are unsigned on the wire; one beyond the signed range cannot be genuine.
        if (counter < 0) throw Rejected("malformed")
        return Header(deviceId, direction, counter, kind)
    }

    /** Encrypt and authenticate one message. A fresh random nonce is used for every message. */
    fun seal(secret: ByteArray, header: Header, timestamp: Long, requestId: String, payload: JsonElement): ByteArray =
        seal(secret, header, timestamp, requestId, payload, sodium.randomBytes(Sodium.NONCE_BYTES))

    /** As [seal], with the nonce given. Only for tests against fixed vectors. */
    internal fun seal(
        secret: ByteArray,
        header: Header,
        timestamp: Long,
        requestId: String,
        payload: JsonElement,
        nonce: ByteArray,
    ): ByteArray {
        val plaintext = buildJsonObject {
            put("ts", JsonPrimitive(timestamp))
            put("rid", JsonPrimitive(requestId))
            put("p", payload)
        }.toString().toByteArray(Charsets.UTF_8)
        val packed = header.pack()
        val key = deriveKey(secret, header.direction)
        val blob = packed + nonce + sodium.aeadEncrypt(plaintext, packed, nonce, key)
        key.fill(0)
        require(blob.size <= Wire.MAX_MESSAGE_BYTES) { "message too large; split it into chunks" }
        return blob
    }

    /** Decrypt one message. Success proves it was made by a holder of the device secret. */
    fun open(secret: ByteArray, blob: ByteArray): Message {
        val header = parseHeader(blob)
        val packed = blob.copyOfRange(0, Wire.HEADER_BYTES)
        val nonce = blob.copyOfRange(Wire.HEADER_BYTES, Wire.HEADER_BYTES + Sodium.NONCE_BYTES)
        val key = deriveKey(secret, header.direction)
        val plaintext = sodium.aeadDecrypt(
            blob.copyOfRange(Wire.HEADER_BYTES + Sodium.NONCE_BYTES, blob.size), packed, nonce, key,
        )
        key.fill(0)
        if (plaintext == null) throw Rejected("authentication")
        try {
            val data: JsonObject = Json.parseToJsonElement(String(plaintext, Charsets.UTF_8)).jsonObject
            val rid = data.getValue("rid")
            if (rid !is JsonPrimitive || rid is JsonNull) throw Rejected("malformed")
            return Message(header, data.getValue("ts").jsonPrimitive.long, rid.content, data.getValue("p"))
        } catch (error: Rejected) {
            throw error
        } catch (_: Exception) {
            throw Rejected("malformed")
        }
    }

    /** Six digits both screens show at pairing; depends on the secret and the hello message. */
    fun comparisonCode(secret: ByteArray, helloBlob: ByteArray): String {
        val key = deriveKey(secret, SUBKEY_CODE)
        val digest = sodium.genericHash(helloBlob, key)
        key.fill(0)
        val number = java.lang.Long.remainderUnsigned(ByteBuffer.wrap(digest, 0, 8).getLong(), 1_000_000L)
        return "%06d".format(number)
    }
}

/** `123456` as `123 456`, the way both screens show the comparison code. */
fun formatComparisonCode(code: String): String =
    if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code
