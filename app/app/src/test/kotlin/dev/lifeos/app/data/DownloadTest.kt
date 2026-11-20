package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayException
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class DownloadTest {
    private class MemorySink : FileSink {
        val data = ByteArrayOutputStream()
        var committed = false
        var aborted = false

        override fun write(bytes: ByteArray, length: Int) = data.write(bytes, 0, length)
        override fun commit() {
            committed = true
        }

        override fun abort() {
            aborted = true
        }
    }

    private val content = ByteArray(5120) { (it % 251).toByte() }
    private val pieces = content.toList().chunked(1000).map { it.toByteArray() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val offer = FileOffer("offer-1", "notes.pdf", "application/pdf", 5120, "course-5")

    private fun chunk(index: Int) = AnswerEvent.FileChunk(index, pieces.size, pieces[index])
    private fun end(size: Long = 5120, chunks: Int = pieces.size, sha: String = hash) =
        AnswerEvent.Done(RelayResponse(200, Json.parseToJsonElement("""{"size":$size,"chunks":$chunks,"sha256":"$sha"}""")))

    private suspend fun failure(transport: FakeTransport, sink: MemorySink): DownloadFailure.Reason {
        try {
            RelayDownload.receive(Api(transport), offer, sink) {}
        } catch (error: DownloadFailure) {
            return error.reason
        }
        fail("expected a DownloadFailure")
        throw IllegalStateException()
    }

    // ---- the direct address

    @Test
    fun `only a plain https address is acceptable`() {
        assertEquals("files.example.invalid", DownloadRules.hostOf("https://files.example.invalid/a/b.pdf?token=x"))
        assertEquals("files.example.invalid", DownloadRules.hostOf("HTTPS://Files.Example.invalid:443/a"))
        for (bad in listOf("http://files.example.invalid/a", "ftp://files.example.invalid/a", "https://user:pw@files.example.invalid/a", "https://files.example.invalid:8443/a", "https:///a", "file:///sdcard/a", "content://x/a", "not an address", "")) {
            assertNull(bad, DownloadRules.hostOf(bad))
        }
    }

    @Test
    fun `a redirect is followed only to the same host over https`() {
        val from = "https://files.example.invalid/a/b.pdf?token=x"
        assertEquals("https://files.example.invalid/c.pdf", DownloadRules.redirectTarget(from, "https://files.example.invalid/c.pdf"))
        assertEquals("https://files.example.invalid/c.pdf", DownloadRules.redirectTarget(from, "/c.pdf"))
        assertEquals("https://files.example.invalid/a/c.pdf", DownloadRules.redirectTarget(from, "c.pdf"))
        for (bad in listOf("https://elsewhere.example.invalid/c.pdf", "http://files.example.invalid/c.pdf", "//elsewhere.example.invalid/c.pdf", "https://files.example.invalid.evil.invalid/c", "https://files.example.invalid@evil.invalid/c", "", null)) {
            assertNull(bad.toString(), DownloadRules.redirectTarget(from, bad))
        }
    }

    @Test
    fun `the address never appears when it is printed`() {
        val address = FileAddress("https://files.example.invalid/a?token=secret-1", "a.pdf", 5)
        assertFalse("secret-1" in address.toString())
        assertFalse("files.example" in "$address")
    }

    @Test
    fun `file names are made safe`() {
        assertEquals("notes.pdf", DownloadRules.safeName("notes.pdf"))
        assertEquals("a_b_c.pdf", DownloadRules.safeName("a/b\\c.pdf"))
        assertEquals("hidden", DownloadRules.safeName("..hidden"))
        assertEquals("file", DownloadRules.safeName(" . "))
        assertEquals("ders notu ğüş.pdf", DownloadRules.safeName("ders notu ğüş.pdf"))
        assertEquals(120, DownloadRules.safeName("x".repeat(300)).length)
        assertFalse(DownloadRules.safeName("a\u0000b\nc").any { it.isISOControl() })
    }

    // ---- chunks through the relay

    @Test
    fun `chunks in sequence with the right size and hash give the file`() = runTest {
        val transport = FakeTransport()
        transport.streams = { flow { pieces.indices.forEach { emit(chunk(it)) }; emit(end()) } }
        val sink = MemorySink()
        val progress = mutableListOf<Long>()
        RelayDownload.receive(Api(transport), offer, sink) { progress += it }
        assertArrayEquals(content, sink.data.toByteArray())
        assertTrue(sink.committed && !sink.aborted)
        assertEquals(listOf(1000L, 2000L, 3000L, 4000L, 5000L, 5120L), progress)
        val call = transport.calls.single()
        assertEquals("/files/offer-1", call.path)
        assertEquals(mapOf("session_id" to "course-5"), call.query)
    }

    @Test
    fun `a wrong hash, size or count removes the file`() = runTest {
        for (wrong in listOf(end(sha = "00".repeat(32)), end(size = 5119), end(chunks = 5))) {
            val transport = FakeTransport()
            transport.streams = { flow { pieces.indices.forEach { emit(chunk(it)) }; emit(wrong) } }
            val sink = MemorySink()
            assertEquals(DownloadFailure.Reason.DAMAGED, failure(transport, sink))
            assertTrue(sink.aborted && !sink.committed)
        }
    }

    @Test
    fun `a missing, repeated or out-of-order chunk removes the file`() = runTest {
        for (order in listOf(listOf(0, 2, 3, 4, 5), listOf(0, 1, 1, 2, 3, 4, 5), listOf(1, 0, 2, 3, 4, 5), listOf(0, 1, 2, 3, 4))) {
            val transport = FakeTransport()
            transport.streams = { flow { order.forEach { emit(chunk(it)) }; emit(end()) } }
            val sink = MemorySink()
            assertEquals(order.toString(), DownloadFailure.Reason.DAMAGED, failure(transport, sink))
            assertTrue(sink.aborted && !sink.committed)
        }
        val sink = MemorySink()
        assertThrows(DownloadFailure::class.java) { ChunkAssembler(sink).add(3, 3, ByteArray(1)) }
    }

    @Test
    fun `after a dropped connection the transfer continues once at the missing chunk`() = runTest {
        val transport = FakeTransport()
        transport.streams = { call ->
            flow {
                if (call.query["from"] == null) {
                    emit(chunk(0)); emit(chunk(1)); emit(chunk(2))
                    throw RelayException(RelayException.Reason.TIMEOUT)
                }
                (call.query.getValue("from").toInt() until pieces.size).forEach { emit(chunk(it)) }
                emit(end())
            }
        }
        val sink = MemorySink()
        RelayDownload.receive(Api(transport), offer, sink) {}
        assertArrayEquals(content, sink.data.toByteArray())
        assertTrue(sink.committed)
        assertEquals(listOf(null, "3"), transport.calls.map { it.query["from"] })
    }

    @Test
    fun `a second drop ends the transfer and removes the file`() = runTest {
        val transport = FakeTransport()
        transport.streams = { flow { emit(chunk(it.query["from"]?.toInt() ?: 0)); throw RelayException(RelayException.Reason.TIMEOUT) } }
        val sink = MemorySink()
        assertEquals(DownloadFailure.Reason.UNREACHABLE, failure(transport, sink))
        assertTrue(sink.aborted)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `the computer's refusals are told apart`() = runTest {
        for ((status, reason) in listOf(404 to DownloadFailure.Reason.NO_COPY, 409 to DownloadFailure.Reason.NO_COPY, 504 to DownloadFailure.Reason.UNREACHABLE)) {
            val transport = FakeTransport()
            transport.streams = { flow { emit(AnswerEvent.Done(RelayResponse(status, null, "x"))) } }
            val sink = MemorySink()
            assertEquals(reason, failure(transport, sink))
            assertTrue(sink.aborted)
        }
    }
}
