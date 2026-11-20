package dev.lifeos.app.push

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.Header
import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Vectors
import dev.lifeos.app.crypto.testSodium
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** Push hints: sealed by the bridge with the push key (vectors), opened here with that key alone. */
class PushHintsTest {
    private val e2e = E2e(testSodium)
    private val pushKey = e2e.deriveKey(Vectors.secret, Direction.PUSH)
    private var last = 0L
    private val accept: (Long) -> Boolean = { counter -> (counter > last).also { if (it) last = counter } }

    private fun field(name: String) = Base64.getEncoder().encodeToString(Vectors.named(name).blob)
    private fun open(field: String?, key: ByteArray = pushKey, device: ByteArray = Vectors.deviceId) =
        PushHints.open(e2e, key, device, field, accept)
    private fun sealed(payload: String, direction: Int = Direction.PUSH, kind: Int = Kind.PUSH_HINT, counter: Long = 50, secret: ByteArray = Vectors.secret) =
        Base64.getEncoder().encodeToString(
            e2e.seal(secret, Header(Vectors.deviceId, direction, counter, kind), 1_800_000_000, "", Json.parseToJsonElement(payload)),
        )

    @Test
    fun `the bridge's hints are opened with the push key alone`() {
        assertEquals(Hint("answer", "session-1"), open(field("hint_answer")))
        assertEquals(Hint("notice", "", "Örnek başlık", "https://example.invalid/issue/1"), open(field("hint_notice")))
        assertEquals(Hint("coach"), open(field("hint_coach")))
        assertEquals(10L, last)
    }

    @Test
    fun `a repeated or older hint is dropped`() {
        assertEquals("notice", open(field("hint_notice"))?.event)
        assertNull(open(field("hint_notice")))
        assertNull(open(field("hint_answer"))) // counter 8 after 9
        assertEquals("coach", open(field("hint_coach"))?.event)
    }

    @Test
    fun `a hint for another device, with another key or of an unknown event is dropped without moving the counter`() {
        assertNull(open(field("hint_answer"), device = ByteArray(16) { 1 }))
        assertNull(open(field("hint_answer"), key = ByteArray(32) { 1 }))
        assertNull(open(sealed("""{"e":"something"}""")))
        assertNull(open(sealed("""{"r":"x"}""")))
        assertNull(open(sealed("""["e"]""")))
        assertNull(open(field("push_hint"))) // the old shape without an event
        assertEquals(0L, last)
        assertEquals("answer", open(field("hint_answer"))?.event)
    }

    @Test
    fun `messages of the other directions and kinds are not hints, and the push key cannot open them`() {
        // A request and an answer from the vectors: other keys, other directions.
        assertNull(open(field("request")))
        assertNull(open(field("pair_end")))
        // Sealed for the push direction but as another kind, or a hint sealed for another direction.
        assertNull(open(sealed("""{"e":"answer"}""", kind = Kind.END)))
        assertNull(open(sealed("""{"e":"answer"}""", direction = Direction.BRIDGE_TO_PHONE)))
        // The push key opens no ordinary message at all.
        for (name in listOf("request", "pair_end", "chunk", "end")) {
            org.junit.Assert.assertThrows(dev.lifeos.app.crypto.Rejected::class.java) { e2e.openWithKey(pushKey, Vectors.named(name).blob) }
        }
    }

    @Test
    fun `tampered and malformed fields are dropped`() {
        val blob = Vectors.named("hint_answer").blob
        for (index in blob.indices) {
            val changed = blob.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull("byte $index", open(Base64.getEncoder().encodeToString(changed)))
        }
        assertNull(open(null))
        assertNull(open(""))
        assertNull(open("not base64 !!"))
        assertNull(open("AAAA"))
        assertEquals(0L, last)
    }

    @Test
    fun `every event has a fixed kind of notification and a place to open`() {
        val expected = mapOf(
            "answer" to (NotificationKind.ANSWER to Destination.Chat("s1")),
            "reminder" to (NotificationKind.REMINDER to Destination.Sessions),
            "coach" to (NotificationKind.COACH to Destination.Coach),
            "courses" to (NotificationKind.COURSES to Destination.Courses),
            "signin" to (NotificationKind.COURSES to Destination.Courses),
            "notice" to (NotificationKind.NOTICE to Destination.Today),
            "approval" to (NotificationKind.WAITING to Destination.Chat("s1")),
            "question" to (NotificationKind.WAITING to Destination.Chat("s1")),
        )
        assertEquals(PushHints.EVENTS, expected.keys)
        for ((event, pair) in expected) {
            val hint = Hint(event, "s1")
            assertEquals(event, pair.first, PushHints.kindOf(hint))
            assertEquals(event, pair.second, PushHints.destinationOf(hint))
        }
        assertEquals(Destination.Sessions, PushHints.destinationOf(Hint("answer")))
    }

    @Test
    fun `only a notice may show text from the computer, and only its sealed title, as one clean line`() {
        assertEquals("Sample title", PushHints.noticeTitle(Hint("notice", title = " Sample\u0000 title\n")))
        assertEquals(120, PushHints.noticeTitle(Hint("notice", title = "x".repeat(500)))!!.length)
        assertNull(PushHints.noticeTitle(Hint("notice", title = " \n")))
        assertNull(PushHints.noticeTitle(Hint("notice")))
        for (event in PushHints.EVENTS - "notice") assertNull(event, PushHints.noticeTitle(Hint(event, title = "Sample title")))
    }

    @Test
    fun `destinations survive the way through a notification`() {
        for (destination in listOf(Destination.Chat("session-1"), Destination.Coach, Destination.Courses, Destination.Sessions, Destination.Today, Destination.Sleep)) {
            assertEquals(destination, Destination.decode(destination.encode()))
        }
        assertNull(Destination.decode(null))
        assertNull(Destination.decode("settings"))
        assertEquals(300, (Destination.decode("chat:" + "x".repeat(900)) as Destination.Chat).sessionId.length)
    }
}
