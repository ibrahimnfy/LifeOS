package dev.lifeos.app.relay

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.Header
import dev.lifeos.app.crypto.Message
import dev.lifeos.app.crypto.Rejected
import kotlinx.serialization.json.JsonElement

/**
 * The two message counters of a pairing. Both are stored durably *before* the message they
 * belong to is sent or acted on, so a crash can never lead to a counter being used twice or
 * a repeated message being accepted.
 */
interface CounterStore {
    /** Reserve and return the next counter for a message to the bridge. */
    fun nextSend(): Long

    /** Record [counter] as received if it is greater than every earlier one; false otherwise. */
    fun acceptReceived(counter: Long): Boolean
}

/**
 * The phone's end of the sealed channel: seals messages to the bridge and applies the
 * receiver checks of docs/security.md, section 3.3, to everything that claims to come from it.
 */
class Channel(
    private val e2e: E2e,
    deviceId: ByteArray,
    secret: ByteArray,
    private val counters: CounterStore,
    private val nowSeconds: () -> Long,
) {
    private val deviceId = deviceId.copyOf()
    private val secret = secret.copyOf()
    private val drops = mutableMapOf<String, Int>()

    @Volatile
    private var closed = false

    /** Seal one message for the bridge. Every call uses a new counter; a blob is never re-made. */
    @Synchronized
    fun seal(kind: Int, requestId: String, payload: JsonElement): ByteArray {
        check(!closed) { "the channel is closed" }
        val header = Header(deviceId, Direction.PHONE_TO_BRIDGE, counters.nextSend(), kind)
        return e2e.seal(secret, header, nowSeconds(), requestId, payload)
    }

    /**
     * The message if [blob] passes every check, otherwise null. A dropped message is only
     * counted by reason; nothing about it is kept and nobody is told why.
     *
     * Order: 1. known version and shape, this device, direction bridge → phone;
     * 2. authentication (decryption); 3. counter greater than the last accepted one, stored
     * before the message is handed on. Answers do not start work, so (as on the bridge, which
     * times only hello and request) their timestamp is not checked.
     */
    @Synchronized
    fun accept(blob: ByteArray): Message? {
        if (closed) return null
        return try {
            val header = e2e.parseHeader(blob)
            if (!header.isFor(deviceId)) throw Rejected("unknown_device")
            if (header.direction != Direction.BRIDGE_TO_PHONE) throw Rejected("direction")
            val message = e2e.open(secret, blob)
            if (!counters.acceptReceived(header.counter)) throw Rejected("replay")
            message
        } catch (rejected: Rejected) {
            drops[rejected.reason] = (drops[rejected.reason] ?: 0) + 1
            null
        }
    }

    /** The counter in a blob's header, for ordering a batch; null if it has no valid header. */
    fun counterOf(blob: ByteArray): Long? = try {
        e2e.parseHeader(blob).counter
    } catch (_: Rejected) {
        null
    }

    @Synchronized
    fun dropCounts(): Map<String, Int> = drops.toMap()

    /** Forget the secret. The channel cannot be used afterwards. */
    @Synchronized
    fun close() {
        closed = true
        secret.fill(0)
    }
}
