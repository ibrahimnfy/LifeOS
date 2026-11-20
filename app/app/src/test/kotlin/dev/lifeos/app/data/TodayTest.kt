package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayTest {
    private val transport = FakeTransport().apply {
        answer("GET /coach/plan", 200, """{"prepared_at":"2027-01-15T06:00:00","messages":[],"top_three":[],"order":[]}""")
        answer("GET /agenda", 200, """{"date":"2027-01-15","tasks_today":[{"id":"t-1","title":"A"}],"tasks_overdue":[]}""")
        answer("POST /coach/plan/refresh", 200, """{"prepared_at":"2027-01-15T10:00:00","messages":[{"id":"m-1","text":"Sample"}]}""")
    }
    private val api = Api(transport)

    @Test
    fun `a pull on Today reads the plan and the agenda and never starts a model run`() = runTest {
        val asked = mutableListOf<Endpoint<*>>()
        TodayReload.reload { asked += it }
        // The screen hands each endpoint to its loader, which reads it like this.
        asked.forEach { api.get(it) }
        assertEquals(listOf("GET /coach/plan", "GET /agenda"), transport.calls.map { "${it.method} ${it.path}" })
        assertTrue(transport.calls.none { it.path == "/coach/plan/refresh" })
        assertTrue(transport.calls.all { it.method == "GET" })
    }

    @Test
    fun `rerun asks for a new plan once and hands it over to be saved`() = runTest {
        var saved: JsonElement? = null
        assertEquals(CoachRerun.State.Done, CoachRerun.run(api) { saved = it })
        assertEquals(listOf("POST /coach/plan/refresh"), transport.calls.map { "${it.method} ${it.path}" })
        assertEquals("2027-01-15T10:00:00", BridgeJson.decodeFromJsonElement(CoachPlan.serializer(), saved!!).preparedAt)
    }

    @Test
    fun `the computer's refusal and other failures are told apart`() = runTest {
        var saved: JsonElement? = null
        transport.answer("POST /coach/plan/refresh", 429, """{"error":"too_soon"}""")
        assertEquals(CoachRerun.State.TooSoon, CoachRerun.run(api) { saved = it })
        transport.answer("POST /coach/plan/refresh", 500, """{"error":"claude_failed"}""")
        assertEquals(CoachRerun.State.Failed, CoachRerun.run(api) { saved = it })
        transport.fail("POST /coach/plan/refresh", ApiException(ApiException.Kind.NOT_SENT))
        assertEquals(CoachRerun.State.Unreachable, CoachRerun.run(api) { saved = it })
        transport.fail("POST /coach/plan/refresh", ApiException(ApiException.Kind.UNCERTAIN))
        assertEquals(CoachRerun.State.NoAnswer, CoachRerun.run(api) { saved = it })
        assertNull(saved)
    }
}
