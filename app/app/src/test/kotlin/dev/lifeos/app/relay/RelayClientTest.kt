package dev.lifeos.app.relay

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.Header
import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Vectors
import dev.lifeos.app.crypto.testSodium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayClientTest {
    private val box = FakeMailbox()
    private val counters = MemoryCounters()

    private class Setup(val client: RelayClient, val bridge: FakeBridge, val channel: Channel)

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun TestScope.setup(serve: Boolean = true): Setup {
        val now = { 1_800_000_000_000 + testScheduler.currentTime }
        val bridge = FakeBridge(box, now)
        val channel = Channel(bridge.e2e, Vectors.deviceId, Vectors.secret, counters) { now() / 1000 }
        val client = RelayClient(box, channel, bridge.deviceHex, backgroundScope, now, testSodium::randomBytes)
        client.start()
        if (serve) backgroundScope.launch { bridge.serve() }
        return Setup(client, bridge, channel)
    }

    private fun toBridgeCounters(): List<Long> = box.recorded
        .filter { "/b/" in it.first }
        .map { E2eHolder.e2e.parseHeader(MailboxKeys.decode(it.second)!!).counter }

    private object E2eHolder {
        val e2e = dev.lifeos.app.crypto.E2e(testSodium)
    }

    private suspend fun failure(block: suspend () -> Unit): RelayException.Reason {
        try {
            block()
        } catch (error: RelayException) {
            return error.reason
        }
        fail("expected a RelayException")
        throw IllegalStateException()
    }

    @Test
    fun `a single answer`() = runTest {
        val s = setup()
        s.bridge.answer = { listOf(Kind.END to json("""{"status":200,"body":{"status":"ok","version":"test"}}""")) }
        val response = s.client.request("GET", "/whoami")
        assertEquals(RelayResponse(200, json("""{"status":"ok","version":"test"}""")), response)
        assertTrue(response.ok)
        // The request has the bridge's shape and went through the mailbox sealed.
        assertEquals(json("""{"m":"GET","path":"/whoami","q":{},"body":null}"""), s.bridge.requests.single().payload)
        assertTrue(MailboxKeys.isKey(box.recorded.first().first.substringAfterLast('/')))
        assertEquals(Link.UP, s.client.link.value)
        // The answer was taken out of the mailbox: that is the acknowledgement.
        advanceTimeBy(100)
        assertEquals(emptyList<MailboxEntry>(), box.children(MailboxKeys.toPhone(s.bridge.deviceHex)))
    }

    @Test
    fun `listening again at once keeps answering, and not after a stop`() = runTest {
        val s = setup()
        s.bridge.answer = { listOf(Kind.END to json("""{"status":200,"body":{"n":1}}""")) }
        advanceTimeBy(10)
        assertEquals(1, box.listens)
        s.client.reconnect()
        advanceTimeBy(10)
        assertEquals(2, box.listens)
        assertEquals(RelayResponse(200, json("""{"n":1}""")), s.client.request("GET", "/whoami"))
        s.client.stop()
        s.client.reconnect()
        advanceTimeBy(10)
        assertEquals(2, box.listens)
        assertEquals(Link.UNKNOWN, s.client.link.value)
    }

    @Test
    fun `query and body are passed on`() = runTest {
        val s = setup()
        s.bridge.answer = { request -> listOf(Kind.END to buildJsonObject { put("status", JsonPrimitive(201)); put("body", request) }) }
        val body = json("""{"text":"süt al"}""")
        val response = s.client.request("POST", "/notes", mapOf("limit" to "5"), body)
        assertEquals(201, response.status)
        assertEquals(json("""{"m":"POST","path":"/notes","q":{"limit":"5"},"body":{"text":"süt al"}}"""), response.body)
    }

    @Test
    fun `a large answer arrives in parts and is put together`() = runTest {
        val s = setup()
        val text = """{"sessions":["${"x".repeat(50)}","ğüşiöç"],"limit":5}"""
        val pieces = text.chunked(17)
        s.bridge.answer = {
            pieces.mapIndexed { index, part ->
                Kind.CHUNK to buildJsonObject { put("i", JsonPrimitive(index)); put("part", JsonPrimitive(part)) }
            } + (Kind.END to json("""{"status":200,"parts":${pieces.size}}"""))
        }
        assertEquals(RelayResponse(200, json(text)), s.client.request("GET", "/sessions"))
    }

    @Test
    fun `an answer with a missing part is an error, not a half answer`() = runTest {
        val s = setup()
        s.bridge.answer = { listOf(Kind.CHUNK to json("""{"i":0,"part":"{\"a\":"}"""), Kind.END to json("""{"status":200,"parts":2}""")) }
        val response = s.client.request("GET", "/sessions")
        assertEquals(RelayResponse(502, null, "incomplete_answer"), response)
        assertFalse(response.ok)
    }

    @Test
    fun `a streamed answer is exposed piece by piece`() = runTest {
        val s = setup()
        s.bridge.answer = {
            listOf(
                Kind.CHUNK to json("""{"event":"session","data":{"session_id":"s1"}}"""),
                Kind.CHUNK to json("""{"event":"delta","data":{"text":"Merhaba "}}"""),
                Kind.CHUNK to json("""{"event":"delta","data":{"text":"dünya"}}"""),
                Kind.CHUNK to json("""{"event":"done","data":{"answer":"Merhaba dünya"}}"""),
                Kind.END to json("""{"status":200,"stream":true}"""),
            )
        }
        val events = s.client.stream("POST", "/ask", body = json("""{"prompt":"x","stream":true}""")).toList()
        assertEquals(listOf("session", "delta", "delta", "done"), events.filterIsInstance<AnswerEvent.Stream>().map { it.event })
        val text = events.filterIsInstance<AnswerEvent.Stream>().filter { it.event == "delta" }
            .joinToString("") { it.data.jsonObject.getValue("text").jsonPrimitive.content }
        assertEquals("Merhaba dünya", text)
        assertEquals(AnswerEvent.Done(RelayResponse(200, null)), events.last())
    }

    @Test
    fun `a stream must start soon`() = runTest {
        // Nobody answers: the wait ends after the time allowed for the first message.
        val s = setup(serve = false)
        val started = testScheduler.currentTime
        assertEquals(
            RelayException.Reason.TIMEOUT,
            failure { s.client.stream("POST", "/ask", idleTimeoutMillis = 600_000, firstTimeoutMillis = 45_000).toList() },
        )
        assertEquals(45_000, testScheduler.currentTime - started)
    }

    @Test
    fun `a stream may stay quiet for long once it has started`() = runTest {
        // The computer took the message and then says nothing: the longer limit applies.
        val s = setup()
        s.bridge.answer = { listOf(Kind.CHUNK to json("""{"event":"session","data":{"session_id":"s1"}}""")) }
        val started = testScheduler.currentTime
        val events = mutableListOf<AnswerEvent>()
        assertEquals(
            RelayException.Reason.TIMEOUT,
            failure { s.client.stream("POST", "/ask", idleTimeoutMillis = 600_000, firstTimeoutMillis = 45_000).collect { events += it } },
        )
        assertEquals(1, events.size)
        assertTrue(testScheduler.currentTime - started >= 600_000)
    }

    @Test
    fun `file pieces are exposed with their position`() = runTest {
        val s = setup()
        s.bridge.answer = {
            listOf(
                Kind.CHUNK to json("""{"i":0,"n":2,"b64":"AAEC"}"""),
                Kind.CHUNK to json("""{"i":1,"n":2,"b64":"Aw=="}"""),
                Kind.END to json("""{"status":200,"file":{"size":4,"chunks":2,"sha256":"x"}}"""),
            )
        }
        val events = s.client.stream("GET", "/files/offer").toList()
        val chunks = events.filterIsInstance<AnswerEvent.FileChunk>()
        assertEquals(listOf(0 to 2, 1 to 2), chunks.map { it.index to it.total })
        assertEquals(listOf<Byte>(0, 1, 2, 3), chunks.flatMap { it.bytes.toList() })
        assertEquals(200, (events.last() as AnswerEvent.Done).response.status)
    }

    @Test
    fun `the bridge's error message becomes a plain result`() = runTest {
        val s = setup()
        s.bridge.answer = { listOf(Kind.ERROR to json("""{"status":404,"error":"not_found"}""")) }
        assertEquals(RelayResponse(404, null, "not_found"), s.client.request("GET", "/unknown"))
    }

    @Test
    fun `answers to two requests at once do not mix`() = runTest {
        val s = setup()
        s.bridge.answer = { request -> listOf(Kind.END to buildJsonObject { put("status", JsonPrimitive(200)); put("body", (request as JsonObject).getValue("path")) }) }
        val results = mutableListOf<String>()
        val first = launch { results += s.client.request("GET", "/tasks").body!!.jsonPrimitive.content }
        val second = launch { results += s.client.request("GET", "/notes").body!!.jsonPrimitive.content }
        first.join()
        second.join()
        assertEquals(setOf("/tasks", "/notes"), results.toSet())
        // Written one after the other: the keys rise with the counters.
        val written = box.recorded.filter { "/b/" in it.first }
        assertEquals(written.map { it.first }.sorted(), written.map { it.first })
        assertEquals(listOf(2L, 3L), toBridgeCounters())
    }

    @Test
    fun `a batch is handled in counter order even when the keys say otherwise`() = runTest {
        val s = setup(serve = false)
        val caller = launch {
            assertEquals(RelayResponse(200, json("""{"a":1}""")), s.client.request("GET", "/whoami"))
        }
        advanceTimeBy(5)
        val rid = E2eHolder.e2e.open(Vectors.secret, MailboxKeys.decode(box.recorded.single().second)!!).requestId
        // The bridge numbers messages before writing them; a later number can get an earlier key.
        val laterKey = s.bridge.nextKey()
        val earlierKey = "0" + laterKey.drop(1)
        s.bridge.put(s.bridge.seal(Kind.CHUNK, rid, json("""{"i":0,"part":"{\"a\":1}"}""")), laterKey)
        s.bridge.put(s.bridge.seal(Kind.END, rid, json("""{"status":200,"parts":1}""")), earlierKey)
        caller.join()
    }

    @Test
    fun `repeated, forged, reflected and foreign entries cause nothing and are removed`() = runTest {
        val s = setup()
        s.bridge.answer = { listOf(Kind.END to json("""{"status":200,"body":1}""")) }
        assertEquals(200, s.client.request("GET", "/whoami").status)
        advanceTimeBy(100)
        val genuine = box.recorded.last { "/p/" in it.first }.second
        val folder = MailboxKeys.toPhone(s.bridge.deviceHex)
        val reflected = MailboxKeys.decode(box.recorded.first { "/b/" in it.first }.second)!!
        val stranger = s.bridge.e2e.seal(
            ByteArray(32) { 5 }, Header(Vectors.deviceId, Direction.BRIDGE_TO_PHONE, 99, Kind.END), 1_800_000_000, "r", JsonPrimitive(1),
        )
        box.tree["$folder/${s.bridge.nextKey()}"] = genuine // a replay
        box.tree["$folder/${s.bridge.nextKey()}"] = "not base64 !!"
        box.tree["$folder/${s.bridge.nextKey()}"] = MailboxKeys.encode("junk".repeat(30).toByteArray())
        box.tree["$folder/${s.bridge.nextKey()}"] = MailboxKeys.encode(reflected)
        box.tree["$folder/${s.bridge.nextKey()}"] = MailboxKeys.encode(stranger)
        box.tree["$folder/strange-key"] = "x"
        advanceTimeBy(200)
        assertEquals(emptyList<MailboxEntry>(), box.children(folder))
        assertEquals(mapOf("replay" to 1, "version" to 1, "direction" to 1, "authentication" to 1), s.channel.dropCounts())
        assertEquals(1, counters.received)
        // The channel still works afterwards.
        assertEquals(200, s.client.request("GET", "/whoami").status)
    }

    @Test
    fun `an answer for a request nobody waits for is taken out and ignored`() = runTest {
        val s = setup(serve = false)
        s.bridge.put(s.bridge.seal(Kind.END, "nobody", json("""{"status":200,"body":1}""")))
        advanceTimeBy(100)
        assertEquals(emptyList<MailboxEntry>(), box.children(MailboxKeys.toPhone(s.bridge.deviceHex)))
        assertEquals(1, counters.received)
    }

    @Test
    fun `without an answer a POST times out and is sent once`() = runTest {
        val s = setup(serve = false)
        assertEquals(RelayException.Reason.TIMEOUT, failure { s.client.request("POST", "/ask", body = json("""{"prompt":"x"}"""), timeoutMillis = 5_000) })
        assertEquals(1, box.recorded.size)
    }

    @Test
    fun `a GET is tried a second time as a new message, never as the same blob`() = runTest {
        val s = setup(serve = false)
        assertEquals(RelayException.Reason.TIMEOUT, failure { s.client.request("GET", "/whoami", timeoutMillis = 5_000) })
        assertEquals(2, box.recorded.size)
        val blobs = box.recorded.map { MailboxKeys.decode(it.second)!! }
        assertEquals(listOf(2L, 3L), toBridgeCounters())
        assertFalse(blobs[0].contentEquals(blobs[1]))
        val rids = blobs.map { E2eHolder.e2e.open(Vectors.secret, it).requestId }
        assertEquals(2, rids.toSet().size)
        assertEquals(2, box.recorded.map { it.first }.toSet().size)
        assertNull(s.bridge.requests.firstOrNull())
    }

    @Test
    fun `a request that certainly did not leave is sent again with a new counter`() = runTest {
        val s = setup()
        s.bridge.answer = { listOf(Kind.END to json("""{"status":200,"body":1}""")) }
        box.putFailures += MailboxException.Reason.NOT_SENT
        assertEquals(200, s.client.request("POST", "/notes", body = json("""{"text":"x"}""")).status)
        assertEquals(listOf(2L, 3L), counters.reserved)
        assertEquals(listOf(3L), toBridgeCounters())
        assertEquals(1, s.bridge.requests.size)
    }

    @Test
    fun `a POST that may have arrived is not sent again`() = runTest {
        val s = setup(serve = false)
        box.putFailures += MailboxException.Reason.UNREACHABLE
        box.failedPutsArrive = true
        assertEquals(RelayException.Reason.UNREACHABLE, failure { s.client.request("POST", "/notes", body = json("""{"text":"x"}""")) })
        assertEquals(1, box.recorded.size)
        assertEquals(listOf(2L), counters.reserved)
    }

    @Test
    fun `a refusal by the relay is reported and not retried`() = runTest {
        val s = setup()
        box.putFailures += MailboxException.Reason.DENIED
        assertEquals(RelayException.Reason.DENIED, failure { s.client.request("GET", "/whoami") })
        assertEquals(listOf(2L), counters.reserved)
    }

    @Test
    fun `at most two tries`() = runTest {
        val s = setup()
        repeat(3) { box.putFailures += MailboxException.Reason.NOT_SENT }
        assertEquals(RelayException.Reason.NOT_SENT, failure { s.client.request("GET", "/whoami") })
        assertEquals(listOf(2L, 3L), counters.reserved)
        assertEquals(1, box.putFailures.size)
    }

    @Test
    fun `the retry rule`() {
        for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
            assertTrue(RelayClient.retryable(RelayException.Reason.NOT_SENT, method))
            assertFalse(RelayClient.retryable(RelayException.Reason.DENIED, method))
            assertEquals(method == "GET", RelayClient.retryable(RelayException.Reason.TIMEOUT, method))
            assertEquals(method == "GET", RelayClient.retryable(RelayException.Reason.UNREACHABLE, method))
        }
    }

    @Test
    fun `a relay that records everything sees no plaintext`() = runTest {
        val s = setup()
        s.bridge.answer = { listOf(Kind.END to json("""{"status":200,"body":{"answer":"echo-7731"}}""")) }
        s.client.request("POST", "/ask", body = json("""{"prompt":"secret-prompt-4471"}"""))
        val everything = box.recorded.joinToString("\n") { "${it.first} ${it.second}" } +
            box.recorded.joinToString("\n") { String(MailboxKeys.decode(it.second)!!, Charsets.ISO_8859_1) }
        for (word in listOf("secret-prompt-4471", "echo-7731", "/ask", "prompt", "POST")) {
            assertFalse(word, word in everything)
        }
    }

    // ------------------------------------------------------------------ streams that are no longer read (#157)

    /** A bridge whose `/live` stream goes on, whose other streams say `done`, and which answers a detach. */
    private fun Setup.streaming() {
        bridge.answer = { request ->
            when (request.jsonObject.getValue("path").jsonPrimitive.content) {
                RelayClient.DETACH_PATH -> listOf(Kind.END to json("""{"status":200,"body":{"detached":true}}"""))
                "/sessions/s-1/live" -> listOf(Kind.CHUNK to json("""{"event":"delta","data":{"text":"a"}}"""))
                else -> listOf(Kind.CHUNK to json("""{"event":"done","data":{"answer":"a"}}"""), Kind.END to json("""{"status":200,"stream":true}"""))
            }
        }
    }

    private fun Setup.paths() = bridge.requests.map { it.payload.jsonObject.getValue("path").jsonPrimitive.content }

    @Test
    fun `a stream that is no longer read is let go on the bridge`() = runTest {
        val s = setup()
        s.streaming()
        // One event is read, then the reader stops: the stream would keep a relay worker.
        val reader = launch { s.client.stream("GET", "/sessions/s-1/live").collect { } }
        advanceTimeBy(100)
        reader.cancel()
        advanceTimeBy(1_000)
        val (stream, detach) = s.bridge.requests
        assertEquals(json("""{"m":"POST","path":"/relay/detach","q":{},"body":{"r":"${stream.requestId}"}}"""), detach.payload)
        // Once, and its own answer is taken out of the mailbox like any other.
        assertEquals(2, s.bridge.requests.size)
        assertEquals(emptyList<MailboxEntry>(), box.children(MailboxKeys.toPhone(s.bridge.deviceHex)))
    }

    @Test
    fun `a stream that gave no sign in time is let go too`() = runTest {
        val s = setup()
        s.bridge.answer = { request -> if (request.jsonObject.getValue("path").jsonPrimitive.content == RelayClient.DETACH_PATH) listOf(Kind.END to json("""{"status":200,"body":{"detached":false}}""")) else emptyList() }
        assertEquals(RelayException.Reason.TIMEOUT, failure { s.client.stream("POST", "/ask", idleTimeoutMillis = 5_000).collect { } })
        advanceTimeBy(1_000)
        assertEquals(listOf("/ask", RelayClient.DETACH_PATH), s.paths())
    }

    @Test
    fun `a stream that said its end, or a plain request, needs no detach`() = runTest {
        val s = setup()
        s.streaming()
        // Read to the end.
        assertEquals(2, s.client.stream("POST", "/ask").toList().size)
        // The reader stops at `done`, before the end of the stream arrives: the bridge closes it by itself.
        var seen = 0
        val reader = launch { s.client.stream("POST", "/ask").collect { if (++seen == 1) throw kotlinx.coroutines.CancellationException("enough") } }
        advanceTimeBy(1_000)
        assertTrue(reader.isCancelled)
        // A request without an answer in time is not a stream.
        s.bridge.answer = { emptyList() }
        assertEquals(RelayException.Reason.TIMEOUT, failure { s.client.request("POST", "/notes", timeoutMillis = 5_000) })
        advanceTimeBy(1_000)
        assertEquals(listOf("/ask", "/ask", "/notes"), s.paths())
    }

    @Test
    fun `before the secret is forgotten every open stream is let go, sealed at once and written afterwards`() = runTest {
        val s = setup()
        s.streaming()
        val reader = launch { s.client.stream("GET", "/sessions/s-1/live").collect { } }
        advanceTimeBy(100)
        // The app is locked: the messages are sealed now, the channel is closed, the reader ends.
        s.client.detachAll(backgroundScope)
        s.channel.close()
        reader.cancel()
        advanceTimeBy(1_000)
        val (stream, detach) = s.bridge.requests
        assertEquals(RelayClient.DETACH_PATH, detach.payload.jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(stream.requestId, detach.payload.jsonObject.getValue("body").jsonObject.getValue("r").jsonPrimitive.content)
        // The cancelled reader does not ask a second time (it could not seal anything any more).
        assertEquals(2, s.bridge.requests.size)
        // The counters went up in order: the bridge took both.
        assertEquals(toBridgeCounters().sorted(), toBridgeCounters())
    }
}
