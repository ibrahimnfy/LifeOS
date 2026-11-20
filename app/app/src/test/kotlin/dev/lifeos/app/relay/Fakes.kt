package dev.lifeos.app.relay

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.Header
import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Message
import dev.lifeos.app.crypto.Vectors
import dev.lifeos.app.crypto.testSodium
import dev.lifeos.app.crypto.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import java.util.TreeMap

/** A mailbox in memory. It records everything that was ever written, like a hostile relay would. */
class FakeMailbox : Mailbox {
    val tree = TreeMap<String, String>()
    val recorded = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()

    /** Reasons the next puts fail with, in order. */
    val putFailures = ArrayDeque<MailboxException.Reason>()

    /** Whether a failing put is stored anyway (the answer got lost, not the request). */
    var failedPutsArrive = false

    /** Paths that may not be read, like a mailbox before the bridge grants access. */
    var readDenied: (String) -> Boolean = { false }

    /** Paths that can be written only once, like the pairing hello. */
    var writeOnce: (String) -> Boolean = { false }

    override suspend fun put(path: String, value: String) {
        if (writeOnce(path) && path in tree) throw MailboxException(MailboxException.Reason.DENIED, 401)
        val failure = putFailures.removeFirstOrNull()
        if (failure == null || (failedPutsArrive && failure != MailboxException.Reason.NOT_SENT)) {
            tree[path] = value
            recorded += path to value
        }
        if (failure != null) throw MailboxException(failure)
    }

    override suspend fun get(path: String): String? {
        if (readDenied(path)) throw MailboxException(MailboxException.Reason.DENIED, 401)
        return tree[path]
    }

    override suspend fun delete(path: String) {
        deleted += path
        tree.keys.filter { it == path || it.startsWith("$path/") }.forEach { tree.remove(it) }
    }

    override suspend fun keys(path: String): List<String> {
        if (readDenied(path)) throw MailboxException(MailboxException.Reason.DENIED, 401)
        return children(path).map { it.key }
    }

    fun children(path: String): List<MailboxEntry> = tree.entries
        .filter { it.key.startsWith("$path/") && '/' !in it.key.substring(path.length + 1) }
        .map { MailboxEntry(it.key.substring(path.length + 1), it.value) }

    override fun listen(path: String): Flow<MailboxEvent> = flow {
        emit(MailboxEvent.Up)
        while (true) {
            val entries = children(path)
            if (entries.isNotEmpty()) emit(MailboxEvent.Entries(entries))
            delay(20)
        }
    }
}

class MemoryCounters(var sent: Long = 1, var received: Long = 0) : CounterStore {
    val reserved = mutableListOf<Long>()

    override fun nextSend(): Long {
        sent += 1
        reserved += sent
        return sent
    }

    override fun acceptReceived(counter: Long): Boolean {
        if (counter <= received) return false
        received = counter
        return true
    }
}

/** The bridge's side for tests: opens requests with the same secret and seals answers. */
class FakeBridge(private val box: FakeMailbox, private val now: () -> Long) {
    val e2e = E2e(testSodium)
    val deviceHex = Vectors.deviceId.toHex()
    val requests = mutableListOf<Message>()
    var sent = 0L
    private var received = 1L
    private var keys = 0

    /** What a request is answered with: (kind, payload) pairs. Null: no answer at all. */
    var answer: (JsonElement) -> List<Pair<Int, JsonElement>>? = { null }

    fun seal(kind: Int, rid: String, payload: JsonElement, counter: Long = ++sent): ByteArray =
        e2e.seal(Vectors.secret, Header(Vectors.deviceId, Direction.BRIDGE_TO_PHONE, counter, kind), now() / 1000, rid, payload)

    fun put(blob: ByteArray, key: String = nextKey()) {
        box.tree["${MailboxKeys.toPhone(deviceHex)}/$key"] = MailboxKeys.encode(blob)
        box.recorded += "${MailboxKeys.toPhone(deviceHex)}/$key" to MailboxKeys.encode(blob)
    }

    fun nextKey(): String = MailboxKeys.newKey(now() + keys, byteArrayOf(0, 0, 0, (keys++).toByte()))

    /** Take every waiting request and answer it; run this in a background coroutine. */
    suspend fun serve() {
        val folder = MailboxKeys.toBridge(deviceHex)
        while (true) {
            for (entry in box.children(folder)) {
                box.tree.remove("$folder/${entry.key}")
                val blob = MailboxKeys.decode(entry.value) ?: continue
                val message = e2e.open(Vectors.secret, blob)
                check(message.header.direction == Direction.PHONE_TO_BRIDGE && message.header.kind == Kind.REQUEST)
                check(message.header.counter > received) { "the phone reused a counter" }
                received = message.header.counter
                requests += message
                answer(message.payload)?.forEach { (kind, payload) -> put(seal(kind, message.requestId, payload)) }
            }
            delay(10)
        }
    }
}
