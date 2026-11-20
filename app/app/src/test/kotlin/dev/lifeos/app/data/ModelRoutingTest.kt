package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRoutingTest {
    @Test
    fun `the routing and usage of every job are read`() = runTest {
        val transport = FakeTransport()
        transport.answer(
            "GET /models/routing",
            200,
            """{"jobs":[
                {"job":"chat","label":"Chat sessions","model":"opus","effort":"high","advisor":null,"source":"default","per_session":true,
                 "usage":{"today":{"runs":2,"input_tokens":1000,"output_tokens":500,"cache_creation_input_tokens":200,"cache_read_input_tokens":10000,"cost_usd":0.42,"models":["claude-opus"]},
                          "week":{"runs":9,"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"cost_usd":1.5,"models":[]}}},
                {"job":"summaries","label":"Summaries","model":"sonnet","effort":null,"advisor":"opus","source":"routing","per_session":false,
                 "usage":{"today":{"runs":0},"week":{"runs":0}}}],
               "no_model":["saving notes and tasks","course sync","notifications"],"today":"2027-01-15","week_from":"2027-01-11","note":"x"}""",
        )
        val routing = Api(transport).get(Endpoints.modelRouting).first
        assertEquals(listOf("chat", "summaries"), routing.jobs.map { it.job })
        val chat = routing.jobs[0]
        assertTrue(chat.perSession)
        assertEquals(11_700L, chat.usage.today.tokens)
        assertEquals(0.42, chat.usage.today.costUsd, 1e-9)
        assertEquals(9, chat.usage.week.runs)
        val summaries = routing.jobs[1]
        assertEquals("routing", summaries.source)
        assertEquals("opus", summaries.advisor)
        assertNull(summaries.effort)
        assertEquals(3, routing.noModel.size)
        assertEquals("2027-01-11", routing.weekFrom)
        assertTrue(transport.calls.all { it.method == "GET" })
    }

    @Test
    fun `usage numbers are short`() {
        assertEquals("950", UsageNumbers.compact(950))
        assertEquals("12.3k", UsageNumbers.compact(12_345))
        assertEquals("2k", UsageNumbers.compact(2_000))
        assertEquals("250k", UsageNumbers.compact(250_400))
        assertEquals("4.2M", UsageNumbers.compact(4_200_000))
        assertEquals("$0", UsageNumbers.dollars(0.0))
        assertEquals("<$0.01", UsageNumbers.dollars(0.004))
        assertEquals("$0.42", UsageNumbers.dollars(0.4249))
    }
}
