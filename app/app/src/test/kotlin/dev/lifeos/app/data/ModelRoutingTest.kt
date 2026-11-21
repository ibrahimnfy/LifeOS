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
    fun `a change is sent directly as PUT with every field, and the answer is the new overview`() = runTest {
        val transport = FakeTransport()
        transport.answer(
            "PUT /models/routing",
            200,
            """{"changed":true,"jobs":[{"job":"summaries","model":"haiku","effort":"low","advisor":null,"source":"app",
               "default":{"model":"sonnet","effort":null,"advisor":"opus","source":"default"},"usage":{"today":{},"week":{}}}]}""",
        )
        val api = Api(transport)
        val body = RoutingEdit.body("summaries", RoutingChoice.Value("haiku"), RoutingChoice.Value("low"), RoutingChoice.Off)
        val answer = api.changeRouting(body)
        val sent = transport.calls.single()
        assertEquals("PUT", sent.method)
        assertEquals("/models/routing", sent.path)
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"job":"summaries","model":"haiku","effort":"low","advisor":"none"}"""),
            sent.body,
        )
        val job = answer.jobs.single()
        assertEquals("app", job.source)
        assertEquals("sonnet", job.defaults?.model)
        // Default means null (back to what lies underneath); reset is its own body.
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"job":"chat","model":null,"effort":null,"advisor":null}"""),
            RoutingEdit.body("chat", RoutingChoice.Default, RoutingChoice.Default, RoutingChoice.Default),
        )
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"job":"chat","reset":true}"""), RoutingEdit.reset("chat"))
        // A refusal is an error of the call.
        transport.answer("PUT /models/routing", 400, """{"error":"bad_request"}""")
        assertEquals(400, runCatching { api.changeRouting(body) }.exceptionOrNull().let { (it as ApiException).status })
    }

    @Test
    fun `the editor starts from the app's own choices only`() {
        val base = JobDefault(model = "sonnet", effort = null, advisor = "opus")
        // Not chosen in the app: everything at "Default".
        assertEquals(
            Triple(RoutingChoice.Default, RoutingChoice.Default, RoutingChoice.Default),
            RoutingEdit.initial(ModelJob("summaries", model = "sonnet", advisor = "opus", source = "routing", defaults = base)),
        )
        // Chosen in the app: the values that differ from the default; a missing advisor over one underneath is "off".
        assertEquals(
            Triple(RoutingChoice.Value("haiku"), RoutingChoice.Value("low"), RoutingChoice.Off),
            RoutingEdit.initial(ModelJob("summaries", model = "haiku", effort = "low", advisor = null, source = "app", defaults = base)),
        )
        assertEquals(
            Triple(RoutingChoice.Default, RoutingChoice.Value("high"), RoutingChoice.Default),
            RoutingEdit.initial(ModelJob("summaries", model = "sonnet", effort = "high", advisor = "opus", source = "app", defaults = base)),
        )
    }

    @Test
    fun `the models and efforts on offer are read`() = runTest {
        val transport = FakeTransport()
        transport.answer("GET /models", 200, """{"models":["opus","sonnet","haiku"],"efforts":["low","medium","high"],"default_model":"opus","default_effort":"high"}""")
        val choices = Api(transport).get(Endpoints.modelChoices).first
        assertEquals(listOf("opus", "sonnet", "haiku"), choices.models)
        assertEquals("high", choices.defaultEffort)
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
