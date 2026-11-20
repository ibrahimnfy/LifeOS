package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiTest {
    private val transport = FakeTransport.withCourses()
    private val api = Api(transport)

    private suspend fun failure(block: suspend () -> Unit): ApiException {
        try {
            block()
        } catch (error: ApiException) {
            return error
        }
        fail("expected an ApiException")
        throw IllegalStateException()
    }

    @Test
    fun `tasks, events and the agenda are read`() = runTest {
        transport.answer("GET /tasks", 200, """{"tasks":[{"id":"t-20270115-01","title":"Örnek görev","done":false,"due":"2027-01-20","project":null,"course":"ex-101-5","added":"2027-01-15","effort":"small"},{"id":"t-2","title":"No date","done":false,"due":null}]}""")
        transport.answer("GET /events", 200, """{"events":[{"id":"e-1","title":"Sample","date":"2027-01-15","end_date":"2027-01-17","start":null,"end":null,"all_day":true,"note":null},{"id":"e-2","title":"Timed","date":"2027-01-16","end_date":null,"start":"09:30","end":"10:00","all_day":false,"note":"moodle:assign-41"}]}""")
        transport.answer("GET /agenda", 200, """{"date":"2027-01-15","events_today":[],"tasks_today":[{"id":"t-1","title":"A","done":false,"due":"2027-01-15"}],"tasks_overdue":[{"id":"t-0","title":"B","done":false,"due":"2027-01-10"}],"upcoming":{"until":"2027-01-22","events":[],"deadlines":[]}}""")
        val tasks = api.get(Endpoints.tasks).first.tasks
        assertEquals(listOf("t-20270115-01", "t-2"), tasks.map { it.id })
        assertEquals("2027-01-20", tasks[0].due)
        assertEquals("ex-101-5", tasks[0].course)
        assertNull(tasks[1].due)
        val events = api.get(Endpoints.events("2027-01-08", "2027-03-16")).first.events
        assertEquals(mapOf("start" to "2027-01-08", "end" to "2027-03-16"), transport.calls.last().query)
        assertTrue(events[0].allDay)
        assertEquals("2027-01-17", events[0].endDate)
        assertEquals("09:30", events[1].start)
        val agenda = api.get(Endpoints.agenda).first
        assertEquals(listOf("t-1"), agenda.tasksToday.map { it.id })
        assertEquals(listOf("t-0"), agenda.tasksOverdue.map { it.id })
    }

    @Test
    fun `the coach's plan is read, with and without a plan`() = runTest {
        transport.answer("GET /coach/plan", 200, """{"prepared_at":"2027-01-15T06:30:00Z","age_seconds":5400,"stale":false,"messages":[{"id":"m1","kind":"focus","text":"Sample focus","why":"Sample reason","task":"t-1"},{"id":"m2","kind":"question","text":"Sample question","question":"waiting"}],"notification":"m1","top_three":[{"task":{"id":"t-1","title":"A","done":false,"due":null},"reason":"Because"},{"task":{"id":"t-2","title":"B","done":false},"reason":null}],"order":[{"id":"t-1","title":"A","done":false},{"id":"t-2","title":"B","done":false}]}""")
        val plan = api.get(Endpoints.plan).first
        assertTrue(plan.exists)
        assertEquals(5400L, plan.ageSeconds)
        assertEquals(listOf("m1", "m2"), plan.messages.map { it.id })
        assertEquals("Sample reason", plan.messages[0].why)
        assertNull(plan.messages[1].why)
        assertEquals(listOf("Because", null), plan.topThree.map { it.reason })
        assertEquals(2, plan.order.size)

        transport.answer("GET /coach/plan", 200, """{"prepared_at":null,"age_seconds":null,"stale":false,"messages":[],"notification":null,"top_three":[],"order":[{"id":"t-1","title":"A","done":false}]}""")
        val none = api.get(Endpoints.plan).first
        assertFalse(none.exists)
        assertNull(none.ageSeconds)
    }

    @Test
    fun `the course answers of the bridge are read`() = runTest {
        val summary = api.get(Endpoints.courseSummary).first
        assertTrue(summary.configured)
        assertEquals(2, summary.deadlines.size)
        assertEquals(5L, summary.deadlines[0].courseId)
        assertEquals(5, summary.newTotal)
        assertEquals("2027-01-15T17:00:00Z", summary.lastSync)

        val courses = api.get(Endpoints.courses).first.courses
        assertEquals(listOf(5L, 7L), courses.map { it.id })
        assertTrue(courses[0].hasNew)
        assertEquals(5, courses[0].new.total)
        assertFalse(courses[1].hasNew)
        assertEquals(1800352800L, courses[0].nextDeadline?.due)

        val materials = api.get(Endpoints.materials(5)).first
        val files = materials.sections.flatMap { it.files }
        assertTrue(files.size >= 3 && files.all { it.key.isNotEmpty() && it.size > 0 })
        assertTrue(files.any { it.new })
        assertTrue(materials.links.all { it.url.startsWith("https://") })

        assertEquals(3, api.get(Endpoints.announcements(5)).first.announcements.size)
        val assignments = api.get(Endpoints.assignments(5)).first.assignments
        assertNull(assignments[0].status)
        assertEquals("new", assignments[1].status?.submission)
        val grades = api.get(Endpoints.grades(5)).first
        assertEquals("80.00", grades.overall)
        assertEquals("90.00", grades.items[0].grade)
        assertNull(api.get(Endpoints.grades(7)).first.overall)
        assertEquals(listOf(5L, 7L), api.get(Endpoints.allGrades).first.courses.map { it.id })

        val sync = api.syncState()
        assertTrue(sync.configured && !sync.running)
        assertNull(sync.lastError)
        val offer = api.fileOffer(5, "KEY")
        assertEquals("course-5", offer.sessionId)
        assertEquals("notes.pdf", offer.name)
        api.markSeen(5)
        assertEquals("POST /courses/5/seen", transport.calls.last().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `grades that arrive as numbers are read as text`() {
        val grades = BridgeJson.decodeFromString(Grades.serializer(), """{"overall":80.5,"items":[{"name":"x","grade":90,"range":null,"percentage":"90 %"}]}""")
        assertEquals("80.5", grades.overall)
        assertEquals("90", grades.items[0].grade)
        assertNull(grades.items[0].range)
    }

    @Test
    fun `an error answer carries the bridge's status and code`() = runTest {
        val error = failure { api.markSeen(9) }
        assertEquals(ApiException.Kind.HTTP, error.kind)
        assertEquals(404, error.status)
        assertEquals("course_not_found", error.code)
    }

    @Test
    fun `an answer of the wrong shape is an error, not a crash`() = runTest {
        transport.answer("GET /tasks", 200, """{"tasks":"none"}""")
        val error = failure { api.get(Endpoints.tasks) }
        assertEquals("bad_answer", error.code)
    }

    @Test
    fun `writes have the bodies the bridge expects`() = runTest {
        for (key in listOf("POST /notes", "POST /tasks", "POST /tasks/t-1/complete", "POST /events", "PATCH /events/e-1")) {
            transport.answer(key, 201, "{}")
        }
        api.addNote("süt al")
        assertEquals(Json.parseToJsonElement("""{"text":"süt al"}"""), transport.calls.last().body)
        api.addTask("Task", null)
        assertEquals(Json.parseToJsonElement("""{"title":"Task"}"""), transport.calls.last().body)
        api.addTask("Task", "2027-01-20")
        assertEquals(Json.parseToJsonElement("""{"title":"Task","due":"2027-01-20"}"""), transport.calls.last().body)
        api.completeTask("t-1")
        assertNull(transport.calls.last().body)
        api.createEvent(EventDraft("Sample", "2027-01-15"))
        assertEquals(Json.parseToJsonElement("""{"title":"Sample","date":"2027-01-15"}"""), transport.calls.last().body)
        api.createEvent(EventDraft("Sample", "2027-01-15", "2027-01-16", "09:00", "10:00", "note"))
        assertEquals(
            Json.parseToJsonElement("""{"title":"Sample","date":"2027-01-15","end_date":"2027-01-16","start":"09:00","end":"10:00","note":"note"}"""),
            transport.calls.last().body,
        )
    }

    @Test
    fun `changing an event clears optional fields with an explicit null`() = runTest {
        transport.answer("PATCH /events/e-1", 200, "{}")
        api.updateEvent("e-1", EventDraft("Sample", "2027-01-15", end = "10:00", note = " "))
        val body = transport.calls.last().body!!.jsonObject
        assertEquals(setOf("title", "date", "end_date", "start", "end", "note"), body.keys)
        assertEquals(JsonNull, body["end_date"])
        assertEquals(JsonNull, body["start"])
        assertEquals(JsonNull, body["end"]) // an end without a start means nothing
        assertEquals(JsonNull, body["note"])
    }
}
