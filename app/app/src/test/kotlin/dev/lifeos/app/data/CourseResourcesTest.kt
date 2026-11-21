package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The resources list of a course (#128, #160): each item downloaded, or why it is only listed. */
class CourseResourcesTest {
    private val transport = FakeTransport()
    private val api = Api(transport)

    private fun item(status: String, reason: String?) = CourseResource(title = "Sample book", url = "https://books.example.org/a.pdf", status = status, reason = reason)

    @Test
    fun `the view is read, with whether the owner has named the resource folders`() = runTest {
        transport.answer(
            "GET /courses/41/resources",
            200,
            """{"course_id":41,"syllabus":{"name":"Syllabus.pdf"},"items":[{"title":"Open book","url":"https://books.example.org/open.pdf","kind":"open_access_book","licence":"CC BY","why_legal":"Open licence","reference":"Chapter 1","status":"listed","file":null,"reason":"folders_not_named"},{"title":"Known book","url":"https://books.example.org/known.pdf","kind":"open_access_book","licence":null,"why_legal":null,"reference":null,"status":"listed","file":null,"reason":"already_present"}],"running":false,"pending":false,"folders_named":false,"last_run":"2027-01-15T06:00:00Z","last_result":"ok"}""",
        )
        val view = api.get(CourseResources.endpoint(41)).first
        assertFalse(view.foldersNamed)
        assertEquals(listOf(CourseResources.Reason.FOLDERS_NOT_NAMED, CourseResources.Reason.ALREADY_PRESENT), view.items.map { CourseResources.reasonOf(it) })
        assertEquals("CC BY", view.items[0].licence)
        // A view from before #160 has no such field: nothing to say about the folders.
        transport.answer("GET /courses/41/resources", 200, """{"course_id":41,"syllabus":null,"items":[]}""")
        assertTrue(api.get(CourseResources.endpoint(41)).first.foldersNamed)
    }

    @Test
    fun `every reason the computer gives is worded, and an unknown one falls back`() {
        assertNull(CourseResources.reasonOf(item("downloaded", null)))
        val expected = mapOf(
            "paid" to CourseResources.Reason.PAID,
            "other" to CourseResources.Reason.OTHER,
            "folders_not_named" to CourseResources.Reason.FOLDERS_NOT_NAMED,
            "already_present" to CourseResources.Reason.ALREADY_PRESENT,
            "not_https" to CourseResources.Reason.NOT_HTTPS,
            "private_address" to CourseResources.Reason.NOT_PUBLIC,
            "bad_address" to CourseResources.Reason.NOT_PUBLIC,
            "not_a_document" to CourseResources.Reason.NOT_A_DOCUMENT,
            "too_large" to CourseResources.Reason.TOO_LARGE,
            "download_failed" to CourseResources.Reason.FAILED,
            "unreachable" to CourseResources.Reason.FAILED,
            "host_not_allowed" to CourseResources.Reason.FAILED,
            "later" to CourseResources.Reason.LATER,
            "audit_unavailable" to CourseResources.Reason.UNRECORDED,
            "something_new" to CourseResources.Reason.UNKNOWN,
        )
        expected.forEach { (word, reason) -> assertEquals(word, reason, CourseResources.reasonOf(item("listed", word))) }
        assertEquals(CourseResources.Reason.UNKNOWN, CourseResources.reasonOf(item("listed", null)))
    }
}
