package dev.lifeos.app.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseTest {
    private fun events(text: String): List<SseEvent> {
        val parser = SseParser()
        return text.split("\n").mapNotNull { parser.feed(it) }
    }

    @Test
    fun `an event ends at the blank line`() {
        val parser = SseParser()
        assertNull(parser.feed("event: put"))
        assertNull(parser.feed("""data: {"path":"/","data":null}"""))
        assertEquals(SseEvent("put", """{"path":"/","data":null}"""), parser.feed(""))
        assertNull(parser.feed(""))
    }

    @Test
    fun `several events, carriage returns, comments and missing spaces`() {
        val found = events("event:keep-alive\r\ndata:null\r\n\r\n: comment\nevent: put\ndata: a\ndata: b\n\nid: 7\nretry: 5\n\ndata: only\n\n")
        assertEquals(
            listOf(SseEvent("keep-alive", "null"), SseEvent("put", "a\nb"), SseEvent("message", "only")),
            found,
        )
    }

    @Test
    fun `an unfinished event is not delivered`() {
        assertEquals(emptyList<SseEvent>(), events("event: put\ndata: x"))
    }

    @Test
    fun `the first snapshot lists every entry in key order`() {
        val signal = RtdbStream.interpret(SseEvent("put", """{"path":"/","data":{"b-key":"Qg==","a-key":"QQ=="}}"""))
        assertEquals(StreamSignal.Entries(listOf(MailboxEntry("a-key", "QQ=="), MailboxEntry("b-key", "Qg=="))), signal)
    }

    @Test
    fun `a later put or patch names one entry`() {
        assertEquals(
            StreamSignal.Entries(listOf(MailboxEntry("k1", "QQ=="))),
            RtdbStream.interpret(SseEvent("put", """{"path":"/k1","data":"QQ=="}""")),
        )
        assertEquals(
            StreamSignal.Entries(listOf(MailboxEntry("k2", "Qg=="))),
            RtdbStream.interpret(SseEvent("patch", """{"path":"/","data":{"k2":"Qg==","gone":null}}""")),
        )
    }

    @Test
    fun `an entry that is not text is still reported so it can be removed`() {
        assertEquals(
            StreamSignal.Entries(listOf(MailboxEntry("k", null))),
            RtdbStream.interpret(SseEvent("put", """{"path":"/k","data":{"x":1}}""")),
        )
    }

    @Test
    fun `empty snapshots, deletions, deeper paths and rubbish mean nothing`() {
        for (data in listOf(
            """{"path":"/","data":null}""",
            """{"path":"/k1","data":null}""",
            """{"path":"/k1/deeper","data":"x"}""",
            """{"path":"/","data":"text"}""",
            "not json",
            "[]",
            "null",
        )) {
            assertEquals(data, StreamSignal.Nothing, RtdbStream.interpret(SseEvent("put", data)))
        }
        assertEquals(StreamSignal.Nothing, RtdbStream.interpret(SseEvent("message", "x")))
    }

    @Test
    fun `keep-alive, cancel and auth_revoked are told apart`() {
        assertEquals(StreamSignal.KeepAlive, RtdbStream.interpret(SseEvent("keep-alive", "null")))
        assertEquals(StreamSignal.Closed(authRevoked = false), RtdbStream.interpret(SseEvent("cancel", "null")))
        assertEquals(StreamSignal.Closed(authRevoked = true), RtdbStream.interpret(SseEvent("auth_revoked", "x")))
    }
}
