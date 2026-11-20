package dev.lifeos.app.activity

import dev.lifeos.app.data.Api
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class ActivityStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val today = LocalDate.of(2027, 1, 15)
    private val file get() = File(folder.root, "activity/activity.json")
    private val transport = FakeTransport().apply {
        answer("POST /activity/usage", 200, "{}")
        answer("POST /activity/sleep", 200, "{}")
    }
    private val api = Api(transport)
    private fun day(date: String, minutes: Int, complete: Boolean = true, apps: Int = 2) =
        DayUsage(date, minutes, 5, (1..apps).map { AppUse("app.$it", "Sample app $it", 100 - it) }, complete)
    private fun posts() = transport.calls.map { it.path to it.body!!.jsonObject.getValue("date").toString().trim('"') }

    @Test
    fun `days and nights survive a restart and old ones are dropped after 400 days`() {
        val store = ActivityStore(file)
        store.putDays(listOf(day("2025-12-10", 50), day("2025-12-12", 60), day("2027-01-14", 70)), today)
        store.putManual("2025-12-01", "23:00", "07:00", today)
        store.putManual("2027-01-15", "23:30", "07:10", today)
        val again = ActivityStore(file)
        assertEquals(listOf("2025-12-12", "2027-01-14"), again.days().map { it.date }) // 2025-12-11 is day 400
        assertEquals(listOf("2027-01-15"), again.nights().map { it.date })
    }

    @Test
    fun `every completed day and night is uploaded once`() = runTest {
        val store = ActivityStore(file)
        store.putDays(listOf(day("2027-01-13", 50), day("2027-01-14", 70), day("2027-01-15", 20, complete = false)), today)
        store.putManual("2027-01-15", "23:30", "07:10", today)
        assertEquals(3, ActivitySync.upload(store, api, 1000))
        assertEquals(listOf("/activity/usage" to "2027-01-13", "/activity/usage" to "2027-01-14", "/activity/sleep" to "2027-01-15"), posts())
        assertEquals(1000L, store.lastSyncMillis())
        // Again, and after a restart: nothing more is sent. Today's unfinished day never was.
        assertEquals(0, ActivitySync.upload(store, api, 2000))
        assertEquals(0, ActivitySync.upload(ActivityStore(file), api, 3000))
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun `measuring a day again with the same numbers does not upload it again, new numbers do`() = runTest {
        val store = ActivityStore(file)
        store.putDays(listOf(day("2027-01-14", 70)), today)
        ActivitySync.upload(store, api, 1)
        store.putDays(listOf(day("2027-01-14", 70)), today)
        assertEquals(0, ActivitySync.upload(store, api, 2))
        store.putDays(listOf(day("2027-01-14", 75)), today)
        assertEquals(1, ActivitySync.upload(store, api, 3))
        // Days later the system has forgotten most events: the emptier measurement is ignored.
        store.putDays(listOf(day("2027-01-14", 3)), today)
        assertEquals(75, store.days().single().screenMinutes)
        assertEquals(0, ActivitySync.upload(store, api, 4))
    }

    @Test
    fun `when the computer cannot be reached everything stays for the next time`() = runTest {
        val store = ActivityStore(file)
        store.putDays(listOf(day("2027-01-13", 50), day("2027-01-14", 70)), today)
        transport.fail("POST /activity/usage", ApiException(ApiException.Kind.UNCERTAIN))
        assertEquals(0, ActivitySync.upload(store, api, 1000))
        assertEquals(1, transport.calls.size) // stopped at the first failure
        assertEquals(0L, store.lastSyncMillis())
        // The first try may have arrived; sending the day again only replaces it.
        transport.answer("POST /activity/usage", 200, "{}")
        assertEquals(2, ActivitySync.upload(store, api, 2000))
    }

    @Test
    fun `a day the computer refuses is skipped and the rest goes on`() = runTest {
        val store = ActivityStore(file)
        store.putDays(listOf(day("2027-01-13", 50)), today)
        store.putManual("2027-01-14", "23:00", "07:00", today)
        transport.answer("POST /activity/usage", 400, """{"error":"bad_request"}""")
        assertEquals(1, ActivitySync.upload(store, api, 1000))
        assertFalse(store.days().single().uploaded)
        assertTrue(store.nights().single().uploaded)
    }

    @Test
    fun `an estimate never replaces an entry of the owner, and an entry replaces the estimate and is sent`() = runTest {
        val store = ActivityStore(file)
        store.putEstimate(Night("2027-01-15", "23:40", "07:05", true), today)
        assertEquals(1, ActivitySync.upload(store, api, 1))
        assertEquals("true", transport.calls.last().body!!.jsonObject.getValue("estimated").toString())
        store.putEstimate(Night("2027-01-15", "23:40", "07:05", true), today) // the same guess again
        assertEquals(0, ActivitySync.upload(store, api, 2))
        store.putManual("2027-01-15", "00:15", "07:30", today)
        assertEquals(1, ActivitySync.upload(store, api, 3))
        assertEquals(Json.parseToJsonElement("""{"date":"2027-01-15","bed":"00:15","wake":"07:30","estimated":false}"""), transport.calls.last().body)
        store.putEstimate(Night("2027-01-15", "23:40", "07:05", true), today)
        assertEquals(Night("2027-01-15", "00:15", "07:30", estimated = false, uploaded = true), store.nights().single())
    }

    @Test
    fun `a night corrected while its upload was on the way is sent again`() {
        val store = ActivityStore(file)
        store.putManual("2027-01-15", "23:00", "07:00", today)
        val sent = store.nights().single()
        store.putManual("2027-01-15", "23:30", "07:00", today)
        store.markNightUploaded(sent)
        assertFalse(store.nights().single().uploaded)
    }

    @Test
    fun `at most twenty apps go to the computer, with label and minutes only`() {
        val body = ActivitySync.usageBody(day("2027-01-14", 500, apps = 30))
        val apps = body.getValue("apps").jsonArray
        assertEquals(20, apps.size)
        assertEquals(setOf("label", "minutes"), apps[0].jsonObject.keys)
        assertEquals(setOf("date", "screen_minutes", "unlocks", "apps"), body.keys)
    }

    @Test
    fun `wiping removes the file`() {
        val store = ActivityStore(file)
        store.putDays(listOf(day("2027-01-14", 70)), today)
        store.wipe()
        assertFalse(file.exists())
        assertTrue(store.days().isEmpty() && ActivityStore(file).days().isEmpty())
    }
}
