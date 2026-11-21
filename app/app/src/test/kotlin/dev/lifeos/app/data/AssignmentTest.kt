package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssignmentTest {
    @Test
    fun `the list and one assignment are read with the bridge's fields`() = runTest {
        val transport = FakeTransport()
        transport.answer(
            "GET /courses/5/assignments",
            200,
            """{"course_id":5,"assignments":[{"id":41,"name":"Assignment 2","opens":1800000000,"opens_iso":"x","due":1800352800,"due_iso":"y","cutoff":null,"cutoff_iso":null,
               "status":{"submission":"new","grading":"notgraded","graded":false},"submitted":false,"files":2,"task":"t-20270115-01"}]}""",
        )
        transport.answer(
            "GET /courses/5/assignments/41",
            200,
            """{"course_id":5,"course":"Example course A","id":41,"name":"Assignment 2","opens":1800000000,"due":1800352800,"cutoff":1800439200,
               "status":null,"submitted":true,"files":[{"key":"k1","name":"Sheet.pdf","type":"application/pdf","size":300,"modified":1800000000,"modified_iso":"z","new":true,"downloaded":false,"skipped":null}],
               "task":null,"grading_due":1800500000,"grading_due_iso":"g","modified_iso":"m","description":"Solve **all** problems."}""",
        )
        val api = Api(transport)
        val list = api.get(Endpoints.assignments(5)).first.assignments
        assertEquals(2, list.single().files)
        assertEquals("t-20270115-01", list.single().task)
        assertEquals(1800000000L, list.single().opens)
        val detail = api.get(Endpoints.assignment(5, 41)).first
        assertEquals("Example course A", detail.course)
        assertTrue(detail.submitted)
        assertEquals(1800439200L, detail.cutoff)
        assertEquals(1800500000L, detail.gradingDue)
        assertEquals("k1", detail.files.single().key)
        assertEquals("Solve **all** problems.", detail.description)
        // An unknown id is an error of the call.
        assertEquals(404, runCatching { api.get(Endpoints.assignment(5, 99)) }.exceptionOrNull().let { (it as ApiException).status })
    }

    @Test
    fun `a deadline of the summary finds its assignment by name and due time`() {
        val list = listOf(Assignment(1, "Quiz 1", due = 100), Assignment(2, "Assignment 2", due = 200), Assignment(3, "Lab report", due = 200))
        assertEquals(2L, AssignmentMatch.find(list, "Assignment 2", 200)?.id)
        // Renamed on the site but the same time: only if one assignment is due then.
        assertEquals(1L, AssignmentMatch.find(list, "Quiz 1 (renamed)", 100)?.id)
        assertNull(AssignmentMatch.find(list, "Other", 200))
        // Moved to another time but the same name.
        assertEquals(3L, AssignmentMatch.find(list, "lab report", 999)?.id)
        assertNull(AssignmentMatch.find(emptyList(), "x", 1))
    }

    @Test
    fun `a deadline that names its assignment opens it directly, others are looked up`() = runTest {
        val transport = FakeTransport()
        transport.answer(
            "GET /courses/summary",
            200,
            """{"configured":true,"deadlines":[
                {"course_id":5,"course":"Example course A","name":"Assignment 2","due":1800352800,"due_iso":"x","assignment_id":41},
                {"course_id":5,"course":"Example course A","name":"Quiz closes","due":1800439200,"due_iso":"y"}]}""",
        )
        transport.answer(
            "GET /courses",
            200,
            """{"courses":[{"id":5,"name":"Example course A","next_deadline":{"name":"Assignment 2","due":1800352800,"course_id":5,"assignment_id":41}}]}""",
        )
        val api = Api(transport)
        val deadlines = api.get(Endpoints.courseSummary).first.deadlines
        assertEquals(41L, AssignmentMatch.direct(deadlines[0]))
        assertNull(AssignmentMatch.direct(deadlines[1]))
        assertEquals(41L, api.get(Endpoints.courses).first.courses.single().nextDeadline?.assignmentId)
        // Without a course there is nothing to open; an id of 0 is no id.
        assertNull(AssignmentMatch.direct(Deadline(name = "x", assignmentId = 41)))
        assertNull(AssignmentMatch.direct(Deadline(courseId = 5, name = "x", assignmentId = 0)))
    }

    @Test
    fun `new pages count among a course's new content`() {
        val counts = BridgeJson.decodeFromString(NewCounts.serializer(), """{"files":1,"pages":2,"announcements":0,"deadlines":0,"grades":0}""")
        assertEquals(2, counts.pages)
        assertEquals(3, counts.total)
    }

    @Test
    fun `the description's Markdown becomes headings, lists, paragraphs and links`() {
        val blocks = SiteMarkdown.blocks(
            """
            # Task
            Solve problems **1-4** on the
            attached sheet.

            - Show your *working*
            2. Hand in one [PDF](https://example.invalid/hand-in) file
            Keep file_name_v2 as it is, and [this](javascript:alert) is no link.
            """.trimIndent(),
        )
        assertEquals(listOf(SiteMarkdown.Kind.HEADING, SiteMarkdown.Kind.PARAGRAPH, SiteMarkdown.Kind.BULLET, SiteMarkdown.Kind.NUMBERED, SiteMarkdown.Kind.PARAGRAPH), blocks.map { it.kind })
        assertEquals("Task", blocks[0].spans.single().text)
        assertEquals(listOf("Solve problems ", "1-4", " on the attached sheet."), blocks[1].spans.map { it.text })
        assertTrue(blocks[1].spans[1].bold)
        assertTrue(blocks[2].spans.last().italic)
        assertEquals("2.", blocks[3].marker)
        assertEquals("https://example.invalid/hand-in", blocks[3].spans.first { it.text == "PDF" }.link)
        assertTrue(blocks[4].spans.joinToString("") { it.text }.contains("file_name_v2"))
        assertNull(blocks[4].spans.first { it.text == "this" }.link)
    }
}
