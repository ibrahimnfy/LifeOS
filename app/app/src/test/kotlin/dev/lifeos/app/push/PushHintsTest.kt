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
    fun `every event has its channel and a place to open, also for older hints without c and o`() {
        val expected = mapOf(
            "answer" to (PushChannel.CHAT to Destination.Chat("s1")),
            "question" to (PushChannel.CHAT to Destination.Chat("s1")),
            "approval" to (PushChannel.CHAT to Destination.Chat("s1")),
            "courses" to (PushChannel.COURSES to Destination.Courses),
            "signin" to (PushChannel.COURSES to Destination.CourseSignIn),
            "coach" to (PushChannel.COACH to Destination.Coach("s1")),
            "reminder" to (PushChannel.REMINDERS to Destination.Reminder("s1")),
            "notice" to (PushChannel.REMINDERS to Destination.Notice),
            "update" to (PushChannel.REMINDERS to Destination.AppUpdate),
        )
        assertEquals(PushHints.EVENTS, expected.keys)
        for ((event, pair) in expected) {
            val hint = Hint(event, "s1")
            assertEquals(event, pair.first, PushHints.channelOf(hint))
            assertEquals(event, pair.second, PushHints.destinationOf(hint))
        }
        assertEquals(Destination.Sessions, PushHints.destinationOf(Hint("answer")))
        assertEquals(Destination.Today, PushHints.destinationOf(Hint("reminder")))
    }

    @Test
    fun `the bridge's channel, screen, item and counts are read from a hint`() {
        val courses = open(sealed("""{"e":"courses","c":"courses","o":"course","r":"12","n":{"files":2,"grades":1,"bad":"x","zero":0,"huge":99999}}"""))!!
        assertEquals(PushChannel.COURSES, PushHints.channelOf(courses))
        assertEquals(Destination.Course(12), PushHints.destinationOf(courses))
        assertEquals(mapOf("files" to 2, "grades" to 1, "huge" to 9999), courses.counts)
        // Several courses changed: the Courses screen.
        assertEquals(Destination.Courses, PushHints.destinationOf(Hint("courses", channel = "courses", opens = "courses")))
        assertEquals(Destination.CourseSignIn, PushHints.destinationOf(Hint("signin", opens = "moodle_signin")))
        assertEquals(Destination.AppUpdate, PushHints.destinationOf(Hint("update", "42", channel = "reminders", opens = "app_update")))
        assertEquals(Destination.AppUpdate, Destination.decode(Destination.AppUpdate.encode()))
        // A nudge names its message; the daily note may name none.
        assertEquals(Destination.Coach("m-2"), PushHints.destinationOf(Hint("coach", "m-2", channel = "coach", opens = "coach")))
        assertEquals(Destination.Coach(), PushHints.destinationOf(Hint("coach")))
        // The consolidation proposal comes as a coach hint that opens its screen (#151).
        val proposal = Hint("coach", "c-20260101", channel = "coach", opens = "consolidation")
        assertEquals(Destination.Consolidation("c-20260101"), PushHints.destinationOf(proposal))
        assertEquals(PushChannel.COACH, PushHints.channelOf(proposal))
        assertEquals(Destination.Chat("abc"), PushHints.destinationOf(Hint("answer", "abc", channel = "chat", opens = "session")))
        // An unknown channel falls back to the event's; an unknown screen to Today.
        assertEquals(PushChannel.COACH, PushHints.channelOf(Hint("coach", channel = "elsewhere")))
        assertEquals(Destination.Today, PushHints.destinationOf(Hint("coach", opens = "elsewhere")))
    }

    @Test
    fun `replies of one session, and all course changes, share one notification`() {
        assertEquals(PushHints.notificationKey(Hint("answer", "s1")), PushHints.notificationKey(Hint("question", "s1")))
        assertEquals(false, PushHints.notificationKey(Hint("answer", "s1")) == PushHints.notificationKey(Hint("answer", "s2")))
        assertEquals(PushHints.notificationKey(Hint("courses", "1")), PushHints.notificationKey(Hint("courses", "2")))
        assertEquals(PushHints.notificationKey(Hint("courses")), PushHints.notificationKey(Hint("signin")))
        assertEquals(false, PushHints.notificationKey(Hint("reminder", "r1")) == PushHints.notificationKey(Hint("reminder", "r2")))
        // A newer release replaces the notice of an older one.
        assertEquals(PushHints.notificationKey(Hint("update", "41")), PushHints.notificationKey(Hint("update", "42")))
    }

    @Test
    fun `a notice's link is kept only when it is a web address`() {
        assertEquals("https://example.invalid/x", PushHints.noticeLink(Hint("notice", link = "https://example.invalid/x")))
        assertNull(PushHints.noticeLink(Hint("notice", link = "intent://x")))
        assertNull(PushHints.noticeLink(Hint("answer", link = "https://example.invalid/x")))
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
        val all = listOf(
            Destination.Chat("session-1"), Destination.Course(12), Destination.Reminder("r-1"), Destination.Coach(), Destination.Coach("m-2"), Destination.Consolidation(), Destination.Consolidation("c-1"), Destination.Courses,
            Destination.CourseSignIn, Destination.Sessions, Destination.Today, Destination.Notice, Destination.Sleep,
        )
        for (destination in all) {
            assertEquals(destination, Destination.decode(destination.encode()))
        }
        assertNull(Destination.decode(null))
        assertNull(Destination.decode("settings"))
        assertEquals(300, (Destination.decode("chat:" + "x".repeat(900)) as Destination.Chat).sessionId.length)
    }
}
